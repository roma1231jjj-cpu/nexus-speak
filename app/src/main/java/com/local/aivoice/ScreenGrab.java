package com.local.aivoice;

import android.app.Activity;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Base64;
import android.view.Display;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Screen capture via MediaProjection: consent once, then captures on demand. */
public final class ScreenGrab {

    public static final class ProjectionRequestActivity extends Activity {
        @Override
        protected void onStart() {
            super.onStart();
            MediaProjectionManager mpm =
                    getSystemService(MediaProjectionManager.class);
            startActivityForResult(mpm.createScreenCaptureIntent(), 7);
        }

        @Override
        protected void onActivityResult(int requestCode, int resultCode, Intent data) {
            if (requestCode == 7) {
                if (resultCode == RESULT_OK && data != null) {
                    Intent svc = new Intent(this, ScreenGrabService.class)
                            .putExtra("code", resultCode)
                            .putExtra("data", data);
                    startService(svc);
                    // latch counted down by ScreenGrabService.onStartCommand
                } else {
                    denied = true;
                    CountDownLatch l = pendingLatch;
                    if (l != null) l.countDown();
                }
            }
            finish();
        }
    }

    public static volatile boolean denied = false;
    public static volatile CountDownLatch pendingLatch = null;

    /** Called from background thread. Returns JPEG data-URL of a fresh screenshot, or null. */
    public static String capture(Context ctx) {
        denied = false;
        // На Android 14 один MediaProjection нельзя использовать для повторного
        // createVirtualDisplay — спрашиваем разрешение заново.
        boolean haveProjection = ScreenGrabService.projection != null
                && android.os.Build.VERSION.SDK_INT < 34;
        if (!haveProjection) {
            final CountDownLatch latch = new CountDownLatch(1);
            pendingLatch = latch;
            ctx.startActivity(new Intent(ctx, ProjectionRequestActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try {
                latch.await(45, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        } else {
            // brief settle so screen shows the actual content, not our overlay
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        }
        Bitmap bmp = ScreenGrabService.take(ctx);
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            // на 14 повторно использовать проекцию нельзя — гасим сервис,
            // иначе висит уведомление «может снимать экран»
            ScreenGrabService.projection = null;
            try { ctx.stopService(new Intent(ctx, ScreenGrabService.class)); } catch (Throwable ignored) {}
        }
        if (bmp == null) return null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 60, bos);
        byte[] bytes = bos.toByteArray();
        return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    public static class ScreenGrabService extends Service {
        static volatile MediaProjection projection;
        private static volatile ImageReader reader;
        private static HandlerThread thread;

        @Override
        public IBinder onBind(Intent i) { return null; }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            int code = intent.getIntExtra("code", -1);
            Intent data;
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                data = intent.getParcelableExtra("data", Intent.class);
            } else {
                data = intent.getParcelableExtra("data");
            }
            if (!startForegroundSafe()) {
                CountDownLatch l0 = pendingLatch;
                pendingLatch = null;
                if (l0 != null) l0.countDown();
                stopSelf();
                return START_NOT_STICKY;
            }

            MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
            MediaProjection p = mpm.getMediaProjection(code, data);
            if (p == null) {
                CountDownLatch l0 = pendingLatch;
                pendingLatch = null;
                if (l0 != null) l0.countDown();
                stopSelf();
                return START_NOT_STICKY;
            }
            projection = p;
            p.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    if (projection == p) projection = null;
                    releaseReader();
                }
            }, new Handler(getThread().getLooper()));
            CountDownLatch l = pendingLatch;
            pendingLatch = null;
            if (l != null) l.countDown();
            return START_STICKY;
        }

        /** На Android 14 без типа mediaProjection сервис падает */
        private boolean startForegroundSafe() {
            try {
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    startForeground(43, notif(),
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
                } else {
                    startForeground(43, notif());
                }
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        static HandlerThread getThread() {
            synchronized (ScreenGrab.class) {
                if (thread == null || !thread.isAlive()) {
                    thread = new HandlerThread("screengrab");
                    thread.start();
                }
                return thread;
            }
        }

        static void releaseReader() {
            if (reader != null) { try { reader.close(); } catch (Exception ignored) {} reader = null; }
        }

        static Bitmap take(Context ctx) {
            MediaProjection p = projection;
            if (p == null) return null;
            try {
                WindowManager wm = ctx.getSystemService(WindowManager.class);
                Display d = wm.getDefaultDisplay();
                android.util.DisplayMetrics sz = new android.util.DisplayMetrics();
                d.getRealMetrics(sz);
                int w = sz.widthPixels, hpx = sz.heightPixels;
                ImageReader r = ImageReader.newInstance(w, hpx, PixelFormat.RGBA_8888, 2);
                reader = r;
                android.hardware.display.VirtualDisplay vd = p.createVirtualDisplay("nexus",
                        w, hpx, sz.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.getSurface(), null,
                        new Handler(getThread().getLooper()));
                final Bitmap[] out = new Bitmap[1];
                CountDownLatch latch = new CountDownLatch(1);
                r.setOnImageAvailableListener(reader1 -> {
                    Image img = null;
                    try {
                        img = reader1.acquireLatestImage();
                        if (img != null) {
                            android.media.Image.Plane plane = img.getPlanes()[0];
                            java.nio.ByteBuffer buf = plane.getBuffer();
                            int pixelStride = plane.getPixelStride();
                            int rowStride = plane.getRowStride();
                            int rowPadding = rowStride - pixelStride * reader1.getWidth();
                            Bitmap full = Bitmap.createBitmap(reader1.getWidth() + rowPadding / pixelStride,
                                    reader1.getHeight(), Bitmap.Config.ARGB_8888);
                            full.copyPixelsFromBuffer(buf);
                            out[0] = Bitmap.createBitmap(full, 0, 0, reader1.getWidth(),
                                    reader1.getHeight());
                        }
                    } catch (Exception ignored) {
                    } finally {
                        if (img != null) img.close();
                        latch.countDown();
                    }
                }, new Handler(getThread().getLooper()));
                latch.await(5, TimeUnit.SECONDS);
                try { vd.release(); } catch (Exception ignored) {}
                releaseReader();
                return out[0];
            } catch (Exception e) {
                return null;
            }
        }

        private android.app.Notification notif() {
            android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
            if (nm.getNotificationChannel("assistant") == null) {
                nm.createNotificationChannel(new android.app.NotificationChannel(
                        "assistant", L.s("Нексус"), android.app.NotificationManager.IMPORTANCE_LOW));
            }
            return new android.app.Notification.Builder(this, "assistant")
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setContentTitle(L.s("Нексус может снимать экран"))
                    .build();
        }
    }

    private ScreenGrab() {}
}
