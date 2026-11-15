package com.qiao.remotedesk.net;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/**
 * 隧道前台服务。只依赖系统图标与系统 API，不引用任何 UI 类或自定义资源 id。
 * 渠道 id 固定为 remotedesk_tunnel，权限与 service 声明由 AndroidManifest 提供。
 */
public class TunnelService extends Service {

    private static final String CHANNEL_ID = "remotedesk_tunnel";
    private static final int NOTIFICATION_ID = 1;

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startAsForeground();
        } catch (Throwable ignored) {
            // 缺少前台服务权限等情况下不让服务崩溃
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        SessionManager.get().stopAll();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "远程桌面隧道", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
        }
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("远程桌面隧道")
                .setContentText("隧道服务正在运行")
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, notification);
    }
}
