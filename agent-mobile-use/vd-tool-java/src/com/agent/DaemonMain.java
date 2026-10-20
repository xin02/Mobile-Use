package com.agent;

import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Display;
import android.view.Surface;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;

public class DaemonMain {
    private static final String STATUS_FILE = "/data/local/tmp/agent/vd_status.json";
    private static final String STOP_SIGNAL = "/data/local/tmp/agent/vd_stop";

    private static int sWidth = 1080;
    private static int sHeight = 2400;
    private static int sDpi = 420;

    public static void main(String[] args) {
        if (args.length >= 3) {
            try {
                sWidth = Integer.parseInt(args[0]);
                sHeight = Integer.parseInt(args[1]);
                sDpi = Integer.parseInt(args[2]);
            } catch (Exception e) {
                System.err.println("[AgentDaemon] Failed to parse display args: " + e.getMessage());
            }
        }

        System.out.println("[AgentDaemon] [mi9-3] Starting virtual display: " + sWidth + "x" + sHeight + " @ " + sDpi + " DPI");

        // Claim 3071/3072 before dropping privileges: a non-root uid cannot socket()/bind()
        // in this domain on Android 9 (EACCES), while accept/write on a pre-bound socket
        // keeps working afterwards.
        boolean helperOnly = "1".equals(System.getenv("VD_NO_DISPLAY"));
        if (helperOnly) {
            if (!preBindCtrl()) {
                writeStatus("failed", -1);
                System.exit(1);
            }
        } else if (!preBindServers()) {
            writeStatus("failed", -1);
            System.exit(1);
        }

        // Android 9's DisplayManagerService validates that the caller's uid actually owns
        // the package name it passes ("packageName must match the calling uid"). A root
        // (uid 0) process owns no package, and the system Context we obtain below reports
        // package "android", so creation is refused with a SecurityException.
        //
        // Dropping to uid/gid 1000 (system, package "android") satisfies that check and
        // still carries every signature permission the daemon needs. opt-out via VD_UID=0.
        try {
            String uidEnv = System.getenv("VD_UID");
            int targetUid = (uidEnv != null && uidEnv.length() > 0) ? Integer.parseInt(uidEnv) : 1000;
            if (targetUid > 0) {
                Class<?> procCls = Class.forName("android.os.Process");
                procCls.getMethod("setGid", int.class).invoke(null, targetUid);
                procCls.getMethod("setUid", int.class).invoke(null, targetUid);
                System.out.println("[AgentDaemon] Dropped to uid=" + procCls.getMethod("myUid").invoke(null));
            }
        } catch (Throwable uidErr) {
            System.err.println("[AgentDaemon] uid drop failed: " + uidErr);
        }

        try {
            // MIUI (Android 9) initialises miui.contentcatcher.InterceptorProxy inside
            // ActivityThread.attach(). That initialiser builds a Handler, and on MIUI 10
            // it can run without a usable main Looper, killing the whole daemon with an
            // NPE in Handler.<init> before any display work happens.
            //
            // Two-step defence:
            //  1) make sure the current thread owns the process-wide main Looper;
            //  2) touch InterceptorProxy *here*, on this thread, so its static
            //     initialiser completes under our main Looper instead of MIUI's path.
            try {
                Class<?> looperCls = Class.forName("android.os.Looper");
                Object my = looperCls.getMethod("myLooper").invoke(null);
                Object main = looperCls.getMethod("getMainLooper").invoke(null);
                if (main == null) {
                    if (my == null) {
                        looperCls.getMethod("prepareMainLooper").invoke(null);
                        System.out.println("[AgentDaemon] prepareMainLooper() done");
                    } else {
                        looperCls.getMethod("prepareMainLooper").invoke(null);
                        System.out.println("[AgentDaemon] WARN: looper existed, prepareMainLooper attempted");
                    }
                }
                System.out.println("[AgentDaemon] looper my=" + looperCls.getMethod("myLooper").invoke(null)
                        + " main=" + looperCls.getMethod("getMainLooper").invoke(null)
                        + " thread=" + Thread.currentThread().getName());
            } catch (Throwable looperErr) {
                System.err.println("[AgentDaemon] Looper bootstrap failed: " + looperErr);
            }

            // Pre-warm MIUI's interceptor on the main thread (no-op on AOSP).
            try {
                Class.forName("miui.contentcatcher.InterceptorProxy");
                System.out.println("[AgentDaemon] MIUI InterceptorProxy pre-initialised");
            } catch (Throwable ignored) {
                System.out.println("[AgentDaemon] MIUI InterceptorProxy pre-warm skipped: " + ignored);
            }
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method systemMain = atClass.getMethod("systemMain");
            Object at = systemMain.invoke(null);
            Method getSysCtx = atClass.getMethod("getSystemContext");
            android.content.Context ctx = (android.content.Context) getSysCtx.invoke(at);
            sSystemContext = ctx;

            // Helper-only mode: no virtual display, just the control socket (clipboard /
            // input injection). Used by the Android 9 gateway, which drives the physical
            // display and only needs a privileged process for clipboard writes.
            if ("1".equals(System.getenv("VD_NO_DISPLAY"))) {
                System.out.println("[AgentDaemon] helper-only mode (no virtual display)");
                startCtrlServer(null);
                File stopFile2 = new File(STOP_SIGNAL);
                if (stopFile2.exists()) stopFile2.delete();
                while (!stopFile2.exists()) {
                    try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                }
                System.out.println("[AgentDaemon] helper stop signal; exiting");
                System.exit(0);
            }

            Class<?> dmClass = Class.forName("android.hardware.display.DisplayManager");
            java.lang.reflect.Constructor<?> dmCtor = dmClass.getDeclaredConstructor(android.content.Context.class);
            dmCtor.setAccessible(true);
            DisplayManager dm = (DisplayManager) dmCtor.newInstance(ctx);

            try {
                java.lang.reflect.Field mirrorField = dmClass.getDeclaredField("mDisplayIdToMirror");
                mirrorField.setAccessible(true);
                mirrorField.setInt(dm, 0);
            } catch (Throwable ignored) {}

            java.lang.reflect.Field serviceField = dmClass.getDeclaredField("mGlobal");
            serviceField.setAccessible(true);

            HandlerThread drainThread = new HandlerThread("ImageReaderDrainer");
            drainThread.start();
            Handler drainHandler = new Handler(drainThread.getLooper());

            ImageReader reader = ImageReader.newInstance(sWidth, sHeight, PixelFormat.RGBA_8888, 2);
            sReader = reader;
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader r) {
                    Image img = null;
                    try {
                        img = r.acquireLatestImage();
                        // Keep a throttled copy of the newest frame. A static screen stops
                        // producing frames, so a screenshot taken on demand would otherwise
                        // wait forever; the cache makes capture independent of UI changes.
                        if (img != null) {
                            long now = android.os.SystemClock.uptimeMillis();
                            if (now - sLastFrameTime >= FRAME_CACHE_INTERVAL_MS) {
                                sLastFrameTime = now;
                                cacheFrame(img);
                            }
                        }
                        if (img != null && sCaptureRequested) {
                            try {
                                String path = sCapturePath;
                                if (path != null) {
                                    writePngFromCache(path);
                                    sCaptureResult = "OK " + path;
                                } else {
                                    sCaptureResult = "ERR no_path";
                                }
                            } catch (Throwable capErr) {
                                sCaptureResult = "ERR " + capErr;
                            } finally {
                                sCaptureRequested = false;
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        if (img != null) {
                            try { img.close(); } catch (Throwable ignored) {}
                        }
                    }
                }
            }, drainHandler);

            // 0x14609 = FLAG_PUBLIC (1) | FLAG_OWN_CONTENT_ONLY (8) | FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS (512) |
            //           FLAG_TRUSTED (1024) | VIRTUAL_DISPLAY_FLAG_OWN_FOCUS (16384) |
            //           VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED (65536)
            //
            // Android 9 (API 28) only understands FLAG_PUBLIC | FLAG_OWN_CONTENT_ONLY
            // (PRESENTATION/SECURE/AUTO_MIRROR are alternatives we must NOT set).
            // SHOULD_SHOW_SYSTEM_DECORATIONS is API 29+, TRUSTED is API 34+,
            // OWN_FOCUS/STEAL_TOP_FOCUS_DISABLED are hidden Q+ flags; passing unknown
            // bits to the P implementation is undefined, so branch on SDK_INT.
            int sdkInt = android.os.Build.VERSION.SDK_INT;
            int flags;
            if (sdkInt < 29) {
                flags = 1 | 8; // PUBLIC | OWN_CONTENT_ONLY
            } else {
                flags = 1545 | 16384 | 65536;
            }
            // Diagnostic override: VD_FLAGS=0x41 etc. lets us try hidden flags such as
            // VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH (1<<6) without a rebuild.
            String flagsEnv = System.getenv("VD_FLAGS");
            if (flagsEnv != null && flagsEnv.length() > 0) {
                try {
                    flags = (int) Long.parseLong(flagsEnv.replace("0x", ""), flagsEnv.startsWith("0x") ? 16 : 10);
                } catch (Throwable ignored) {}
            }
            System.out.println("[AgentDaemon] SDK_INT=" + sdkInt + " display flags=0x" + Integer.toHexString(flags));
            VirtualDisplay vd = null;

            try {
                Class<?> cBuilder = Class.forName("android.hardware.display.VirtualDisplayConfig$Builder");
                java.lang.reflect.Constructor<?> ctor = cBuilder.getConstructor(String.class, int.class, int.class, int.class);
                Object builder = ctor.newInstance("AgentVirtualDisplay", sWidth, sHeight, sDpi);
                cBuilder.getMethod("setSurface", Class.forName("android.view.Surface")).invoke(builder, reader.getSurface());
                cBuilder.getMethod("setFlags", int.class).invoke(builder, flags);

                Object config = cBuilder.getMethod("build").invoke(builder);
                Method mCreateVD = dm.getClass().getMethod("createVirtualDisplay", Class.forName("android.hardware.display.VirtualDisplayConfig"));
                vd = (VirtualDisplay) mCreateVD.invoke(dm, config);
            } catch (Throwable t) {
                try {
                    vd = dm.createVirtualDisplay("AgentVirtualDisplay", sWidth, sHeight, sDpi, reader.getSurface(), flags);
                } catch (Throwable fallbackErr) {
                    System.err.println("[AgentDaemon] Virtual display creation failed: " + fallbackErr);
                }
            }

            if (vd == null || vd.getDisplay() == null) {
                System.err.println("[AgentDaemon] Virtual display creation failed; not reporting a running screen.");
                writeStatus("failed", -1);
                System.exit(1);
                return;
            }

            Display display = vd.getDisplay();
            int displayId = display.getDisplayId();
            System.out.println("[AgentDaemon] Virtual Display created successfully! ID: " + displayId);

            // Optional: set IME policy to local virtual display (0 = DISPLAY_IME_POLICY_LOCAL)
            try {
                Class<?> wmClass = Class.forName("android.view.WindowManagerGlobal");
                Method getWmService = wmClass.getMethod("getWindowManagerService");
                Object wmService = getWmService.invoke(null);
                Method setImePolicy = wmService.getClass().getMethod("setDisplayImePolicy", int.class, int.class);
                setImePolicy.invoke(wmService, displayId, 0);
                System.out.println("[AgentDaemon] Set Display " + displayId + " IME policy to LOCAL (0)");
            } catch (Throwable t) {
                System.err.println("[AgentDaemon] Warning: Failed to set IME policy: " + t.getMessage());
            }

            // Bind the stream server BEFORE publishing "running". The web console treats
            // status=="running" as "the video pipeline is up", so publishing it first made
            // clients race the 127.0.0.1:3071 listener and land on a black screen. Binding
            // first removes that window entirely.
            if (!startStreamServer(vd, reader)) {
                System.err.println("[AgentDaemon] Failed to bind stream server on 127.0.0.1:3071; not reporting a running screen.");
                writeStatus("failed", -1);
                System.exit(1);
                return;
            }
            System.out.println("[AgentDaemon] Stream server ready on 127.0.0.1:3071");

            startCtrlServer(vd);

            // Publish status only after the encoder pipeline can accept clients.
            int pid = android.os.Process.myPid();
            writeStatus("running", displayId, pid, sWidth, sHeight, sDpi);

            File stopFile = new File(STOP_SIGNAL);
            if (stopFile.exists()) stopFile.delete();

            // Loop checking stop signal
            while (!stopFile.exists()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    break;
                }
            }

