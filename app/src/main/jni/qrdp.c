/*
 * qrdp.c —— FreeRDP 的 Android JNI 薄封装。
 *
 * 设计要点：
 *  - 采用 FreeRDP 官方 client API（freerdp_client_context_new），
 *    走与 xfreerdp / aFreeRDP 完全相同的连接、NLA、能力集协商路径，
 *    不再自己拼协议字节。
 *  - 画面：GDI 直接绘制到本模块 malloc 的 BGRA32 缓冲区（零拷贝），
 *    每帧结束（EndPaint）只把脏矩形复制进 Java 的 Bitmap。
 *  - 线程：连接与事件循环跑在独立的 pthread 里，Java 侧轮询状态位，
 *    不做任何 JNI 回调，避免线程与生命周期问题。
 */
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

/* 注意：本机平板默认把 INFO 级日志过滤掉了，所以诊断信息统一走 WARN。 */
#define QLOGE(...) __android_log_print(ANDROID_LOG_ERROR, "qrdp", __VA_ARGS__)
#define QLOGI(...) __android_log_print(ANDROID_LOG_WARN, "qrdp", __VA_ARGS__)

#include <errno.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <freerdp/freerdp.h>
#include <freerdp/constants.h>
#include <freerdp/gdi/gdi.h>
#include <freerdp/input.h>
#include <freerdp/update.h>
#include <freerdp/client.h>
#include <freerdp/client/cmdline.h>
#include <freerdp/client/channels.h>
#include <freerdp/client/disp.h>
#include <freerdp/channels/channels.h>
#include <freerdp/channels/disp.h>
#include <freerdp/log.h>

#include <winpr/synch.h>
#include <winpr/collections.h>
#include <winpr/thread.h>
#include <winpr/wlog.h>

#define TAG "qrdp"

/* 会话状态 */
#define ST_INIT 0
#define ST_CONNECTING 1
#define ST_CONNECTED 2
#define ST_CLOSED 3
#define ST_ERROR 4

typedef struct qrdp_s qrdp_t;

/* 扩展的 rdpContext：第一个成员必须是 rdpContext。 */
typedef struct
{
	rdpContext _p;
	qrdp_t* owner;
} qrdpContext;

struct qrdp_s
{
	rdpContext* context;
	freerdp* instance;

	JavaVM* vm;
	JNIEnv* env; /* 连接线程的 JNI 环境 */
	jobject bitmap; /* 全局引用 */

	BYTE* fb; /* GDI 直接绘制的缓冲区 */
	UINT32 stride;
	int width;
	int height;
	int scale;                  /* Java 请求的远端缩放百分比 */
	DispClientContext* disp;     /* MS-RDPEDISP 通道接口，由 ChannelConnected 事件注入 */
	volatile int dispSent;

	int bmpWidth;
	int bmpHeight;

	volatile int state;
	volatile int frameSeq;
	volatile int stop;
	volatile int attached;   /* bitmap 是否已绑定 */
	volatile int jni_attached; /* 连接线程是否已 attach JVM */

	char errmsg[512];

	char* user;
	char* domain;
	char* password;

	pthread_t thread;
	int threadStarted;
	pthread_mutex_t lock;
};

static void qrdp_set_error(qrdp_t* q, const char* msg)
{
	if (!q)
		return;
	pthread_mutex_lock(&q->lock);
	snprintf(q->errmsg, sizeof(q->errmsg), "%s", msg ? msg : "");
	pthread_mutex_unlock(&q->lock);
}

static void qrdp_set_error_code(qrdp_t* q, const char* prefix, UINT32 code)
{
	char buf[512];
	snprintf(buf, sizeof(buf), "%s (0x%08X)", prefix ? prefix : "错误", code);
	qrdp_set_error(q, buf);
}

/* 把关心的 FreeRDP 日志标签打到 logcat，便于定位连接失败的具体协议阶段。 */
static void qrdp_enable_debug_logs(void)
{
	static const char* tags[] = {
		"com.freerdp.core",        "com.freerdp.core.rdp",     "com.freerdp.core.nego",
		"com.freerdp.core.mcs",    "com.freerdp.core.security", "com.freerdp.core.connection",
		"com.freerdp.core.transport", "com.freerdp.core.license", "com.freerdp.core.activation",
		"com.freerdp.core.capabilities", "com.freerdp.core.codecs", "com.winpr.sspi",
	};
	size_t i;
	for (i = 0; i < sizeof(tags) / sizeof(tags[0]); i++)
		WLog_SetLogLevel(WLog_Get(tags[i]), WLOG_DEBUG);
}


/* ------------------------------------------------------------------ */
/* 画面回调                                                            */
/* ------------------------------------------------------------------ */

static BOOL qrdp_begin_paint(rdpContext* context)
{
	WINPR_UNUSED(context);
	return TRUE;
}

