package com.benwintle.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Optional diagnostics page served on the home Wi-Fi (off by default; VistaGlow settings → Remote diagnostics).
 * Every request needs the token shown in VistaGlow settings.
 *   /            menu
 *   /log         recent VistaGlowRemote log lines
 *   /shot        screenshot of the projector (PNG)
 *   /key?k=up|down|left|right|ok|back|home   press a remote button (Netflix navigation uses VistaGlow's helper)
 *   /tree        layout of whatever is on screen
 */
class DebugServer {
    static final int PORT = 8765;
    private static final ArrayDeque<String> LOG = new ArrayDeque<>();

    static void record(String line) {
        synchronized (LOG) {
            LOG.addLast(new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(new java.util.Date()) + " " + line);
            while (LOG.size() > 1500) LOG.removeFirst();
        }
    }

    interface Actions {
        void key(String k);
        String tree();
    }

    private final AccessibilityService svc;
    private final Actions actions;
    private final String token;
    private volatile ServerSocket server;
    private final Handler ui = new Handler(Looper.getMainLooper());

    DebugServer(AccessibilityService svc, Actions actions, String token) {
        this.svc = svc; this.actions = actions; this.token = token;
    }

    void start() {
        if (server != null) return;
        Thread t = new Thread(() -> {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(PORT));
                server = ss;
                HomeService.log("diagnostics page on port " + PORT);
                while (!ss.isClosed()) {
                    Socket s = ss.accept();
                    new Thread(() -> handle(s)).start();
                }
            } catch (Exception e) {
                HomeService.log("diagnostics stopped: " + e);
            }
        }, "vg-debug");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        server = null;
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(5000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String first = in.readLine();
            if (first == null) { s.close(); return; }
            String[] parts = first.split(" ");
            String target = parts.length > 1 ? parts[1] : "/";
            String path = target, query = "";
            int q = target.indexOf('?');
            if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }
            String t = param(query, "t"), k = param(query, "k");
            OutputStream out = s.getOutputStream();
            if (!token.equals(t)) { send(out, 403, "text/plain", "Wrong or missing token".getBytes(StandardCharsets.UTF_8)); s.close(); return; }
            switch (path) {
                case "/log": {
                    StringBuilder sb = new StringBuilder();
                    synchronized (LOG) { for (String l : LOG) sb.append(l).append('\n'); }
                    send(out, 200, "text/plain; charset=utf-8", sb.toString().getBytes(StandardCharsets.UTF_8));
                    break;
                }
                case "/clear": {
                    synchronized (LOG) { LOG.clear(); }
                    send(out, 200, "text/plain", "cleared".getBytes(StandardCharsets.UTF_8));
                    break;
                }
                case "/shot": {
                    byte[] png = screenshot();
                    if (png == null) send(out, 500, "text/plain", "screenshot failed".getBytes(StandardCharsets.UTF_8));
                    else send(out, 200, "image/png", png);
                    break;
                }
                case "/key": {
                    final String key = k == null ? "" : k;
                    CountDownLatch done = new CountDownLatch(1);
                    ui.post(() -> { try { actions.key(key); } finally { done.countDown(); } });
                    done.await(3, TimeUnit.SECONDS);
                    send(out, 200, "text/plain", ("pressed " + key).getBytes(StandardCharsets.UTF_8));
                    break;
                }
                case "/tree": {
                    final String[] txt = { "" };
                    CountDownLatch done = new CountDownLatch(1);
                    ui.post(() -> { try { txt[0] = actions.tree(); } finally { done.countDown(); } });
                    done.await(5, TimeUnit.SECONDS);
                    send(out, 200, "text/plain; charset=utf-8", txt[0].getBytes(StandardCharsets.UTF_8));
                    break;
                }
                default: {
                    String tk = "t=" + token;
                    String html = "<!doctype html><meta name=viewport content='width=device-width'><title>VistaGlow diagnostics</title>"
                        + "<body style='font:16px sans-serif;background:#111;color:#eee;padding:16px'><h2>VistaGlow diagnostics</h2>"
                        + "<p><a style='color:#8ab4f8' href='/log?" + tk + "'>Log</a> · <a style='color:#8ab4f8' href='/shot?" + tk + "'>Screenshot</a> · "
                        + "<a style='color:#8ab4f8' href='/tree?" + tk + "'>Screen layout</a> · <a style='color:#8ab4f8' href='/clear?" + tk + "'>Clear log</a></p>"
                        + "<p>Remote: " + btn("up", tk) + btn("down", tk) + btn("left", tk) + btn("right", tk) + btn("ok", tk) + btn("back", tk) + btn("home", tk) + "</p>"
                        + "</body>";
                    send(out, 200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
                }
            }
            s.close();
        } catch (Exception e) {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    private static String btn(String k, String tk) {
        return "<a style='display:inline-block;margin:4px;padding:10px 16px;background:#333;color:#fff;border-radius:8px;text-decoration:none' href='/key?k=" + k + "&" + tk + "'>" + k + "</a>";
    }

    private static String param(String query, String name) {
        for (String kv : query.split("&")) {
            int e = kv.indexOf('=');
            if (e > 0 && kv.substring(0, e).equals(name)) {
                try { return URLDecoder.decode(kv.substring(e + 1), "UTF-8"); } catch (Exception ex) { return kv.substring(e + 1); }
            }
        }
        return null;
    }

    private static void send(OutputStream out, int code, String type, byte[] body) throws Exception {
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\nContent-Type: " + type
            + "\r\nContent-Length: " + body.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private byte[] screenshot() {
        final byte[][] result = { null };
        CountDownLatch done = new CountDownLatch(1);
        try {
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.getMainExecutor(), new AccessibilityService.TakeScreenshotCallback() {
                @Override public void onSuccess(AccessibilityService.ScreenshotResult r) {
                    try {
                        HardwareBuffer hb = r.getHardwareBuffer();
                        ColorSpace cs = r.getColorSpace();
                        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, cs);
                        Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
                        hb.close();
                        Bitmap small = Bitmap.createScaledBitmap(sw, sw.getWidth() / 2, sw.getHeight() / 2, true);
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        small.compress(Bitmap.CompressFormat.PNG, 100, bos);
                        result[0] = bos.toByteArray();
                    } catch (Exception e) { HomeService.log("shot error " + e); }
                    done.countDown();
                }
                @Override public void onFailure(int code) { HomeService.log("shot failed code=" + code); done.countDown(); }
            });
            done.await(5, TimeUnit.SECONDS);
        } catch (Exception e) { HomeService.log("shot error " + e); }
        return result[0];
    }
}