            System.out.println("[AgentDaemon] Stop signal detected. Cleaning up...");
            stopStreamServer();
            vd.release();
            reader.close();
            drainThread.quitSafely();
            new File(STATUS_FILE).delete();
            System.out.println("[AgentDaemon] Daemon safely terminated.");
            System.exit(0);

        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static void writeStatus(String status, int displayId) {
        writeStatus(status, displayId, android.os.Process.myPid(), sWidth, sHeight, sDpi);
    }

    private static void writeStatus(String status, int displayId, int pid, int w, int h, int dpi) {
        try {
            String json = String.format("{\"status\":\"%s\",\"pid\":%d,\"display_id\":%d,\"width\":%d,\"height\":%d,\"dpi\":%d}\n",
                    status, pid, displayId, w, h, dpi);
            FileOutputStream fos = new FileOutputStream(STATUS_FILE);
            fos.write(json.getBytes("UTF-8"));
            fos.flush();
            fos.close();
        } catch (Exception e) {
            System.err.println("[AgentDaemon] Failed to write status file: " + e.getMessage());
        }
    }

    /** System context captured at boot; used by the clipboard command. */
    private static volatile android.content.Context sSystemContext = null;

    private static ServerSocket sStreamServer = null;
    private static ServerSocket sCtrlServer = null;
    private static volatile Socket sCurrentStreamClient = null;