/* 把一片矩形从 GDI 缓冲区复制到 Java Bitmap 的像素区。 */
static void qrdp_blit(qrdp_t* q, BYTE* dst, int dstStride, int l, int t, int r, int b)
{
	if (l < 0)
		l = 0;
	if (t < 0)
		t = 0;
	if (r > q->width)
		r = q->width;
	if (b > q->height)
		b = q->height;
	if (r <= l || b <= t)
		return;

	const int rowBytes = (r - l) * 4;
	const int srcStride = (int)q->stride;
	for (int y = t; y < b; y++)
	{
		const BYTE* s = q->fb + (size_t)y * srcStride + (size_t)l * 4;
		BYTE* d = dst + (size_t)y * dstStride + (size_t)l * 4;
		memcpy(d, s, (size_t)rowBytes);
	}
}

static BOOL qrdp_end_paint(rdpContext* context)
{
	qrdpContext* qc = (qrdpContext*)context;
	qrdp_t* q = qc ? qc->owner : NULL;
	rdpGdi* gdi = context->gdi;

	if (!q || !gdi || !gdi->primary || !q->fb)
		return TRUE;

	if (!q->bitmap || !q->attached)
	{
		q->frameSeq++;
		return TRUE;
	}

	JNIEnv* env = q->env;
	if (!env)
	{
		QLOGE("end_paint: env 为空 (jni_attached=%d)", (int)q->jni_attached);
		return TRUE;
	}

	AndroidBitmapInfo info;
	if (AndroidBitmap_getInfo(env, q->bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS)
	{
		QLOGE("end_paint: getInfo 失败");
		return TRUE;
	}

	void* pixels = NULL;
	if (AndroidBitmap_lockPixels(env, q->bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
	{
		QLOGE("end_paint: lockPixels 失败");
		return TRUE;
	}

	/* 全屏 blit：GDI 已把内容画进 q->fb，直接整块复制到 Bitmap */
	{
		int copyH = q->height < (int)info.height ? q->height : (int)info.height;
		int copyW = q->width < (int)info.width ? q->width : (int)info.width;
		int srcStride = (int)q->stride;
		int dstStride = (int)info.stride;
		for (int y = 0; y < copyH; y++)
		{
			memcpy((BYTE*)pixels + (size_t)y * dstStride,
			       q->fb + (size_t)y * srcStride,
			       (size_t)copyW * 4);
		}
	}

	AndroidBitmap_unlockPixels(env, q->bitmap);

	q->frameSeq++;
	if (q->frameSeq <= 5 || q->frameSeq % 100 == 0)
	{
		/* 抽样打日志：前 5 帧 + 每 100 帧 */
		unsigned char* fb = q->fb;
		QLOGE("end_paint #%d fb[0..3]=%02X%02X%02X%02X bmp=%dx%d fb=%dx%d",
		      q->frameSeq, fb[0], fb[1], fb[2], fb[3],
		      q->bmpWidth, q->bmpHeight, q->width, q->height);
	}
	return TRUE;
}

static BOOL qrdp_play_sound(rdpContext* context, const PLAY_SOUND_UPDATE* play_sound)
{
	WINPR_UNUSED(context);
	WINPR_UNUSED(play_sound);
	return TRUE;
}

/* ------------------------------------------------------------------ */
/* 客户端回调                                                          */
/* ------------------------------------------------------------------ */

/*
 * 远端 Windows 的「缩放与布局」由 MS-RDPEDISP 通道的 Monitor Layout PDU 决定：
 * 建链期 Client Core Data 里的 desktopScaleFactor 只是初始声明，Win10 常常忽略。
 * 通道连接后（或 post_connect 完成后，取较晚者）带 DesktopScaleFactor 发一次布局，
 * 服务端据此设置会话 DPI。100% 不发送，保持与旧版一致的建链行为。
 */
static void qrdp_try_send_scale(qrdp_t* q)
{
	DISPLAY_CONTROL_MONITOR_LAYOUT layout;
	UINT rc;
	if (!q || q->dispSent || !q->disp || !q->disp->SendMonitorLayout)
		return;
	if (q->width <= 0 || q->height <= 0 || q->scale <= 100)
		return;
	q->dispSent = 1;
	memset(&layout, 0, sizeof(layout));
	layout.Flags = DISPLAY_CONTROL_MONITOR_PRIMARY;
	layout.Left = 0;
	layout.Top = 0;
	layout.Width = (UINT32)q->width;
	layout.Height = (UINT32)q->height;
	layout.PhysicalWidth = (UINT32)(q->width * 25.4 / 75);
	layout.PhysicalHeight = (UINT32)(q->height * 25.4 / 75);
	layout.Orientation = ORIENTATION_LANDSCAPE;
	layout.DesktopScaleFactor = (UINT32)q->scale;
	layout.DeviceScaleFactor = 100;
	rc = q->disp->SendMonitorLayout(q->disp, 1, &layout);
	QLOGI("disp 缩放 %d%% 桌面 %dx%d -> rc=%u", q->scale, q->width, q->height,
	      (unsigned)rc);
}

static void qrdp_on_channel_connected(void* context, ChannelConnectedEventArgs* e)
{
	qrdpContext* qc = (qrdpContext*)context;
	if (!qc || !qc->owner || !e || !e->name)
		return;
	if (strcmp(e->name, DISP_DVC_CHANNEL_NAME) == 0)
	{
		qc->owner->disp = (DispClientContext*)e->pInterface;
		QLOGI("disp 通道已连接");
		qrdp_try_send_scale(qc->owner);
	}
}

static BOOL qrdp_pre_connect(freerdp* instance)
{
	QLOGE("pre_connect: RedirectClipboard=%d", (int)instance->settings->RedirectClipboard);
	PubSub_SubscribeChannelConnected(instance->context->pubSub, qrdp_on_channel_connected);
	if (!freerdp_client_load_addins(instance->context->channels, instance->settings))
	{
		WLog_ERR(TAG, "加载通道插件失败");
		return FALSE;
	}
	return TRUE;
}

static BOOL qrdp_post_connect(freerdp* instance)
{
	qrdpContext* qc = (qrdpContext*)instance->context;
	qrdp_t* q = qc->owner;

	rdpSettings* settings = instance->settings;
	q->width = (int)settings->DesktopWidth;
	q->height = (int)settings->DesktopHeight;
	q->stride = (UINT32)q->width * 4;

	free(q->fb);
	q->fb = (BYTE*)malloc((size_t)q->stride * (size_t)q->height);
	if (!q->fb)
	{
		qrdp_set_error(q, "分配帧缓冲失败");
		return FALSE;
	}
	memset(q->fb, 0, (size_t)q->stride * (size_t)q->height);

	/* RGBA32 内存序 = R,G,B,A，与 Android Bitmap(ARGB_8888) 的内存序一致，
	 * end_paint 直拷即可；用 BGRA32 会导致 R/B 互换（蓝色变桔色）。 */
	if (!gdi_init_ex(instance, PIXEL_FORMAT_RGBA32, q->stride, q->fb, NULL))
	{
		qrdp_set_error(q, "初始化 GDI 失败");
		return FALSE;
	}

	instance->update->BeginPaint = qrdp_begin_paint;
	instance->update->EndPaint = qrdp_end_paint;
	instance->update->PlaySound = qrdp_play_sound;

	/* disp 通道可能早于 post_connect 连上，这里再补一次，保证宽高已就绪 */
	qrdp_try_send_scale(q);

	return TRUE;
}

static void qrdp_post_disconnect(freerdp* instance)
{
	qrdpContext* qc;
	if (!instance || !instance->context)
		return;
	qc = (qrdpContext*)instance->context;
	WINPR_UNUSED(qc);

	gdi_free(instance);
}

static BOOL qrdp_authenticate(freerdp* instance, char** username, char** password, char** domain)
{
	qrdpContext* qc = (qrdpContext*)instance->context;
	qrdp_t* q = qc ? qc->owner : NULL;
	if (!q)
		return FALSE;

	if (q->user)
		*username = _strdup(q->user);
	if (q->password)
		*password = _strdup(q->password);
	if (q->domain)
		*domain = _strdup(q->domain);
	return TRUE;
}

/* 自签证书一律接受（与应用内其它 RDP 客户端行为一致）。 */
static int qrdp_verify_certificate(freerdp* instance, const char* host, UINT16 port,
                                   const char* common_name, const char* subject,
                                   const char* issuer, const char* fingerprint, DWORD flags)
{
	WINPR_UNUSED(instance);
	WINPR_UNUSED(port);
	WINPR_UNUSED(common_name);
	WINPR_UNUSED(subject);
	WINPR_UNUSED(issuer);
	WINPR_UNUSED(fingerprint);
	WINPR_UNUSED(flags);
	WLog_INFO(TAG, "接受服务器证书: %s", host ? host : "?");
	return 1; /* 1 = 接受并继续 */
}

static int qrdp_logon_error_info(freerdp* instance, UINT32 data, UINT32 type)
{
	qrdpContext* qc = (qrdpContext*)instance->context;
	qrdp_t* q = qc ? qc->owner : NULL;
	const char* str_data = freerdp_get_logon_error_info_data(data);
	const char* str_type = freerdp_get_logon_error_info_type(type);
	char buf[512];

	WLog_ERR(TAG, "登录错误: %s [%s]", str_data ? str_data : "?", str_type ? str_type : "?");
	snprintf(buf, sizeof(buf), "登录失败：%s", str_type ? str_type : (str_data ? str_data : "未知"));
	qrdp_set_error(q, buf);
	return 1;
}

static BOOL qrdp_client_new(freerdp* instance, rdpContext* context)
{
	if (!instance || !context)
		return FALSE;

	instance->PreConnect = qrdp_pre_connect;
	instance->PostConnect = qrdp_post_connect;
	instance->PostDisconnect = qrdp_post_disconnect;
	instance->Authenticate = qrdp_authenticate;
	instance->VerifyCertificateEx = qrdp_verify_certificate;
	instance->VerifyChangedCertificateEx = qrdp_verify_certificate;
	instance->LogonErrorInfo = qrdp_logon_error_info;
	return TRUE;
}

static void qrdp_client_free(freerdp* instance, rdpContext* context)
{
	WINPR_UNUSED(instance);
	WINPR_UNUSED(context);
}

static int qrdp_client_start(rdpContext* context)
{
	WINPR_UNUSED(context);
	return 0;
}

static int qrdp_client_stop(rdpContext* context)
{
	WINPR_UNUSED(context);
	return 0;
}

static BOOL qrdp_context_new_cb(freerdp* instance, rdpContext* context)
{
	return qrdp_client_new(instance, context);
}

static void qrdp_context_free_cb(freerdp* instance, rdpContext* context)
{
	qrdp_client_free(instance, context);
}

/*
 * 按 freerdp_client_context_new 的内部步骤自己建上下文。
 * 官方封装在失败时不返回任何细节，这里逐步打日志，失败环节一目了然。
 */
static rdpContext* qrdp_context_create(RDP_CLIENT_ENTRY_POINTS* pp)
{
	freerdp* instance;
	rdpContext* context;

	if (!pp)
		return NULL;

	instance = freerdp_new();
	if (!instance)
	{
		QLOGE("freerdp_new 失败");
		return NULL;
	}

	instance->settings = pp->settings;
	instance->ContextSize = pp->ContextSize;
	instance->ContextNew = qrdp_context_new_cb;
	instance->ContextFree = qrdp_context_free_cb;

	instance->pClientEntryPoints = (RDP_CLIENT_ENTRY_POINTS*)malloc(pp->Size);
	if (!instance->pClientEntryPoints)
	{
		QLOGE("分配 entry points 失败 size=%u", (unsigned)pp->Size);
		freerdp_free(instance);
		return NULL;
	}
	CopyMemory(instance->pClientEntryPoints, pp, pp->Size);

	if (!freerdp_context_new(instance))
	{
		QLOGE("freerdp_context_new 失败 ctxSize=%u", (unsigned)pp->ContextSize);
		free(instance->pClientEntryPoints);
		freerdp_free(instance);
		return NULL;
	}
	QLOGI("freerdp_context_new 成功 ctx=%p settings=%p", (void*)instance->context,
	      (void*)instance->settings);

	if (freerdp_register_addin_provider(freerdp_channels_load_static_addin_entry, 0) != CHANNEL_RC_OK)
	{
		QLOGE("注册 addin provider 失败");
		freerdp_context_free(instance);
		free(instance->pClientEntryPoints);
		freerdp_free(instance);
		return NULL;
	}

	context = instance->context;
	context->instance = instance;
	context->settings = instance->settings;
	return context;
}

static int RdpClientEntry(RDP_CLIENT_ENTRY_POINTS* pEntryPoints)
{
	ZeroMemory(pEntryPoints, sizeof(RDP_CLIENT_ENTRY_POINTS));
	pEntryPoints->Version = RDP_CLIENT_INTERFACE_VERSION;
	pEntryPoints->Size = sizeof(RDP_CLIENT_ENTRY_POINTS_V1);
	pEntryPoints->ContextSize = sizeof(qrdpContext);
	pEntryPoints->ClientNew = qrdp_client_new;
	pEntryPoints->ClientFree = qrdp_client_free;
	pEntryPoints->ClientStart = qrdp_client_start;
	pEntryPoints->ClientStop = qrdp_client_stop;
	return 0;
}

/* ------------------------------------------------------------------ */
/* 连接线程                                                            */
/* ------------------------------------------------------------------ */

static void* qrdp_thread(void* arg)
{
	qrdp_t* q = (qrdp_t*)arg;
	HANDLE handles[64];

	JNIEnv* env = NULL;
	if ((*q->vm)->AttachCurrentThread(q->vm, &env, NULL) == JNI_OK)
	{
		q->env = env;
		q->jni_attached = 1;
		QLOGE("线程已 attach env=%p", (void*)env);
	}
	else
	{
		QLOGE("AttachCurrentThread 失败");
	}

	q->state = ST_CONNECTING;
	QLOGI("开始连接 %s", "");
	WLog_INFO(TAG, "开始连接...");

	if (!freerdp_connect(q->instance))
	{
		UINT32 code = freerdp_get_last_error(q->context);
		QLOGE("连接失败 0x%08X", code);
		WLog_ERR(TAG, "连接失败 0x%08X", code);
		pthread_mutex_lock(&q->lock);
		if (q->errmsg[0] == '\0')
		{
			pthread_mutex_unlock(&q->lock);
			qrdp_set_error_code(q, "连接失败", code);
		}
		else
		{
			pthread_mutex_unlock(&q->lock);
		}
		q->state = ST_ERROR;
		goto out;
	}

	q->state = ST_CONNECTED;
	QLOGI("已连接 桌面 %dx%d", q->width, q->height);
	WLog_INFO(TAG, "已连接，桌面 %dx%d", q->width, q->height);

	while (!q->stop && !freerdp_shall_disconnect(q->instance))
	{
		DWORD nCount = freerdp_get_event_handles(q->context, &handles[0], 64);
		if (nCount == 0)
		{
			WLog_ERR(TAG, "获取事件句柄失败");
			break;
		}

		DWORD status = WaitForMultipleObjects(nCount, handles, FALSE, 100);
		if (status == WAIT_FAILED)
			break;

		if (!freerdp_check_event_handles(q->context))
		{
			UINT32 code = freerdp_get_last_error(q->context);
			if (code != FREERDP_ERROR_SUCCESS)
				qrdp_set_error_code(q, "会话中断", code);
			break;
		}
	}

	freerdp_disconnect(q->instance);

out:
	if (q->jni_attached)
	{
		(*q->vm)->DetachCurrentThread(q->vm);
		q->jni_attached = 0;
		q->env = NULL;
	}
	if (q->state != ST_ERROR)
		q->state = ST_CLOSED;
	return NULL;
}

/* ------------------------------------------------------------------ */
/* JNI 接口                                                            */
/* ------------------------------------------------------------------ */

static void qrdp_destroy(qrdp_t* q)
{
	if (!q)
		return;

	if (q->threadStarted)
	{
		q->stop = 1;
		if (q->instance && q->state == ST_CONNECTING)
			freerdp_abort_connect(q->instance);
		pthread_join(q->thread, NULL);
		q->threadStarted = 0;
	}

	if (q->context)
		freerdp_client_context_free(q->context);

	JNIEnv* env = NULL;
	if (q->vm && q->bitmap &&
	    (*q->vm)->GetEnv(q->vm, (void**)&env, JNI_VERSION_1_6) == JNI_OK)
	{
		(*env)->DeleteGlobalRef(env, q->bitmap);
	}

	free(q->fb);
	free(q->user);
	free(q->domain);
	free(q->password);
	pthread_mutex_destroy(&q->lock);
	free(q);
}

static jstring qrdp_new_string(JNIEnv* env, const char* s)
{
	return (*env)->NewStringUTF(env, s ? s : "");
}

JNIEXPORT jlong JNICALL
Java_com_qiao_remotedesk_rdp_FreeRdpSession_nativeCreate(JNIEnv* env, jclass cls, jobject vmObj)
{
	WINPR_UNUSED(cls);
	qrdp_t* q = (qrdp_t*)calloc(1, sizeof(qrdp_t));
	if (!q)
		return 0;

	pthread_mutex_init(&q->lock, NULL);
	q->state = ST_INIT;
	(*env)->GetJavaVM(env, &q->vm);

	/*
	 * winpr 在 ANDROID 下的 console appender 直接调用 __android_log_print，
	 * 这里把根日志级别调到 DEBUG，FreeRDP 的协议日志就会出现在 logcat 里。
	 * 必须在任何 WLog_GetLog 之前设置（根 logger 只在第一次创建时读环境变量）。
	 */
	setenv("WLOG_LEVEL", "DEBUG", 0);

	/* 环境变量不一定生效（根 logger 可能已被创建），这里逐个打开关心的日志标签 */
	qrdp_enable_debug_logs();

	WINPR_UNUSED(vmObj);
	return (jlong)(intptr_t)q;
}

JNIEXPORT void JNICALL
Java_com_qiao_remotedesk_rdp_FreeRdpSession_nativeSetBitmap(JNIEnv* env, jclass cls, jlong h,
                                                            jobject bitmap)
{
	WINPR_UNUSED(cls);
	qrdp_t* q = (qrdp_t*)(intptr_t)h;
	if (!q)
		return;

	if (q->bitmap)
	{
		(*env)->DeleteGlobalRef(env, q->bitmap);
		q->bitmap = NULL;
	}
	q->attached = 0;

	if (!bitmap)
		return;

	AndroidBitmapInfo info;
	if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS)
		return;

	q->bitmap = (*env)->NewGlobalRef(env, bitmap);
	q->bmpWidth = (int)info.width;
	q->bmpHeight = (int)info.height;
	q->attached = 1;
	/* 触发一次全屏重绘 */
	q->frameSeq++;
}

/*
 * 建立上下文并完成参数配置。返回 0 表示成功，非 0 为失败。
 */
JNIEXPORT jint JNICALL
Java_com_qiao_remotedesk_rdp_FreeRdpSession_nativeInit(JNIEnv* env, jclass cls, jlong h,
                                                       jstring host, jint port, jstring user,
                                                       jstring domain, jstring password, jint width,
                                                       jint height, jint bpp, jint scale,
                                                       jint perf, jint security)
{
	WINPR_UNUSED(cls);
	qrdp_t* q = (qrdp_t*)(intptr_t)h;
	if (!q)
		return -1;

	const char* cHost = (*env)->GetStringUTFChars(env, host, NULL);
	const char* cUser = (*env)->GetStringUTFChars(env, user, NULL);
	const char* cDomain = (*env)->GetStringUTFChars(env, domain, NULL);
	const char* cPass = (*env)->GetStringUTFChars(env, password, NULL);

	q->user = _strdup(cUser ? cUser : "");
	q->domain = _strdup(cDomain ? cDomain : "");
	q->password = _strdup(cPass ? cPass : "");

	char target[320];
	char szW[16], szH[16], szBpp[16], szScale[24];
	snprintf(target, sizeof(target), "/v:%s:%d", cHost ? cHost : "", (int)port);
	snprintf(szW, sizeof(szW), "/w:%d", (int)width);
	snprintf(szH, sizeof(szH), "/h:%d", (int)height);
	snprintf(szBpp, sizeof(szBpp), "/bpp:%d", (int)bpp);
	snprintf(szScale, sizeof(szScale), "/scale-desktop:%d", (int)scale);

	/*
	 * security: 0 = 强制传统 RDP 加密（老服务器），其它 = 交给服务器协商。
	 * 不强推 NLA —— 有的服务器只允许 RDP 安全层，强推会直接回
	 * SSL_NOT_ALLOWED_BY_SERVER；协商模式会按服务器能力自动选择 NLA/TLS/RDP。
	 */
	const char* secOpt = (security == 0) ? "/sec:rdp" : NULL;

	char* argv[24];
	int argc = 0;
	argv[argc++] = (char*)"qrdp";
	argv[argc++] = target;
	if (secOpt)
		argv[argc++] = (char*)secOpt;
	argv[argc++] = (char*)"/cert:ignore";
	argv[argc++] = (char*)"/network:auto";
	argv[argc++] = (char*)"/audio-mode:2"; /* 不拉音频 */
	/* 剪贴板通道需要前端注入 custom 回调，未实现前禁用，
	 * 否则 cliprdr 能力协商失败(1359)会拖断整个会话 */
	argv[argc++] = szW;
	argv[argc++] = szH;
	argv[argc++] = szBpp;
	/* 100% 时不追加参数，保持与旧版完全一致的建链报文；>100% 才声明缩放。
	 * desktopScaleFactor 在建链期通过 GCC Client Core Data 发送，改档位必须重连。 */
	if (scale > 100 && scale <= 500)
		argv[argc++] = szScale;
	argv[argc++] = (char*)"/t:RemoteDeskQR";

	QLOGI("连接目标 %s:%d 用户 %s 域 %s 尺寸 %dx%d 缩放 %d%% 体验 0x%02x",
	      cHost ? cHost : "?", (int)port, cUser ? cUser : "?", cDomain ? cDomain : "?",
	      (int)width, (int)height, (int)scale, (unsigned)perf);

	RDP_CLIENT_ENTRY_POINTS entryPoints;
	RdpClientEntry(&entryPoints);

	QLOGI("entry: size=%u ctxSize=%u version=%u",
	      (unsigned)entryPoints.Size, (unsigned)entryPoints.ContextSize,
	      (unsigned)entryPoints.Version);

	rdpContext* context = qrdp_context_create(&entryPoints);
	if (!context)
	{
		QLOGE("创建 RDP 上下文失败");
		qrdp_set_error(q, "创建 RDP 上下文失败");
		goto fail;
	}

	q->context = context;
	q->instance = context->instance;
	((qrdpContext*)context)->owner = q;

	DWORD status = freerdp_client_settings_parse_command_line(context->settings, argc, argv, FALSE);
	if (status)
	{
		freerdp_client_settings_command_line_status_print(context->settings, status, argc, argv);
		qrdp_set_error(q, "解析连接参数失败");
		goto fail;
	}

	/* 凭据直接写进 settings，避免控制台交互 */
	if (context->settings)
	{
		rdpSettings* s = context->settings;
		/*
		 * 「高级 → 桌面体验」六项：配置页保存的布尔值此前从未下发到 FreeRDP，
		 * 所以取消「显示窗口拖动内容」等开关在远端完全没有效果。
		 * FreeRDP 写 Client Info PDU 时会由这六个字段重算 PerformanceFlags，
		 * 因此必须设置字段本身（而不只是 flags 位）。
		 */
		s->DisableWallpaper = !(perf & 0x01);
		s->AllowFontSmoothing = (perf & 0x02) != 0;
		s->AllowDesktopComposition = (perf & 0x04) != 0;
		s->DisableFullWindowDrag = !(perf & 0x08);
		s->DisableMenuAnims = !(perf & 0x10);
		s->DisableThemes = !(perf & 0x20);
		freerdp_performance_flags_make(s);
		/* 启用 MS-RDPEDISP：会话内通过 Monitor Layout PDU 调整远端 DPI 缩放 */
		s->SupportDisplayControl = TRUE;
		s->SupportMonitorLayoutPdu = TRUE;
		q->scale = (int)scale;
		q->disp = NULL;
		q->dispSent = 0;
		if (q->user && q->user[0])
		{
			freerdp_settings_set_string(s, FreeRDP_Username, q->user);
			s->Username[0] = '\0';
			freerdp_settings_set_string(s, FreeRDP_Username, q->user);
		}
		if (q->domain)
			freerdp_settings_set_string(s, FreeRDP_Domain, q->domain);
		if (q->password)
			freerdp_settings_set_string(s, FreeRDP_Password, q->password);
	}

	/*
	 * 关掉 GFX 管线：H.264 解码需要额外依赖，而本项目只需要基础位图通道。
	 * 关掉桌面缩放：应用以固定分辨率渲染，尺寸变化时由上层重连。
	 */
	{
		rdpSettings* s = context->settings;
		s->SupportGraphicsPipeline = FALSE;
		s->GfxH264 = FALSE;
		s->GfxAVC444 = FALSE;
		s->DesktopResize = FALSE;
		s->RedirectClipboard = FALSE;
		s->FastPathInput = TRUE;
		s->FastPathOutput = TRUE;
		s->NetworkAutoDetect = 1;
		s->ConnectionType = CONNECTION_TYPE_AUTODETECT;
	}
	QLOGE("nativeInit 后 RedirectClipboard=%d", (int)context->settings->RedirectClipboard);

	(*env)->ReleaseStringUTFChars(env, host, cHost);
	(*env)->ReleaseStringUTFChars(env, user, cUser);
	(*env)->ReleaseStringUTFChars(env, domain, cDomain);
	(*env)->ReleaseStringUTFChars(env, password, cPass);
	return 0;

fail:
	if (cHost)
		(*env)->ReleaseStringUTFChars(env, host, cHost);
	if (cUser)
		(*env)->ReleaseStringUTFChars(env, user, cUser);
	if (cDomain)
		(*env)->ReleaseStringUTFChars(env, domain, cDomain);
	if (cPass)
		(*env)->ReleaseStringUTFChars(env, password, cPass);
	return -2;
}

JNIEXPORT jint JNICALL
Java_com_qiao_remotedesk_rdp_FreeRdpSession_nativeStart(JNIEnv* env, jclass cls, jlong h)
{
	WINPR_UNUSED(env);
	WINPR_UNUSED(cls);
	qrdp_t* q = (qrdp_t*)(intptr_t)h;
	if (!q || !q->context)
		return -1;

	if (q->threadStarted)
		return 0;

	q->stop = 0;
	if (pthread_create(&q->thread, NULL, qrdp_thread, q) != 0)
	{
		qrdp_set_error(q, "创建连接线程失败");
		q->state = ST_ERROR;
		return -2;
	}
	q->threadStarted = 1;
	return 0;
}

JNIEXPORT jint JNICALL
Java_com_qiao_remotedesk_rdp_FreeRdpSession_nativeState(JNIEnv* env, jclass cls, jlong h)
{
	WINPR_UNUSED(env);
	WINPR_UNUSED(cls);
	qrdp_t* q = (qrdp_t*)(intptr_t)h;
	return q ? q->state : ST_ERROR;
}

JNIEXPORT jint JNICALL
Java_com_qiao_remotedesk_rdp_FreeRdpSession_nativeFrameSeq(JNIEnv* env, jclass cls, jlong h)
{
	WINPR_UNUSED(env);
	WINPR_UNUSED(cls);
	qrdp_t* q = (qrdp_t*)(intptr_t)h;
	return q ? q->frameSeq : 0;
}

JNIEXPORT jint JNICALL
Java_com_qiao_remotedesk_rdp_FreeRdpSession_nativeDesktopWidth(JNIEnv* env, jclass cls, jlong h)
{
	WINPR_UNUSED(env);
	WINPR_UNUSED(cls);
	qrdp_t* q = (qrdp_t*)(intptr_t)h;
	return q ? q->width : 0;
}

JNIEXPORT jint JNICALL
Java_com_qiao_remotedesk_rdp_FreeRdpSession_nativeDesktopHeight(JNIEnv* env, jclass cls, jlong h)
{
	WINPR_UNUSED(env);
	WINPR_UNUSED(cls);
	qrdp_t* q = (qrdp_t*)(intptr_t)h;
	return q ? q->height : 0;
}

JNIEXPORT jstring JNICALL
Java_com_qiao_remotedesk_rdp_FreeRdpSession_nativeLastError(JNIEnv* env, jclass cls, jlong h)
{
	WINPR_UNUSED(cls);
	qrdp_t* q = (qrdp_t*)(intptr_t)h;
	if (!q)
		return qrdp_new_string(env, "");
	pthread_mutex_lock(&q->lock);
	char buf[512];
	snprintf(buf, sizeof(buf), "%s", q->errmsg);
	pthread_mutex_unlock(&q->lock);
	return qrdp_new_string(env, buf);
}

JNIEXPORT void JNICALL
Java_com_qiao_remotedesk_rdp_FreeRdpSession_nativeDestroy(JNIEnv* env, jclass cls, jlong h)
{
	WINPR_UNUSED(env);
	WINPR_UNUSED(cls);
	qrdp_t* q = (qrdp_t*)(intptr_t)h;
	qrdp_destroy(q);
}

/*
 * Android 应用进程默认没有 HOME / TMPDIR，而 FreeRDP 初始化时要靠它们推导
 * HOME、配置与缓存目录，取不到就直接失败（freerdp_settings_new 返回 NULL）。
 * 这里把应用私有目录写进环境变量，必须在建上下文之前调用。
 */
JNIEXPORT void JNICALL
Java_com_qiao_remotedesk_rdp_FreeRdpSession_nativeSetWorkDir(JNIEnv* env, jclass cls, jstring dir)
{
	WINPR_UNUSED(cls);
	if (!dir)
		return;

	const char* d = (*env)->GetStringUTFChars(env, dir, NULL);
	if (!d)
		return;

	setenv("HOME", d, 1);
	setenv("TMPDIR", d, 1);
	setenv("XDG_CONFIG_HOME", d, 1);
	setenv("XDG_DATA_HOME", d, 1);

	char cache[512];
	snprintf(cache, sizeof(cache), "%s/cache", d);
	setenv("XDG_CACHE_HOME", cache, 1);

	QLOGI("工作目录: %s", d);
	(*env)->ReleaseStringUTFChars(env, dir, d);
}

/* ------------------------------------------------------------------ */
/* 输入                                                               */
/* ------------------------------------------------------------------ */

JNIEXPORT void JNICALL
Java_com_qiao_remotedesk_rdp_FreeRdpSession_nativeMouse(JNIEnv* env, jclass cls, jlong h, jint x,
                                                        jint y, jint flags)
{
	WINPR_UNUSED(env);
	WINPR_UNUSED(cls);
	qrdp_t* q = (qrdp_t*)(intptr_t)h;
	if (!q || !q->instance || !q->instance->input || q->state != ST_CONNECTED)
	{
		QLOGE("mouse 忽略 q=%p state=%d", (void*)q, q ? q->state : -1);
		return;
	}
	BOOL ok = freerdp_input_send_mouse_event(q->instance->input, (UINT16)flags, (UINT16)x, (UINT16)y);
	/* 只记按钮事件，纯 MOVE 每帧都有，打日志会刷屏。 */
	if (flags & (PTR_FLAGS_DOWN | PTR_FLAGS_BUTTON1 | PTR_FLAGS_BUTTON2 | PTR_FLAGS_BUTTON3))
	{
		static int n = 0;
		if (n++ < 40)
			QLOGE("mouse flags=0x%04X x=%d y=%d ok=%d", flags, x, y, ok);
	}
}

JNIEXPORT void JNICALL
Java_com_qiao_remotedesk_rdp_FreeRdpSession_nativeWheel(JNIEnv* env, jclass cls, jlong h, jint x,
                                                        jint y, jint delta)
{
	WINPR_UNUSED(env);
	WINPR_UNUSED(cls);
	qrdp_t* q = (qrdp_t*)(intptr_t)h;
	if (!q || !q->instance || !q->instance->input || q->state != ST_CONNECTED)
		return;

	/* 滚轮必须走普通鼠标事件；extended 仅供 X 键，发 wheel 会被
	 * Windows 判为非法鼠标 PDU（ERRINFO_INVALID_INPUT_PDU_MOUSE）并断线。
	 * 按官方 X11 客户端写法：wheel 事件不带 PTR_FLAGS_MOVE、坐标传 0，
	 * 服务端在当前鼠标位置滚动；旋转量 0x78 = 120 = 一个刻度；
	 * delta>0（手指上滑）= 滚轮朝用户转 = PTR_FLAGS_WHEEL_NEGATIVE。 */
	UINT16 flags = PTR_FLAGS_WHEEL | 0x78;
	if (delta > 0)
		flags |= PTR_FLAGS_WHEEL_NEGATIVE;
	freerdp_input_send_mouse_event(q->instance->input, flags, 0, 0);
}

JNIEXPORT void JNICALL
Java_com_qiao_remotedesk_rdp_FreeRdpSession_nativeKey(JNIEnv* env, jclass cls, jlong h,
                                                      jint scancode, jboolean extended,
                                                      jboolean down)
{
	WINPR_UNUSED(env);
	WINPR_UNUSED(cls);
	qrdp_t* q = (qrdp_t*)(intptr_t)h;
	if (!q || !q->instance || !q->instance->input || q->state != ST_CONNECTED)
		return;

	UINT16 flags = 0;
	if (extended)
		flags |= KBD_FLAGS_EXTENDED;
	if (down)
		flags |= KBD_FLAGS_DOWN;
	else
		flags |= KBD_FLAGS_RELEASE;
	freerdp_input_send_keyboard_event(q->instance->input, flags, (UINT16)scancode);
}

JNIEXPORT void JNICALL
Java_com_qiao_remotedesk_rdp_FreeRdpSession_nativeUnicode(JNIEnv* env, jclass cls, jlong h,
                                                          jint codeUnit, jboolean down)
{
	WINPR_UNUSED(env);
	WINPR_UNUSED(cls);
	qrdp_t* q = (qrdp_t*)(intptr_t)h;
	if (!q || !q->instance || !q->instance->input || q->state != ST_CONNECTED)
		return;

	UINT16 flags = down ? KBD_FLAGS_DOWN : KBD_FLAGS_RELEASE;
	freerdp_input_send_unicode_keyboard_event(q->instance->input, flags, (UINT16)codeUnit);
}

/*
 * 注意：这里不要定义 JNI_OnLoad —— WinPR 自带一个（winpr/timezone/timezone.c），
 * 两个同时存在会让链接器报重复符号。WinPR 的版本已经返回 JNI_VERSION_1_6。
 */
