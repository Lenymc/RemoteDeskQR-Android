package com.qiao.remotedesk;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import com.qiao.remotedesk.rdp.RdpSession;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 设备端 RDP 探针：用真实服务器跑完整协议流程，把阶段日志打到 logcat，
 * 并在收到若干帧后把画面存成 PNG（存到应用外部目录，便于 adb 直接取回）。
 * 触发方式：am start -n com.qiao.remotedesk/.MainActivity --es rdpprobe <host>
 * 可选参数：--es probeUser <user> --es probePassword <password> --es probeDomain <domain>
 */
public final class RdpProbe {

    private static final String TAG = "RD";

    private RdpProbe() {
    }

    public static void start(final Context ctx, final String host, final int port,
                             final String user, final String password, final String domain) {
        final File dir = ctx.getExternalFilesDir(null) != null
                ? ctx.getExternalFilesDir(null) : ctx.getFilesDir();
        Log.e(TAG, "== RDP 探针开始 " + host + ":" + port + " 用户=" + user + " 域=" + (domain == null || domain.isEmpty() ? "(空)" : domain));
        final RdpSession s = new RdpSession(null, host, port, domain, user, password, 1280, 800);
        s.setListener(new RdpSession.Listener() {
            private int frames;
            private boolean saved;

            @Override
            public void onConnected(int w, int h) {
                Log.e(TAG, "onConnected " + w + "x" + h);
            }

            @Override
            public void onFramebufferUpdated(int left, int top, int right, int bottom) {
                frames++;
                if (frames <= 5 || frames % 20 == 0) {
                    Log.e(TAG, "帧#" + frames + " 区域=(" + left + "," + top + ")-(" + right + "," + bottom + ")");
                }
                if (!saved && frames >= 6) {
                    saved = true;
                    save(dir, s);
                }
            }

            @Override
            public void onPointerUpdated(int x, int y) {
            }

            @Override
            public void onLog(String message) {
                Log.e(TAG, "[rdp] " + message);
            }

            @Override
            public void onError(String message) {
                Log.e(TAG, "[rdp错误] " + message);
            }

            @Override
            public void onClosed() {
                Log.e(TAG, "[rdp] 已关闭，共 " + frames + " 帧");
            }
        });
        s.start();
    }

    private static void save(File dir, RdpSession s) {
        try {
            int w = s.width();
            int h = s.height();
            int[] px = s.pixels();
            if (w <= 0 || h <= 0 || px == null || px.length < w * h) {
                Log.e(TAG, "保存失败：帧缓冲无效 " + w + "x" + h);
                return;
            }
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.setPixels(px, 0, w, 0, 0, w, h);
            File f = new File(dir, "rdp-frame.png");
            FileOutputStream fo = new FileOutputStream(f);
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fo);
            fo.close();
            bmp.recycle();
            Log.e(TAG, "已保存首帧 " + f.getAbsolutePath() + " (" + w + "x" + h + ")");
        } catch (Throwable t) {
            Log.e(TAG, "保存首帧异常 " + t);
        }
    }
}