    // ─────────────────────────────────────────────────────────────────────────
    // Input injection
    //
    // Android 9's `input` shell command has no -d/--display option, so the gateway cannot
    // target a secondary display through it. Inside this process (uid 1000, system) we can
    // call InputManager.injectInputEvent directly, which is also the only path that keeps
    // working once the app lives on the virtual display.
    // ─────────────────────────────────────────────────────────────────────────

    private static Object sInputManager = null;

    private static Object inputManager() throws Exception {
        if (sInputManager != null) return sInputManager;
        Class<?> imCls = Class.forName("android.hardware.input.InputManager");
        Object im = imCls.getMethod("getInstance").invoke(null);
        sInputManager = im;
        return im;
    }

    /** Inject one MotionEvent; returns false when the platform rejects it. */
    private static boolean injectMotion(android.view.MotionEvent ev) {
        try {
            Object im = inputManager();
            Class<?> imCls = im.getClass();
            java.lang.reflect.Method m = imCls.getMethod("injectInputEvent",
                    Class.forName("android.view.InputEvent"), int.class);
            Object result = m.invoke(im, ev, 0); // 0 = INJECT_INPUT_EVENT_MODE_ASYNC
            return result instanceof Boolean ? ((Boolean) result) : true;
        } catch (Throwable t) {
            System.err.println("[AgentDaemon] injectMotion failed: " + t);
            return false;
        } finally {
            try { ev.recycle(); } catch (Throwable ignored) {}
        }
    }

    private static android.view.MotionEvent makeMotion(long down, long when, int action, float x, float y) {
        android.view.MotionEvent ev = android.view.MotionEvent.obtain(down, when, action, x, y, 0);
        try {
            ev.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        } catch (Throwable ignored) {}
        return ev;
    }

    private static boolean injectTap(int x, int y, long durationMs) {
        long now = android.os.SystemClock.uptimeMillis();
        boolean ok = injectMotion(makeMotion(now, now, android.view.MotionEvent.ACTION_DOWN, x, y));
        if (durationMs > 0) {
            try { Thread.sleep(durationMs); } catch (InterruptedException ignored) {}
        } else {
            try { Thread.sleep(30); } catch (InterruptedException ignored) {}
        }
        long up = android.os.SystemClock.uptimeMillis();
        ok &= injectMotion(makeMotion(now, up, android.view.MotionEvent.ACTION_UP, x, y));
        return ok;
    }

    private static boolean injectSwipe(int x1, int y1, int x2, int y2, long durationMs) {
        long down = android.os.SystemClock.uptimeMillis();
        boolean ok = injectMotion(makeMotion(down, down, android.view.MotionEvent.ACTION_DOWN, x1, y1));
        int steps = Math.max(6, (int) (durationMs / 16));
        for (int i = 1; i <= steps; i++) {
            float t = i / (float) steps;
            float x = x1 + (x2 - x1) * t;
            float y = y1 + (y2 - y1) * t;
            long when = android.os.SystemClock.uptimeMillis();
            ok &= injectMotion(makeMotion(down, when, android.view.MotionEvent.ACTION_MOVE, x, y));
            try { Thread.sleep(Math.max(1, durationMs / steps)); } catch (InterruptedException ignored) {}
        }
        long up = android.os.SystemClock.uptimeMillis();
        ok &= injectMotion(makeMotion(down, up, android.view.MotionEvent.ACTION_UP, x2, y2));
        return ok;
    }

    private static boolean injectKey(int keyCode) {
        try {
            Object im = inputManager();
            java.lang.reflect.Method m = im.getClass().getMethod("injectInputEvent",
                    Class.forName("android.view.InputEvent"), int.class);
            long now = android.os.SystemClock.uptimeMillis();
            android.view.KeyEvent downEv = new android.view.KeyEvent(now, now,
                    android.view.KeyEvent.ACTION_DOWN, keyCode, 0);
            android.view.KeyEvent upEv = new android.view.KeyEvent(now, now + 30,
                    android.view.KeyEvent.ACTION_UP, keyCode, 0);
            m.invoke(im, downEv, 0);
            m.invoke(im, upEv, 0);
            return true;
        } catch (Throwable t) {
            System.err.println("[AgentDaemon] injectKey failed: " + t);
            return false;
        }
    }

    /** Frame source for on-demand screenshots (only valid while no stream client owns the VD surface). */
    private static volatile ImageReader sReader = null;
    private static volatile boolean sCaptureRequested = false;
    private static volatile String sCapturePath = null;
    private static volatile String sCaptureResult = null;

    /** Minimum spacing between cached frame copies; keeps 60fps streaming cheap. */
    private static final long FRAME_CACHE_INTERVAL_MS = 300;

    private static volatile int[] sLastFrame = null;
    private static volatile int sLastFrameW = 0;
    private static volatile int sLastFrameH = 0;
    private static volatile long sLastFrameTime = 0;

    /** Copy the newest frame into the shared cache (throttled by FRAME_CACHE_INTERVAL_MS). */
    private static synchronized void cacheFrame(Image img) {
        try {
            Image.Plane plane = img.getPlanes()[0];
            java.nio.ByteBuffer buf = plane.getBuffer();
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            int w = img.getWidth();
            int h = img.getHeight();
            int[] argb = sLastFrame;
            if (argb == null || w != sLastFrameW || h != sLastFrameH) {
                argb = new int[w * h];
            }
            byte[] row = new byte[rowStride];
            for (int y = 0; y < h; y++) {
                int pos = y * rowStride;
                if (pos >= buf.limit()) break;
                buf.position(pos);
                int n = Math.min(rowStride, buf.remaining());
                buf.get(row, 0, n);
                int base = y * w;
                for (int x = 0; x < w; x++) {
                    int off = x * pixelStride;
                    if (off + 3 >= n) break;
                    int r = row[off] & 0xff;
                    int g = row[off + 1] & 0xff;
                    int b = row[off + 2] & 0xff;
                    int a = row[off + 3] & 0xff;
                    argb[base + x] = (a << 24) | (r << 16) | (g << 8) | b;
                }
            }
            sLastFrame = argb;
            sLastFrameW = w;
            sLastFrameH = h;
        } catch (Throwable ignored) {
        }
    }

    /** Encode the cached frame to PNG. Throws when no frame has been captured yet. */
    private static void writePngFromCache(String path) throws Exception {
        int[] argb = sLastFrame;
        int w = sLastFrameW;
        int h = sLastFrameH;
        if (argb == null || w <= 0 || h <= 0) {
            throw new IllegalStateException("no_frame_cached");
        }
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        bmp.setPixels(argb, 0, w, 0, 0, w, h);
        FileOutputStream fos = new FileOutputStream(path);
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos);
        fos.flush();
        fos.close();
        bmp.recycle();
        DebugLog("screenshot written: " + path + " " + w + "x" + h);
    }

    /**
     * Convert one RGBA_8888 Image into a PNG on disk.
     *
     * Row padding is honoured via Plane.getRowStride(); ignoring it produces the classic
     * diagonal-skew screenshot on devices whose gralloc aligns rows.
     */
    private static void writePng(Image img, String path) throws Exception {
        Image.Plane plane = img.getPlanes()[0];
        java.nio.ByteBuffer buf = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int w = img.getWidth();
        int h = img.getHeight();
        int[] argb = new int[w * h];
        byte[] row = new byte[rowStride];
        for (int y = 0; y < h; y++) {
            int pos = y * rowStride;
            if (pos >= buf.limit()) break;
            buf.position(pos);
            int n = Math.min(rowStride, buf.remaining());
            buf.get(row, 0, n);
            int base = y * w;
            for (int x = 0; x < w; x++) {
                int off = x * pixelStride;
                if (off + 3 >= n) break;
                int r = row[off] & 0xff;
                int g = row[off + 1] & 0xff;
                int b = row[off + 2] & 0xff;
                int a = row[off + 3] & 0xff;
                argb[base + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        bmp.setPixels(argb, 0, w, 0, 0, w, h);
        FileOutputStream fos = new FileOutputStream(path);
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos);
        fos.flush();
        fos.close();
        bmp.recycle();
        DebugLog("screenshot written: " + path + " " + w + "x" + h);
    }

    private static void DebugLog(String msg) {
        System.out.println("[AgentDaemon] " + msg);
    }

    /**
     * Bind both listeners while the process is still uid 0.
     *
     * Android 9 + this SELinux domain rejects socket()/bind() for any non-root uid with
     * EACCES, so the ports must be claimed before dropping to uid 1000 for the
     * DisplayManager package check. accept()/read()/write() on the already-bound sockets
     * keep working after the drop (verified on device).
     */
    /** Bind only the control port (helper-only mode). */
    private static boolean preBindCtrl() {
        try {
            sCtrlServer = new ServerSocket();
            sCtrlServer.setReuseAddress(true);
            sCtrlServer.bind(new InetSocketAddress("127.0.0.1", 3072));
            System.out.println("[AgentDaemon] Pre-bound ctrl port 3072 (uid 0)");
            return true;
        } catch (Throwable t) {
            System.err.println("[AgentDaemon] Pre-bind 3072 failed: " + t.getMessage());
            return false;
        }
    }

    private static boolean preBindServers() {
        try {
            sStreamServer = new ServerSocket();
            sStreamServer.setReuseAddress(true);
            sStreamServer.bind(new InetSocketAddress("127.0.0.1", 3071));
            System.out.println("[AgentDaemon] Pre-bound stream port 3071 (uid 0)");
        } catch (Throwable t) {
            System.err.println("[AgentDaemon] Pre-bind 3071 failed: " + t.getMessage());
            return false;
        }
        try {
            sCtrlServer = new ServerSocket();
            sCtrlServer.setReuseAddress(true);
            sCtrlServer.bind(new InetSocketAddress("127.0.0.1", 3072));
            System.out.println("[AgentDaemon] Pre-bound ctrl port 3072 (uid 0)");
        } catch (Throwable t) {
            System.err.println("[AgentDaemon] Pre-bind 3072 failed: " + t.getMessage());
            try { sStreamServer.close(); } catch (Throwable ignored) {}
            sStreamServer = null;
            return false;
        }
        return true;
    }

    private static void startCtrlServer(final VirtualDisplay vd) {
        try {
            if (sCtrlServer == null) {
                sCtrlServer = new ServerSocket();
                sCtrlServer.setReuseAddress(true);
                sCtrlServer.bind(new InetSocketAddress("127.0.0.1", 3072));
            }
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    System.out.println("[AgentDaemon] Control server listening on 127.0.0.1:3072");
                    while (!sCtrlServer.isClosed()) {
                        try {
                            Socket sock = sCtrlServer.accept();
                            handleCtrlClient(vd, sock);
                        } catch (Throwable ignored) {}
                    }
                }
            }, "CtrlServerThread");
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            System.err.println("[AgentDaemon] Failed to bind ctrl server on 3072: " + t.getMessage());
        }
    }

    private static void handleCtrlClient(VirtualDisplay vd, Socket sock) {
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(sock.getInputStream()));
            java.io.PrintWriter pw = new java.io.PrintWriter(sock.getOutputStream(), true);
            String line = br.readLine();
            if (line != null && line.startsWith("clip ")) {
                // clip <base64-utf8>: publish text to the system clipboard so the gateway can
                // follow up with KEYCODE_PASTE. `input text` cannot carry CJK on Android 9.
                String b64 = line.trim().substring(5).trim();
                try {
                    if (sSystemContext == null) throw new IllegalStateException("no system context");
                    byte[] raw = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                    String text = new String(raw, "UTF-8");
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            sSystemContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("agent", text));
                    pw.println("OK " + text.length());
                } catch (Throwable ex) {
                    pw.println("ERR " + ex);
                }
            } else if (line != null && line.startsWith("resize ") && vd != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 4) {
                    int newW = Integer.parseInt(parts[1]);
                    int newH = Integer.parseInt(parts[2]);
                    int newDpi = Integer.parseInt(parts[3]);
                    if (newW > 16) newW = (newW / 16) * 16;
                    if (newH > 16) newH = (newH / 16) * 16;

                    if (newW == sWidth && newH == sHeight && newDpi == sDpi) {
                        pw.println("ALREADY_SET " + sWidth + " " + sHeight + " " + sDpi);
                    } else {
                        System.out.println("[AgentDaemon] Resizing VirtualDisplay to " + newW + "x" + newH + " @ " + newDpi);
                        sWidth = newW;
                        sHeight = newH;
                        sDpi = newDpi;
                        vd.resize(sWidth, sHeight, sDpi);
                        int did = vd.getDisplay() != null ? vd.getDisplay().getDisplayId() : -1;
                        writeStatus("running", did, android.os.Process.myPid(), sWidth, sHeight, sDpi);

                        // Close active stream client so encoder cleanly restarts with new dimensions
                        Socket cur = sCurrentStreamClient;
                        if (cur != null && !cur.isClosed()) {
                            try { cur.close(); } catch (Throwable ignored) {}
                        }
                        pw.println("OK " + sWidth + " " + sHeight + " " + sDpi);
                    }
                } else {
                    pw.println("ERR bad args");
                }
            } else if (line != null && line.startsWith("screenshot")) {
                // screenshot <abs-path>
                // Served from the ImageReader because Android 9's `screencap -d` only accepts
                // SurfaceFlinger display tokens and cannot address a Java-side virtual display.
                String path = line.trim().length() > 10 ? line.trim().substring(10).trim() : "";
                if (path.isEmpty()) {
                    pw.println("ERR no_path");
                } else if (sLastFrame != null) {
                    // Cache is warm: answer immediately, no dependence on new frames.
                    try {
                        writePngFromCache(path);
                        pw.println("OK " + path);
                    } catch (Throwable ex) {
                        pw.println("ERR " + ex);
                    }
                } else {
                    sCapturePath = path;
                    sCaptureResult = null;
                    sCaptureRequested = true;
                    String result = null;
                    for (int i = 0; i < 60; i++) { // up to ~3s
                        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                        result = sCaptureResult;
                        if (result != null) break;
                    }
                    if (result == null) {
                        sCaptureRequested = false;
                        pw.println("ERR timeout");
                    } else {
                        pw.println(result);
                    }
                }
            } else if (line != null && line.startsWith("launch ")) {
                // launch <displayId> <pkg>/<activity>
                // Executed from this process (uid 1000): Android 9 refuses launchDisplayId
                // from shell/root, but the system uid passes SafeActivityOptions.checkPermissions.
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 3) {
                    pw.println("ERR bad args");
                } else {
                    String display = parts[1];
                    String comp = parts[2];
                    Process proc = null;
                    try {
                        proc = Runtime.getRuntime().exec(new String[] {
                                "/system/bin/sh", "-c",
                                // NEW_TASK | MULTIPLE_TASK forces a fresh instance on the target
                                // display; without it AMS reuses the running instance on
                                // display 0 and answers "intent has been delivered to
                                // currently running top-most instance".
                                "/system/bin/am start --display " + display + " --user 0 -f 0x18000000 -n " + comp + " 2>&1"
                        });
                        java.io.BufferedReader out = new java.io.BufferedReader(
                                new java.io.InputStreamReader(proc.getInputStream()));
                        StringBuilder sb = new StringBuilder();
                        String l;
                        while ((l = out.readLine()) != null) sb.append(l).append(' ');
                        int rc = proc.waitFor();
                        String text = sb.toString().trim();
                        boolean denied = text.contains("Security exception") || text.contains("Permission Denial")
                                || text.contains("Error:") || text.contains("Exception");
                        if (rc == 0 && !denied) {
                            pw.println("OK " + text);
                        } else {
                            pw.println("ERR " + text);
                        }
                    } catch (Throwable ex) {
                        pw.println("ERR " + ex);
                    } finally {
                        if (proc != null) try { proc.destroy(); } catch (Throwable ignored) {}
                    }
                }
            } else if (line != null && line.startsWith("tap ")) {
                // tap <x> <y> [durationMs]
                String[] p = line.trim().split("\\s+");
                try {
                    int x = Integer.parseInt(p[1]);
                    int y = Integer.parseInt(p[2]);
                    long dur = p.length > 3 ? Long.parseLong(p[3]) : 0;
                    pw.println(injectTap(x, y, dur) ? "OK" : "ERR inject_failed");
                } catch (Throwable ex) {
                    pw.println("ERR " + ex);
                }
            } else if (line != null && line.startsWith("swipe ")) {
                // swipe <x1> <y1> <x2> <y2> [durationMs]
                String[] p = line.trim().split("\\s+");
                try {
                    int x1 = Integer.parseInt(p[1]);
                    int y1 = Integer.parseInt(p[2]);
                    int x2 = Integer.parseInt(p[3]);
                    int y2 = Integer.parseInt(p[4]);
                    long dur = p.length > 5 ? Long.parseLong(p[5]) : 300;
                    pw.println(injectSwipe(x1, y1, x2, y2, dur) ? "OK" : "ERR inject_failed");
                } catch (Throwable ex) {
                    pw.println("ERR " + ex);
                }
            } else if (line != null && line.startsWith("key ")) {
                // key <keycode>
                String[] p = line.trim().split("\\s+");
                try {
                    int kc = Integer.parseInt(p[1]);
                    pw.println(injectKey(kc) ? "OK" : "ERR inject_failed");
                } catch (Throwable ex) {
                    pw.println("ERR " + ex);
                }
            } else {
                pw.println("ERR unknown command");
            }
            sock.close();
        } catch (Throwable err) {
            try { sock.close(); } catch (Throwable ignored) {}
        }
    }

    /**
     * Binds the internal stream listener on 127.0.0.1:3071 and starts the accept loop.
     *
     * Synchronous on purpose: the caller must not publish status="running" until the port
     * is actually accepting connections, otherwise the web console races the listener.
     *
     * @return true when the socket is bound and the accept loop is running.
     */
    private static boolean startStreamServer(final VirtualDisplay vd, final ImageReader reader) {
        try {
            if (sStreamServer == null) {
                sStreamServer = new ServerSocket();
                sStreamServer.setReuseAddress(true);
                sStreamServer.bind(new InetSocketAddress("127.0.0.1", 3071));
            }
        } catch (Throwable err) {
            System.err.println("[AgentDaemon] Stream server bind failed: " + err.getMessage());
            return false;
        }

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                System.out.println("[AgentDaemon] Stream server listening on 127.0.0.1:3071");
                while (!sStreamServer.isClosed()) {
                    Socket client = null;
                    try {
                        client = sStreamServer.accept();
                        client.setTcpNoDelay(true);
                        System.out.println("[AgentDaemon] Stream client connected from " + client.getRemoteSocketAddress());
                        // Serve each client on its own thread so a long-lived encoder session
                        // cannot block the accept loop (the gateway is the single-writer hub,
                        // so this normally stays at one connection).
                        final Socket c = client;
                        Thread worker = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    handleStreamClient(vd, reader, c);
                                } catch (Throwable err) {
                                    System.err.println("[AgentDaemon] Stream client session closed: " + err.getMessage());
                                } finally {
                                    try { c.close(); } catch (Throwable ignored) {}
                                }
                            }
                        }, "StreamClientThread");
                        worker.setDaemon(true);
                        worker.start();
                        client = null; // ownership transferred to the worker thread
                    } catch (Throwable err) {
                        if (sStreamServer.isClosed()) break;
                        System.err.println("[AgentDaemon] Stream server error: " + err.getMessage());
                    } finally {
                        if (client != null) {
                            try { client.close(); } catch (Throwable ignored) {}
                        }
                    }
                }
            }
        }, "StreamServerThread");
        t.setDaemon(true);
        t.start();
        return true;
    }

    private static void stopStreamServer() {
        if (sCtrlServer != null) {
            try {
                sCtrlServer.close();
            } catch (Throwable ignored) {}
        }
        if (sStreamServer != null) {
            try {
                sStreamServer.close();
            } catch (Throwable ignored) {}
        }
    }

    private static void handleStreamClient(VirtualDisplay vd, ImageReader reader, Socket client) throws Exception {
        sCurrentStreamClient = client;
        MediaCodec codec = null;
        Surface encoderSurface = null;
        try {
            codec = MediaCodec.createEncoderByType("video/avc");
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", sWidth, sHeight);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 6000000); // 6 Mbps
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 60);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1); // 1s keyframe interval
            try {
                format.setLong("repeat-previous-frame-after", 100000L); // 100ms
            } catch (Throwable ignored) {}

            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderSurface = codec.createInputSurface();
            codec.start();

            // Direct SurfaceFlinger GPU composition to hardware encoder
            vd.setSurface(encoderSurface);
            System.out.println("[AgentDaemon] VirtualDisplay output directed to MediaCodec");

            DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(client.getOutputStream(), 65536));
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            byte[] buf = null;

            while (!client.isClosed() && !client.isOutputShutdown()) {
                int outIndex = codec.dequeueOutputBuffer(info, 20000);
                if (outIndex >= 0) {
                    ByteBuffer outputBuffer = codec.getOutputBuffer(outIndex);
                    if (outputBuffer != null && info.size > 0) {
                        outputBuffer.position(info.offset);
                        outputBuffer.limit(info.offset + info.size);

                        if (buf == null || buf.length < info.size) {
                            buf = new byte[info.size];
                        }
                        outputBuffer.get(buf, 0, info.size);

                        dos.writeInt(info.size);
                        dos.writeInt(info.flags);
                        dos.writeLong(info.presentationTimeUs);
                        dos.write(buf, 0, info.size);
                        dos.flush();
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                }
            }
        } finally {
            if (sCurrentStreamClient == client) {
                sCurrentStreamClient = null;
            }
            // Restore surface back to ImageReader
            try {
                vd.setSurface(reader.getSurface());
                System.out.println("[AgentDaemon] VirtualDisplay output restored to ImageReader");
            } catch (Throwable t) {
                System.err.println("[AgentDaemon] Failed to restore surface: " + t.getMessage());
            }
            if (codec != null) {
                try { codec.stop(); } catch (Throwable ignored) {}
                try { codec.release(); } catch (Throwable ignored) {}
            }
            if (encoderSurface != null) {
                try { encoderSurface.release(); } catch (Throwable ignored) {}
            }
        }
    }
}
