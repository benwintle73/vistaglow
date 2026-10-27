package com.benwintle.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 1. Makes the remote's Home button (and the stock home screen) open VistaGlow.
 * 2. Netflix remote: the Netflix app on this device is the phone build, which expects touch.
 *    While Netflix is in front, the arrows move a white box between its cards using Android
 *    accessibility nodes (rows = children of the page list, cards = clickable nodes in a row),
 *    and OK clicks exactly the highlighted card. Scrolling uses accessibility scroll actions,
 *    then waits for the screen to stop moving. Touch gestures are a dead fallback: this projector
 *    cancels them. Back is passed to Netflix; the box is re-placed once the new screen settles.
 */
public class HomeService extends AccessibilityService {

    static final String[] STOCK_HOMES = {
        "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx",
        "com.android.tv.launcher", "com.android.launcher3"
    };
    static final String NETFLIX = "com.netflix.mediaclient";

    private long lastLaunch = 0;

    boolean enabled() { return getSharedPreferences("home", MODE_PRIVATE).getBoolean("homeOverride", true); }
    boolean netflixEnabled() { return getSharedPreferences("home", MODE_PRIVATE).getBoolean("netflixRemote", true); }

    boolean isStockHome(CharSequence pkg) {
        if (pkg == null) return false;
        String p = pkg.toString();
        if (p.equals(getPackageName())) return false;
        for (String s : STOCK_HOMES) if (s.equals(p)) return true;
        ResolveInfo ri = getPackageManager().resolveActivity(
            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        return ri != null && ri.activityInfo != null && p.equals(ri.activityInfo.packageName)
            && !"android".equals(p);
    }

    void openVistaGlow() {
        long now = System.currentTimeMillis();
        if (now - lastLaunch < 700) return;
        lastLaunch = now;
        Intent i = new Intent(this, MainActivity.class)
            .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { startActivity(i); } catch (Exception ignored) { }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null) return;
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName();
        if (pkg != null && NETFLIX.contentEquals(pkg)) {
            keepNetflixOffMouseList();
            CharSequence cls = e.getClassName();
            if (cls != null && cls.toString().startsWith("com.netflix")
                    && cls.toString().endsWith("Activity")) {
                inPlayer = cls.toString().toLowerCase().contains("player");
            }
        } else if (pkg != null && !"com.android.systemui".contentEquals(pkg)
                && !getPackageName().contentEquals(pkg)) {
            clearSelection();
        }
        if (enabled() && isStockHome(pkg)) openVistaGlow();
    }

    @Override protected boolean onKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_DOWN) log("seen " + KeyEvent.keyCodeToString(k) + " scan=" + e.getScanCode());
        if (k == KeyEvent.KEYCODE_HOME) {
            if (!enabled()) return false;
            if (e.getAction() == KeyEvent.ACTION_UP) { clearSelection(); openVistaGlow(); }
            return true;
        }
        // Everything below only applies while Netflix is the app on screen and the helper is on.
        // Any other app gets every key untouched.
        if (k == KeyEvent.KEYCODE_BACK && backArmed) return backKey(e, true);   // finish a Back already started
        if (!netflixEnabled() || !(isNavKey(k) || k == KeyEvent.KEYCODE_BACK || passKey == k)) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        boolean netflix = root != null && root.getPackageName() != null && NETFLIX.contentEquals(root.getPackageName());

        if (k == KeyEvent.KEYCODE_BACK) return backKey(e, netflix);
        if (!netflix && passKey != k) return false;

        // Full-screen playback with the player's controls hidden: the keys go to Netflix's player.
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            passKey = shouldPassToPlayer(root) ? k : 0;
            if (passKey != 0) hideBox();
        }
        if (passKey == k) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                passKey = 0;
                if (isOkKey(k)) ui.postDelayed(this::revealPlayerControls, 450);
            }
            return false;
        }
        handleNetflixKey(e, root);
        return true;
    }

    // ---------- Back: a tap goes back; holding it turns the Netflix helper off ----------

    private boolean backArmed = false, backHoldDone = false;
    private final Runnable backHold = () -> {
        if (!backArmed) return;
        backHoldDone = true;
        log("BACK held: Netflix remote control turned off");
        getSharedPreferences("home", MODE_PRIVATE).edit().putBoolean("netflixRemote", false).apply();
        busy = false; queuedKey = 0; queuedIsRepeat = false; passKey = 0;
        clearSelection();
        try {
            android.widget.Toast.makeText(this, "Netflix remote control off. Turn it back on in VistaGlow settings.",
                android.widget.Toast.LENGTH_LONG).show();
        } catch (Exception ignored) { }
    };

    boolean backKey(KeyEvent e, boolean netflix) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (!netflix && !backArmed) return false;          // not in Netflix: normal Back, never armed
            if (e.getRepeatCount() == 0) {
                backArmed = true; backHoldDone = false;
                ui.removeCallbacks(backHold);
                ui.postDelayed(backHold, RemoteLogic.BACK_HOLD_MS);
            }
            return true;                                       // decided on release
        }
        if (e.getAction() == KeyEvent.ACTION_UP) {
            if (!backArmed) return false;
            backArmed = false;
            ui.removeCallbacks(backHold);
            if (backHoldDone) return true;                     // that was the hold: don't also go back
            performGlobalAction(GLOBAL_ACTION_BACK);
            log("BACK tap -> Netflix");
            queuedKey = 0; queuedIsRepeat = false;
            if (box != null) afterScreenChange(200);
            return true;
        }
        return backArmed;
    }

    // ---------- Playback: detect full-screen video and hand the remote to the player ----------

    private int passKey = 0;              // the key currently being passed straight to Netflix
    private boolean lastPass = false;

    boolean shouldPassToPlayer(AccessibilityNodeInfo root) {
        if (root == null) return false;
        String video = fullScreenVideo(root, 0);
        int controls = targets(root).size();
        boolean pass = RemoteLogic.passToPlayer(video != null, inPlayer, controls);
        if (pass != lastPass) {
            lastPass = pass;
            log("PLAYBACK " + (pass ? "on" : "off") + " video=" + video + " playerActivity=" + inPlayer + " controls=" + controls);
        }
        return pass;
    }

    /** Class/id of a visible full-screen video view, or null. */
    String fullScreenVideo(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 40 || !n.isVisibleToUser()) return null;
        String cls = n.getClassName() == null ? "" : n.getClassName().toString();
        String id = n.getViewIdResourceName();
        if (RemoteLogic.isVideoView(cls, id)) {
            Rect r = bounds(n);
            if (RemoteLogic.coversScreen(r.left, r.top, r.right, r.bottom, screenW(), screenH())) return cls + (id == null ? "" : " " + id);
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            String f = fullScreenVideo(n.getChild(i), depth + 1);
            if (f != null) return f;
        }
        return null;
    }

    /** OK went to the player; if Netflix didn't bring up its controls, tap the picture to show them. */
    void revealPlayerControls() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null || !NETFLIX.contentEquals(root.getPackageName())) return;
        if (!shouldPassToPlayer(root)) return;   // controls appeared
        log("player ignored OK: showing controls");
        playerKey(KeyEvent.KEYCODE_DPAD_CENTER);
    }

    @Override public void onInterrupt() { }

    @Override public void onDestroy() {
        clearSelection();
        if (debug != null) debug.stop();
        super.onDestroy();
    }

    // ---------- Netflix remote ----------

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean inPlayer = false;
    private AccessibilityNodeInfo cur;     // highlighted node
    private Rect curRect;                  // its last known on-screen bounds
    private View box;                      // highlight overlay
    private long lastMove = 0;

    static boolean isNavKey(int k) {
        return k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
            || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
            || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
            || isOkKey(k);
    }

    static boolean isOkKey(int k) {
        return k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
            || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_SELECT
            || k == KeyEvent.KEYCODE_BUTTON_A;
    }

    static void log(String m) { android.util.Log.w("VistaGlowRemote", m); DebugServer.record(m); }

    // ---------- optional diagnostics page (VistaGlow settings → Remote diagnostics) ----------

    private DebugServer debug;
    private android.content.SharedPreferences.OnSharedPreferenceChangeListener prefWatch;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        android.content.SharedPreferences sp = getSharedPreferences("home", MODE_PRIVATE);
        prefWatch = (p, key) -> { if ("debugServer".equals(key) || "debugToken".equals(key)) applyDebug(); };
        sp.registerOnSharedPreferenceChangeListener(prefWatch);
        applyDebug();
    }

    // ---------- Nebula mouse mode: Nebula keeps a list of apps that open with a mouse cursor ----------

    static final String MOUSE_LIST = "input.mouse.target";
    private long lastMouseCheck = 0;

    /** Take Netflix off Nebula's mouse-cursor list (needs WRITE_SECURE_SETTINGS, granted once from adb). */
    void keepNetflixOffMouseList() {
        long now = SystemClock.uptimeMillis();
        if (now - lastMouseCheck < 3000) return;
        lastMouseCheck = now;
        if (!netflixEnabled()) return;
        try {
            String v = android.provider.Settings.Global.getString(getContentResolver(), MOUSE_LIST);
            if (v == null || !v.contains(NETFLIX)) return;
            StringBuilder keep = new StringBuilder();
            for (String p : v.split(",")) {
                String t = p.trim();
                if (t.isEmpty() || t.equals(NETFLIX)) continue;
                if (keep.length() > 0) keep.append(',');
                keep.append(t);
            }
            boolean ok = android.provider.Settings.Global.putString(getContentResolver(), MOUSE_LIST, keep.toString());
            log("mouse list had Netflix; removed=" + ok + " now=" + keep);
        } catch (SecurityException se) {
            log("mouse list has Netflix but VistaGlow lacks permission to change it (grant WRITE_SECURE_SETTINGS once)");
        } catch (Exception ex) { log("mouse list error " + ex); }
    }

    String settingsReport() {
        android.content.ContentResolver cr = getContentResolver();
        boolean canWrite = checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == android.content.pm.PackageManager.PERMISSION_GRANTED;
        return MOUSE_LIST + "=" + android.provider.Settings.Global.getString(cr, MOUSE_LIST)
            + "\nautomatic_mouse_mode=" + android.provider.Settings.System.getString(cr, "automatic_mouse_mode")
            + " / global " + android.provider.Settings.Global.getString(cr, "automatic_mouse_mode")
            + "\nVistaGlow can change it: " + canWrite
            + "\naccessibility services=" + android.provider.Settings.Secure.getString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            + "\nVistaGlow version=" + versionCode()
            + "\nfeature touchscreen=" + getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
            + " faketouch=" + getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_FAKETOUCH)
            + " (Android only runs accessibility gestures when faketouch=true)"
            + "\nconfig touchscreen=" + getResources().getConfiguration().touchscreen + " (1=none)"
            + "\nNetflix remote control=" + netflixEnabled() + "\n" + tvInputsReport();
    }

    String tvInputsReport() {
        StringBuilder sb = new StringBuilder("TV inputs:\n");
        try {
            android.media.tv.TvInputManager tim = (android.media.tv.TvInputManager) getSystemService(TV_INPUT_SERVICE);
            if (tim == null) return sb.append("  (no TV input service)\n").toString();
            for (android.media.tv.TvInputInfo in : tim.getTvInputList()) {
                sb.append("  ").append(in.getId()).append(" type=").append(in.getType())
                  .append(" label=").append(in.loadLabel(this)).append(in.isHidden(this) ? " hidden" : "").append('\n');
            }
        } catch (Exception e) { sb.append("  error ").append(e).append('\n'); }
        return sb.toString();
    }

    long versionCode() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode(); } catch (Exception e) { return -1; }
    }

    void applyDebug() {
        android.content.SharedPreferences sp = getSharedPreferences("home", MODE_PRIVATE);
        boolean on = sp.getBoolean("debugServer", false);
        String token = sp.getString("debugToken", "");
        if (debug != null) { debug.stop(); debug = null; try { Thread.sleep(150); } catch (Exception ignored) { } }
        if (!on || token.isEmpty()) return;
        debug = new DebugServer(this, new DebugServer.Actions() {
            @Override public void key(String k, int holdMs) { remoteKey(k, holdMs); }
            @Override public String tree() { return settingsReport() + "\n" + screenTree(); }
            @Override public String open(String what) { return diagOpen(what); }
            @Override public String click(String text) { return diagClick(text); }
            @Override public String scroll(String dir) { return diagScroll(dir); }
        }, token);
        debug.start();
    }

    /**
     * A button pressed from the diagnostics page. It goes through onKeyEvent exactly like the remote's key would,
     * including holds (holdMs > 0 sends auto-repeats like a real held key, then the release).
     * Note: a key the helper decides to pass on (outside Netflix, or to Netflix's player) is only logged here,
     * because the diagnostics page can't inject real keys into other apps.
     */
    void remoteKey(String k, int holdMs) {
        int code;
        switch (k) {
            case "up": code = KeyEvent.KEYCODE_DPAD_UP; break;
            case "down": code = KeyEvent.KEYCODE_DPAD_DOWN; break;
            case "left": code = KeyEvent.KEYCODE_DPAD_LEFT; break;
            case "right": code = KeyEvent.KEYCODE_DPAD_RIGHT; break;
            case "ok": code = KeyEvent.KEYCODE_DPAD_CENTER; break;
            case "back": code = KeyEvent.KEYCODE_BACK; break;
            case "home": clearSelection(); openVistaGlow(); log("diag key home"); return;
            default: log("diag key unknown " + k); return;
        }
        final int c = code;
        final long down = SystemClock.uptimeMillis();
        boolean took = onKeyEvent(new KeyEvent(down, down, KeyEvent.ACTION_DOWN, c, 0));
        log("diag key " + k + " down consumed=" + took + (holdMs > 0 ? " holding " + holdMs + "ms" : ""));
        if (!took && c == KeyEvent.KEYCODE_BACK) performGlobalAction(GLOBAL_ACTION_BACK);   // what the real key would have done
        final boolean downTook = took;
        if (holdMs <= 0) {
            boolean upTook = onKeyEvent(new KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, c, 0));
            log("diag key " + k + " up consumed=" + upTook);
            return;
        }
        // Auto-repeat like a real held key: first after 500 ms, then every 50 ms.
        final int[] rep = { 0 };
        final Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            long now = SystemClock.uptimeMillis();
            if (now - down >= holdMs) {
                boolean upTook = onKeyEvent(new KeyEvent(down, now, KeyEvent.ACTION_UP, c, 0));
                log("diag key " + k + " up after " + (now - down) + "ms consumed=" + upTook + " repeats=" + rep[0]);
                return;
            }
            if (now - down >= 500 && downTook) {
                rep[0]++;
                onKeyEvent(new KeyEvent(down, now, KeyEvent.ACTION_DOWN, c, rep[0]));
            }
            ui.postDelayed(tick[0], 50);
        };
        ui.postDelayed(tick[0], 50);
    }

    /** Diagnostics: open a screen. "settings", "developer", "accessibility", or a package name. */
    String diagOpen(String what) {
        Intent i;
        switch (what) {
            case "settings": i = new Intent(android.provider.Settings.ACTION_SETTINGS); break;
            case "developer": i = new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS); break;
            case "wireless": i = new Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"); break;
            case "accessibility": i = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS); break;
            default:
                i = getPackageManager().getLeanbackLaunchIntentForPackage(what);
                if (i == null) i = getPackageManager().getLaunchIntentForPackage(what);
                if (i == null) return "can't open " + what;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); return "opened " + what; } catch (Exception e) { return "failed " + e; }
    }

    /** Diagnostics: click the first on-screen item whose label contains the text (case-insensitive). */
    String diagClick(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "no window";
        AccessibilityNodeInfo hit = findText(root, text.toLowerCase(), 0);
        if (hit == null) return "not found: " + text;
        for (AccessibilityNodeInfo n = hit; n != null; n = n.getParent()) {
            if (n.isClickable()) {
                boolean ok = n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                return "clicked \"" + keyOf(hit) + "\" ok=" + ok;
            }
        }
        return "found but nothing clickable: " + text;
    }

    AccessibilityNodeInfo findText(AccessibilityNodeInfo n, String q, int depth) {
        if (n == null || depth > 40) return null;
        CharSequence t = n.getText(), d = n.getContentDescription();
        if ((t != null && t.toString().toLowerCase().contains(q)) || (d != null && d.toString().toLowerCase().contains(q))) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo f = findText(n.getChild(i), q, depth + 1);
            if (f != null) return f;
        }
        return null;
    }

    /** Diagnostics: scroll the biggest list on screen ("down" / "up"). */
    String diagScroll(String dir) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "no window";
        AccessibilityNodeInfo page = pageScroller(root);
        if (page == null) page = findScrollableIn(root, 0);
        if (page == null) return "nothing scrollable";
        boolean ok = page.performAction("up".equals(dir) ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        return "scroll " + dir + " ok=" + ok;
    }

    String screenTree() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "no window";
        StringBuilder sb = new StringBuilder("app: " + root.getPackageName() + "\nhighlight: " + curRect + " \"" + curKey + "\"\n\n");
        treeTo(root, 0, sb, new int[]{0});
        return sb.toString();
    }

    void treeTo(AccessibilityNodeInfo n, int depth, StringBuilder sb, int[] count) {
        if (n == null || depth > 30 || count[0]++ > 1500) return;
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(describe(n)).append('\n');
        for (int i = 0; i < n.getChildCount(); i++) treeTo(n.getChild(i), depth + 1, sb, count);
    }

    // Real panel size in the same pixels as node bounds and gestures (app metrics can be scaled/inset).
    private final DisplayMetrics real = new DisplayMetrics();
    DisplayMetrics realMetrics() {
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            wm.getDefaultDisplay().getRealMetrics(real);
        } catch (Exception ex) { real.setTo(getResources().getDisplayMetrics()); }
        return real;
    }
    int screenW() { return realMetrics().widthPixels; }
    int screenH() { return realMetrics().heightPixels; }

    private int heldKey = 0;
    private boolean queuedIsRepeat = false;
    private boolean okArmed = false, okLongDone = false;

    private final Runnable okLong = () -> {
        if (!okArmed) return;
        okLongDone = true;
        longPress();
    };

    /** Long-click the highlighted card where Netflix supports it; otherwise an ordinary click. */
    void longPress() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        AccessibilityNodeInfo c = box == null ? null : (curIntact() ? cur : current(targets(root)));
        if (c == null) { press(root); return; }
        boolean done = false;
        for (AccessibilityNodeInfo n = c; n != null && !done; n = n.getParent()) {
            done = tryAction(n, AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK);
            if (n.isScrollable()) break;   // don't long-click the list itself
        }
        log("long press \"" + keyOf(c) + "\" longClick=" + done);
        if (!done) press(root);
        else afterScreenChange(250);
    }

    void handleNetflixKey(KeyEvent e, AccessibilityNodeInfo root) {
        int k = e.getKeyCode();
        log("key " + KeyEvent.keyCodeToString(k) + " action=" + e.getAction() + " repeat=" + e.getRepeatCount());
        if (e.getAction() == KeyEvent.ACTION_UP) {
            if (isOkKey(k) && okArmed) {
                okArmed = false;
                ui.removeCallbacks(okLong);
                if (!okLongDone) {
                    AccessibilityNodeInfo r = getRootInActiveWindow();
                    if (r != null) press(r);
                }
            }
            if (k == heldKey) heldKey = 0;
            // Let go of a held arrow: drop any repeat still waiting, so it never overshoots.
            if (queuedIsRepeat && queuedKey == k) { queuedKey = 0; queuedIsRepeat = false; log("drop queued repeat"); }
            return;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return;
        boolean repeat = e.getRepeatCount() > 0;
        if (!repeat) heldKey = k;
        else if (k != heldKey) return;
        if (busy) {
            // Netflix is still scrolling or loading: remember this press and do it once it stops.
            if (!repeat) { queuedKey = k; queuedIsRepeat = false; }
            else if (queuedKey == 0) { queuedKey = k; queuedIsRepeat = true; }
            log("busy: queued " + KeyEvent.keyCodeToString(k) + (repeat ? " (repeat)" : ""));
            return;
        }
        if (isOkKey(k)) {
            // Click on release; held past OK_LONG_MS = long-click. Auto-repeat is ignored.
            if (!repeat) {
                okArmed = true; okLongDone = false;
                ui.removeCallbacks(okLong);
                ui.postDelayed(okLong, RemoteLogic.OK_LONG_MS);
            }
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (!RemoteLogic.repeatAllowed(e.getRepeatCount(), now, lastMove)) return;
        lastMove = now;
        move(root, k, true);
    }

    // ---------- waiting for Netflix to finish scrolling ----------

    private boolean busy = false;
    private int queuedKey = 0;
    private long busyStart = 0;
    private String lastSig = null;
    private int sameSig = 0;
    private Runnable afterStable = null;

    /** Call right after asking Netflix to scroll. `then` runs once the screen has stopped moving. */
    void waitStable(Runnable then) {
        busy = true;
        busyStart = SystemClock.uptimeMillis();
        lastSig = null;
        sameSig = 0;
        afterStable = then;
        if (box != null) box.setAlpha(0f);
        ui.removeCallbacks(stablePoll);
        ui.postDelayed(stablePoll, 120);
    }

    private final Runnable stablePoll = new Runnable() {
        @Override public void run() {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || root.getPackageName() == null || !NETFLIX.contentEquals(root.getPackageName())) {
                busy = false; queuedKey = 0; clearSelection(); return;
            }
            String sig = signature(root);
            if (sig.equals(lastSig)) sameSig++; else { sameSig = 0; lastSig = sig; }
            boolean timedOut = SystemClock.uptimeMillis() - busyStart > 2000;
            if (sameSig < 2 && !timedOut) { ui.postDelayed(this, 80); return; }
            log("stable after " + (SystemClock.uptimeMillis() - busyStart) + "ms" + (timedOut ? " (timeout)" : ""));
            Runnable then = afterStable;
            afterStable = null;
            busy = false;
            try { if (then != null) then.run(); } catch (Exception ex) { log("after-stable error " + ex); }
            if (box != null && box.getAlpha() < 0.5f && cur != null) showBox(curRect = bounds(cur), true);
            if (queuedKey != 0 && !busy) {
                int k = queuedKey; queuedKey = 0; queuedIsRepeat = false;
                AccessibilityNodeInfo r2 = getRootInActiveWindow();
                if (r2 != null && r2.getPackageName() != null && NETFLIX.contentEquals(r2.getPackageName())) {
                    if (isOkKey(k)) press(r2); else move(r2, k, true);
                }
            }
        }
    };

    /** Positions of everything tappable: unchanged across polls = finished scrolling. */
    String signature(AccessibilityNodeInfo root) {
        StringBuilder sb = new StringBuilder();
        for (AccessibilityNodeInfo n : targets(root)) {
            Rect r = bounds(n);
            sb.append(r.left).append(',').append(r.top).append(',').append(r.right).append(';');
        }
        return sb.toString();
    }

    /** All visible, tappable things on screen, innermost first. */
    List<AccessibilityNodeInfo> targets(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        Rect screen = new Rect(0, 0, screenW(), screenH());
        long maxArea = (long) screen.width() * screen.height() * 6 / 10;
        collect(root, out, screen, maxArea, 0);
        return out;
    }

    void collect(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, Rect screen, long maxArea, int depth) {
        if (n == null || depth > 60 || !n.isVisibleToUser()) return;
        if ((n.isClickable() || n.isCheckable()) && n.isEnabled()) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            Rect vis = new Rect(r);
            if (vis.intersect(screen) && vis.width() >= 12 && vis.height() >= 12
                    && (long) r.width() * r.height() <= maxArea) out.add(n);
        }
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), out, screen, maxArea, depth + 1);
    }

    static Rect bounds(AccessibilityNodeInfo n) { Rect r = new Rect(); n.getBoundsInScreen(r); return r; }

    private String curKey = "";   // what the highlighted card IS (its label), to catch Netflix reusing views

    /** A card's identity: its label ("Play Below"), else its text, else its first labelled child. */
    static String keyOf(AccessibilityNodeInfo n) {
        if (n == null) return "";
        CharSequence d = n.getContentDescription();
        if (d != null && d.length() > 0) return d.toString();
        CharSequence t = n.getText();
        if (t != null && t.length() > 0) return t.toString();
        for (int i = 0; i < Math.min(n.getChildCount(), 6); i++) {
            AccessibilityNodeInfo ch = n.getChild(i);
            if (ch == null) continue;
            CharSequence cd = ch.getContentDescription(), ct = ch.getText();
            if (cd != null && cd.length() > 0) return cd.toString();
            if (ct != null && ct.length() > 0) return ct.toString();
        }
        String id = n.getViewIdResourceName();
        return id == null ? "" : id;
    }

    /** Is cur still the same card (not recycled into another title)? */
    boolean curIntact() {
        if (cur == null || !cur.refresh() || !cur.isVisibleToUser()) return false;
        if (curKey.isEmpty()) return true;
        return curKey.equals(keyOf(cur));
    }

    /** Find the highlighted card again by its label (after a scroll or a view being reused). */
    AccessibilityNodeInfo findByKey(List<AccessibilityNodeInfo> t) {
        if (curKey.isEmpty()) return null;
        AccessibilityNodeInfo best = null; long bestD = Long.MAX_VALUE;
        for (AccessibilityNodeInfo n : t) {
            if (!curKey.equals(keyOf(n))) continue;
            if (curRect == null) return n;
            Rect r = bounds(n);
            long dx = r.centerX() - curRect.centerX(), dy = r.centerY() - curRect.centerY();
            long d = dx * dx + dy * dy;
            if (d < bestD) { bestD = d; best = n; }
        }
        return best;
    }

    /** Re-finds the highlighted node on screen (it may have moved or been replaced). */
    AccessibilityNodeInfo current(List<AccessibilityNodeInfo> t) {
        if (curIntact()) {
            Rect r = bounds(cur);
            curRect = r;
            return cur;
        }
        AccessibilityNodeInfo same = findByKey(t);
        if (same != null) { cur = same; curRect = bounds(same); return same; }
        if (curRect == null || t.isEmpty()) return null;
        AccessibilityNodeInfo best = null; long bestD = Long.MAX_VALUE;
        for (AccessibilityNodeInfo n : t) {
            Rect r = bounds(n);
            long dx = r.centerX() - curRect.centerX(), dy = r.centerY() - curRect.centerY();
            long d = dx * dx + dy * dy;
            if (d < bestD) { bestD = d; best = n; }
        }
        return best;
    }

    AccessibilityNodeInfo firstTarget(List<AccessibilityNodeInfo> t) {
        AccessibilityNodeInfo best = null; long bestS = Long.MAX_VALUE;
        for (AccessibilityNodeInfo n : t) {
            Rect r = bounds(n);
            long s = (long) Math.max(r.top, 0) * 2 + Math.max(r.left, 0);
            if (s < bestS) { bestS = s; best = n; }
        }
        return best;
    }

    void move(AccessibilityNodeInfo root, int key, boolean scrollIfStuck) {
        List<AccessibilityNodeInfo> t = targets(root);
        if (inPlayer && t.size() < 2) { playerKey(key); return; }
        if (t.isEmpty()) { swipe(key, null); return; }
        AccessibilityNodeInfo c = current(t);
        if (c == null || box == null) { select(firstTarget(t)); return; }
        if (scrollIfStuck && structuredMove(root, c, key, true)) return;
        Rect a = bounds(c);
        boolean vertical = key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_UP;

        // Up/Down inside the scrolling page stay inside it (skip the fixed top/bottom bars)
        // until the page can't scroll any further.
        AccessibilityNodeInfo page = vertical ? pageScroller(root) : null;
        List<AccessibilityNodeInfo> inPage = page == null ? null : targets(page);
        boolean curInPage = inPage != null && hasRect(inPage, a);
        AccessibilityNodeInfo best = pick(curInPage ? inPage : t, a, key);
        log("move " + KeyEvent.keyCodeToString(key) + " cur=" + a + " inPage=" + curInPage
            + " best=" + (best == null ? "none" : bounds(best).toString()));
        if (best == null && curInPage && scrollIfStuck) {
            // First try to bring just the neighbouring row into view (small scroll)...
            if (showNeighbourRow(page, c, key)) return;
            // ...otherwise scroll a whole page.
            if (scrollPage(page, key)) { final int k = key; waitStable(() -> afterScroll(k)); return; }
            best = pick(t, a, key);   // end of the page: now allow the bars
        }
        if (best != null) {
            // Partly off screen/under a bar? Ask the page to bring that whole row into view.
            boolean needsRow = vertical && (clipped(bounds(best)) || (curInPage && page != null && !new Rect(bounds(page)).contains(bounds(best))));
            select(best, needsRow);
            if (needsRow) {
                AccessibilityNodeInfo pg = page != null ? page : pageScroller(root);
                AccessibilityNodeInfo row = pg == null ? null : childOf(best, pg);
                boolean ok = false;
                try { if (row != null) ok = row.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId()); } catch (Exception ignored) { }
                log("show row " + (row == null ? "none" : bounds(row).toString()) + " ok=" + ok);
            }
            return;
        }
        if (scrollIfStuck) scrollFor(root, c, key);
    }

    /** Scrolls the row above/below the current one into view and highlights its nearest item. */
    boolean showNeighbourRow(AccessibilityNodeInfo page, AccessibilityNodeInfo c, int key) {
        AccessibilityNodeInfo row = childOf(c, page);
        if (row == null) return false;
        int idx = -1;
        for (int i = 0; i < page.getChildCount(); i++) {
            AccessibilityNodeInfo ch = page.getChild(i);
            if (ch != null && ch.equals(row)) { idx = i; break; }
        }
        int ni = key == KeyEvent.KEYCODE_DPAD_DOWN ? idx + 1 : idx - 1;
        if (idx < 0 || ni < 0 || ni >= page.getChildCount()) return false;
        AccessibilityNodeInfo next = page.getChild(ni);
        boolean ok = false;
        try { ok = next != null && next.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId()); } catch (Exception ignored) { }
        log("neighbour row " + ni + "/" + page.getChildCount() + " ok=" + ok);
        if (!ok) return false;
        final AccessibilityNodeInfo target = next;
        waitStable(() -> {
            target.refresh();
            List<AccessibilityNodeInfo> items = targets(target);
            int x = curRect == null ? 0 : curRect.centerX();
            AccessibilityNodeInfo best = nearestX(items, x);
            if (best != null) select(best, false);
            else { AccessibilityNodeInfo root = getRootInActiveWindow(); if (root != null) move(root, key, false); }
        });
        return true;
    }

    /** Same row = similar height and mostly overlapping vertically (so posters don't jump to the chips above). */
    static boolean sameRow(Rect a, Rect b) {
        int lo = Math.min(a.height(), b.height()), hi = Math.max(a.height(), b.height());
        if (hi == 0 || lo * 2 < hi) return false;
        int overlap = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
        return overlap * 2 >= lo;
    }

    // ---------- structured navigation: Netflix's own list of rows, and posters within each row ----------

    static int indexIn(AccessibilityNodeInfo parent, AccessibilityNodeInfo child) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            AccessibilityNodeInfo ch = parent.getChild(i);
            if (ch != null && ch.equals(child)) return i;
        }
        return -1;
    }

    static AccessibilityNodeInfo firstClickable(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 8) return null;
        if (n.isClickable() && n.isEnabled()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo f = firstClickable(n.getChild(i), depth + 1);
            if (f != null) return f;
        }
        return null;
    }

    /** The sideways-scrolling list inside a row that holds n (between n and the row), or null. */
    static AccessibilityNodeInfo rowScroller(AccessibilityNodeInfo n, AccessibilityNodeInfo row) {
        for (AccessibilityNodeInfo p = n.getParent(); p != null; p = p.getParent()) {
            if (p.isScrollable()) return p;
            if (p.equals(row)) return null;
        }
        return null;
    }

    /**
     * Moves by structure rather than position: Up/Down go to the previous/next row of the page list,
     * Left/Right to the previous/next poster in the row's list. Returns false when this screen
     * doesn't have that structure (or the highlight is on a bar outside the page), so the
     * position-based fallback runs instead.
     */
    boolean structuredMove(AccessibilityNodeInfo root, AccessibilityNodeInfo c, int key, boolean mayScroll) {
        AccessibilityNodeInfo page = pageScroller(root);
        if (page == null) return false;
        AccessibilityNodeInfo row = childOf(c, page);
        if (row == null) return false;
        int ri = indexIn(page, row);
        if (ri < 0) return false;
        AccessibilityNodeInfo card = cardOf(c, row);
        if (!card.equals(c)) { log("S lift to card " + bounds(card)); c = card; cur = card; curRect = bounds(card); }
        final Rect a = bounds(c);
        final int k = key;

        if (key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_UP) {
            boolean down = key == KeyEvent.KEYCODE_DPAD_DOWN;
            // Netflix's list order can differ from screen order, so find the next row by position.
            Rect rb = bounds(row);
            AccessibilityNodeInfo next = null; Rect nb = null;
            for (int j = 0; j < page.getChildCount(); j++) {
                AccessibilityNodeInfo ch = page.getChild(j);
                if (ch == null || ch.equals(row) || firstClickable(ch, 0) == null) continue;
                Rect r = bounds(ch);
                boolean visible = ch.isVisibleToUser() && !r.isEmpty();
                if (visible && (down ? r.top < rb.bottom - 4 : r.bottom > rb.top + 4)) continue;   // not that way
                if (!visible) {
                    // off-screen row: only usable if it's the neighbour in list order
                    int here = indexIn(page, row);
                    if (j != here + (down ? 1 : -1)) continue;
                    if (next == null) { next = ch; nb = null; }
                    continue;
                }
                if (nb == null || (down ? r.top < nb.top : r.bottom > nb.bottom)) { next = ch; nb = r; }
            }
            if (next != null && nb != null && rowFullyShown(next)) {
                AccessibilityNodeInfo it = nearestX(targets(next), a.centerX());
                if (it != null && !clipped(bounds(it)) && bounds(it).bottom < screenH() * 0.89f) {
                    log("S row -> " + nb + " item " + bounds(it)); select(it, false); return true;
                }
                log("S row -> " + nb + " but its card is cut off: scrolling it in");
            }
            if (next != null) {
                // Row is cut off or off screen: scroll just that row into view, wait, then pick.
                boolean ok = tryShow(next);
                log("S show row " + (nb == null ? "offscreen" : nb.toString()) + " ok=" + ok);
                if (ok) {
                    final AccessibilityNodeInfo target = next;
                    waitStable(() -> {
                        target.refresh();
                        AccessibilityNodeInfo it = nearestX(targets(target), a.centerX());
                        if (it == null) { afterScroll(k); return; }
                        log("S after show -> " + bounds(it));
                        if (clipped(bounds(it)) || bounds(it).bottom >= screenH() * 0.89f || bounds(it).top < 8) {
                            // Netflix only partly scrolled a tall row in: scroll the page and take the first full row.
                            AccessibilityNodeInfo r2 = getRootInActiveWindow();
                            AccessibilityNodeInfo pg = r2 == null ? null : pageScroller(r2);
                            log("S row still cut " + bounds(target) + " -> page scroll");
                            if (pg != null && scrollPage(pg, k)) { waitStable(() -> afterScroll(k)); return; }
                        }
                        select(it, false);
                    });
                    return true;
                }
            }
            // No row that way on screen: scroll the page, then try again once it stops.
            if (mayScroll && scrollPage(page, key)) {
                waitStable(() -> {
                    AccessibilityNodeInfo r2 = getRootInActiveWindow();
                    if (r2 == null) return;
                    if (cur == null || !cur.refresh() || !cur.isVisibleToUser() || !structuredMove(r2, cur, k, false)) afterScroll(k);
                });
                return true;
            }
            return false;   // top/bottom of the page: let the fallback reach the bars
        }

        // Left/Right inside the row, ordered by position on screen. Use every tappable thing in the row
        // (Continue Watching / Top 10 nest their posters differently) but only same-size ones, so the
        // small buttons inside a card (info, "...") are skipped.
        AccessibilityNodeInfo list = rowScroller(c, row);
        if (list == null) list = findScrollableIn(row, 0);
        if (list == null) list = actionScroller(c, row);
        log("S row list=" + (list == null ? "none" : bounds(list).toString()));
        if (list == null) dumpOnce(row, c);
        final boolean right = key == KeyEvent.KEYCODE_DPAD_RIGHT;
        AccessibilityNodeInfo next = null;
        for (AccessibilityNodeInfo it : targets(row)) {
            Rect r = bounds(it);
            if (r.isEmpty() || r.equals(a) || !sameRow(a, r)) continue;
            if (r.width() * 2 < a.width() && r.height() * 10 < a.height() * 8) continue;   // a small button inside a card
            if (right ? r.left <= a.left + a.width() / 3 : r.right >= a.right - a.width() / 3) continue;
            if (next == null || (right ? r.left < bounds(next).left : r.right > bounds(next).right)) next = it;
        }
        boolean cut = next == null || clipped(bounds(next)) || bounds(next).width() < a.width() * 9 / 10;
        log("S item " + (right ? "right " : "left ") + (next == null ? "none" : bounds(next).toString()) + " cut=" + cut);
        if (!cut) { select(next, false); return true; }
        // Next poster is cut off (or not loaded): scroll ONLY this row sideways, wait, then land on it.
        if (list != null && mayScroll && tryAction(list,
                right ? AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT : AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT,
                right ? AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)) {
            final AccessibilityNodeInfo rowList = list, want = next, was = c;
            waitStable(() -> {
                if (want != null && want.refresh() && want.isVisibleToUser() && bounds(want).width() > a.width() / 3) {
                    log("S after row scroll -> wanted " + bounds(want));
                    select(want, false);
                    return;
                }
                // The poster we were on is still showing (row moved a little): take the one right next to it.
                if (was.refresh() && was.isVisibleToUser() && !bounds(was).isEmpty()) {
                    Rect w = bounds(was);
                    AccessibilityNodeInfo nb = null;
                    for (AccessibilityNodeInfo it : mainItems(targets(rowList))) {
                        Rect r = bounds(it);
                        if (r.equals(w) || !sameRow(w, r)) continue;
                        if (right ? r.left <= w.left + w.width() / 3 : r.right >= w.right - w.width() / 3) continue;
                        if (nb == null || (right ? r.left < bounds(nb).left : r.right > bounds(nb).right)) nb = it;
                    }
                    if (nb != null) { log("S after row scroll -> next to previous " + bounds(nb)); select(nb, false); return; }
                }
                pickAfterRowScroll(rowList, right);
            });
            return true;
        }
        if (next != null) { select(next, false); return true; }
        // No scroll handle (Top 10 row): Netflix keeps the off-screen posters in the row, just not shown.
        // Bring the next one in by asking Netflix to show it, then land on it.
        if (mayScroll) {
            AccessibilityNodeInfo hidden = offscreenNeighbour(row, c, right);
            log("S offscreen neighbour " + (hidden == null ? "none" : "found"));
            if (hidden != null && tryShow(hidden)) {
                final AccessibilityNodeInfo want = hidden;
                final AccessibilityNodeInfo theRow = row;
                waitStable(() -> {
                    if (want.refresh() && want.isVisibleToUser() && !bounds(want).isEmpty()) {
                        log("S offscreen -> " + bounds(want));
                        select(want, false);
                    } else pickAfterRowScroll(theRow, right);
                });
                return true;
            }
        }
        return true;   // first/last poster of the row: stay put
    }

    /** The next same-size tappable after (or before) c in the row's tree order, including ones not on screen. */
    AccessibilityNodeInfo offscreenNeighbour(AccessibilityNodeInfo row, AccessibilityNodeInfo c, boolean right) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        allClickables(row, all, 0);
        Rect a = bounds(c);
        int idx = -1;
        for (int i = 0; i < all.size(); i++) if (all.get(i).equals(c) || bounds(all.get(i)).equals(a)) { idx = i; break; }
        if (idx < 0) return null;
        for (int i = idx + (right ? 1 : -1); i >= 0 && i < all.size(); i += right ? 1 : -1) {
            AccessibilityNodeInfo n = all.get(i);
            Rect r = bounds(n);
            if (n.isVisibleToUser() && !r.isEmpty()) {
                if (!sameRow(a, r)) continue;     // a small button inside a card
                if (right ? r.left <= a.left : r.right >= a.right) continue;
            }
            return n;
        }
        return null;
    }

    static void allClickables(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 40) return;
        if (n.isClickable() && n.isEnabled()) out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) allClickables(n.getChild(i), out, depth + 1);
    }

    /** After a row scrolled sideways: first poster at the leading edge that is mostly on screen. */
    void pickAfterRowScroll(AccessibilityNodeInfo list, boolean right) {
        list.refresh();
        List<AccessibilityNodeInfo> items = new ArrayList<>();
        int maxW = 0, maxH = 0;
        for (AccessibilityNodeInfo n : targets(list)) { maxH = Math.max(maxH, bounds(n).height()); }
        for (AccessibilityNodeInfo n : targets(list)) {
            if (bounds(n).height() * 10 >= maxH * 8) { items.add(n); maxW = Math.max(maxW, bounds(n).width()); }   // skip small buttons
        }
        AccessibilityNodeInfo best = null;
        for (AccessibilityNodeInfo n : items) {
            Rect r = bounds(n);
            if (r.width() * 3 < maxW) continue;   // less than a third on screen
            if (best == null || (right ? r.left < bounds(best).left : r.right > bounds(best).right)) best = n;
        }
        if (best != null) best = cardOf(best, list);
        log("S after row scroll " + (best == null ? "none" : bounds(best).toString()));
        if (best != null) select(best, false);
    }

    /** Nearest ancestor (up to the row) that accepts a sideways scroll action, even if not flagged scrollable. */
    static AccessibilityNodeInfo actionScroller(AccessibilityNodeInfo n, AccessibilityNodeInfo row) {
        for (AccessibilityNodeInfo p = n.getParent(); p != null; p = p.getParent()) {
            List<AccessibilityNodeInfo.AccessibilityAction> acts = p.getActionList();
            if (acts.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
                || acts.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT)
                || acts.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)) return p;
            if (p.equals(row)) return null;
        }
        return null;
    }

    private final java.util.Set<String> dumped = new java.util.HashSet<>();

    /** Writes the layout of an unusual row to the log once, so it can be handled properly. */
    void dumpOnce(AccessibilityNodeInfo row, AccessibilityNodeInfo c) {
        String key = bounds(row).toString();
        if (!dumped.add(key)) return;
        log("DUMP row " + key + " ----");
        StringBuilder chain = new StringBuilder();
        for (AccessibilityNodeInfo p = c; p != null && !p.equals(row); p = p.getParent()) chain.append(describe(p)).append(" <- ");
        log("DUMP chain: " + chain);
        dumpTree(row, 0, new int[]{0});
        log("DUMP end ----");
    }

    void dumpTree(AccessibilityNodeInfo n, int depth, int[] count) {
        if (n == null || depth > 14 || count[0]++ > 250) return;
        StringBuilder ind = new StringBuilder();
        for (int i = 0; i < depth; i++) ind.append("  ");
        log("DUMP " + ind + describe(n));
        for (int i = 0; i < n.getChildCount(); i++) dumpTree(n.getChild(i), depth + 1, count);
    }

    static String describe(AccessibilityNodeInfo n) {
        Rect r = new Rect(); n.getBoundsInScreen(r);
        String cls = n.getClassName() == null ? "?" : n.getClassName().toString();
        cls = cls.substring(cls.lastIndexOf('.') + 1);
        StringBuilder acts = new StringBuilder();
        for (AccessibilityNodeInfo.AccessibilityAction a : n.getActionList()) {
            int id = a.getId();
            if (id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) acts.append("sF ");
            else if (id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) acts.append("sB ");
            else if (id == android.R.id.accessibilityActionScrollRight) acts.append("sR ");
            else if (id == android.R.id.accessibilityActionScrollLeft) acts.append("sL ");
            else if (id == android.R.id.accessibilityActionShowOnScreen) acts.append("show ");
            else if (id == android.R.id.accessibilityActionScrollToPosition) acts.append("toPos ");
        }
        String id = n.getViewIdResourceName() == null ? "" : n.getViewIdResourceName().replace("com.netflix.mediaclient:id/", "#");
        CharSequence d = n.getContentDescription();
        CharSequence tx = n.getText();
        String txs = tx == null ? "" : " text=\"" + tx.toString().replace('\n', ' ').substring(0, Math.min(50, tx.length())) + "\"";
        return cls + " " + id + " " + r + txs + (n.isClickable() ? " CLICK" : "") + (n.isScrollable() ? " SCROLL" : "")
            + (n.isVisibleToUser() ? "" : " hidden") + " kids=" + n.getChildCount()
            + (acts.length() > 0 ? " [" + acts.toString().trim() + "]" : "")
            + (n.getCollectionInfo() != null ? " coll=" + n.getCollectionInfo().getRowCount() + "x" + n.getCollectionInfo().getColumnCount() : "")
            + (d != null ? " \"" + d.toString().replace('\n', ' ').substring(0, Math.min(30, d.length())) + "\"" : "");
    }

    static AccessibilityNodeInfo findScrollableIn(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 12) return null;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo ch = n.getChild(i);
            if (ch == null) continue;
            if (ch.isScrollable()) return ch;
            AccessibilityNodeInfo f = findScrollableIn(ch, depth + 1);
            if (f != null) return f;
        }
        return null;
    }

    boolean tryShow(AccessibilityNodeInfo n) {
        try { return n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId()); } catch (Exception e) { return false; }
    }

    boolean rowFullyShown(AccessibilityNodeInfo row) {
        Rect r = bounds(row);
        int h = screenH();
        return !r.isEmpty() && r.top > 2 && r.bottom < h * 0.89f;   // below that is under Netflix's tab bar
    }

    /** Whole cards only: drops the small buttons Netflix puts inside cards ("Show details", "More options"). */
    static List<AccessibilityNodeInfo> mainItems(List<AccessibilityNodeInfo> items) {
        int maxH = 0;
        for (AccessibilityNodeInfo n : items) maxH = Math.max(maxH, bounds(n).height());
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        for (AccessibilityNodeInfo n : items) if (bounds(n).height() * 10 >= maxH * 6) out.add(n);
        return out;
    }

    /** If n is a small button inside a card, use the card itself. */
    AccessibilityNodeInfo cardOf(AccessibilityNodeInfo n, AccessibilityNodeInfo row) {
        // Only climb within the card: stop at the row, at any list, or after a few levels,
        // and only accept a card-sized parent (never a page-sized container).
        AccessibilityNodeInfo best = n;
        long maxArea = (long) screenW() * screenH() / 5;
        int depth = 0;
        for (AccessibilityNodeInfo p = n.getParent(); p != null && depth < 4; p = p.getParent(), depth++) {
            if ((row != null && p.equals(row)) || p.isScrollable()) break;
            Rect r = bounds(p);
            if (p.isClickable() && r.height() > bounds(best).height() && (long) r.width() * r.height() <= maxArea
                    && r.contains(bounds(n))) best = p;
        }
        return best;
    }

    AccessibilityNodeInfo nearestX(List<AccessibilityNodeInfo> items, int x) {
        items = mainItems(items);
        AccessibilityNodeInfo best = null; long bestD = Long.MAX_VALUE;
        for (AccessibilityNodeInfo n : items) {
            long d = Math.abs(bounds(n).centerX() - x);
            if (d < bestD) { bestD = d; best = n; }
        }
        return best;
    }

    static boolean hasRect(List<AccessibilityNodeInfo> list, Rect r) {
        for (AccessibilityNodeInfo n : list) if (bounds(n).equals(r)) return true;
        return false;
    }

    /** Nearest target from `a` in the arrow's direction, or null. */
    AccessibilityNodeInfo pick(List<AccessibilityNodeInfo> t, Rect a, int key) {
        AccessibilityNodeInfo best = null; long bestS = Long.MAX_VALUE;
        for (AccessibilityNodeInfo n : t) {
            Rect b = bounds(n);
            if (b.equals(a)) continue;
            long primary, secondary;
            switch (key) {
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (b.centerY() <= a.centerY() + 4 || b.top < a.top + a.height() / 3) continue;
                    primary = Math.max(0, b.top - a.bottom); secondary = Math.abs(b.centerX() - a.centerX()); break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (b.centerY() >= a.centerY() - 4 || b.bottom > a.bottom - a.height() / 3) continue;
                    primary = Math.max(0, a.top - b.bottom); secondary = Math.abs(b.centerX() - a.centerX()); break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (b.centerX() <= a.centerX() + 4 || b.left < a.left + a.width() / 3) continue;
                    if (!sameRow(a, b)) continue;   // stay in the same row
                    primary = Math.max(0, b.left - a.right); secondary = Math.abs(b.centerY() - a.centerY()); break;
                default: // LEFT
                    if (b.centerX() >= a.centerX() - 4 || b.right > a.right - a.width() / 3) continue;
                    if (!sameRow(a, b)) continue;
                    primary = Math.max(0, a.left - b.right); secondary = Math.abs(b.centerY() - a.centerY()); break;
            }
            long s = primary + secondary * 3;
            if (s < bestS) { bestS = s; best = n; }
        }
        return best;
    }

    boolean scrollPage(AccessibilityNodeInfo page, int key) {
        boolean down = key == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean ok = tryAction(page,
            down ? AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN : AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP,
            down ? AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        log("scroll page " + (down ? "down" : "up") + " ok=" + ok);
        return ok;
    }

    boolean clipped(Rect r) {
        int h = screenH(), w = screenW();
        return r.bottom > h - 2 || r.top < 2 || r.right > w - 2 || r.left < 2;
    }

    /** Biggest scrollable thing on screen (Netflix's page list). */
    AccessibilityNodeInfo pageScroller(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo[] best = { null };
        int[] bestH = { 0 };
        findScroller(root, best, bestH, 0);
        return best[0];
    }

    void findScroller(AccessibilityNodeInfo n, AccessibilityNodeInfo[] best, int[] bestH, int depth) {
        if (n == null || depth > 60 || !n.isVisibleToUser()) return;
        if (n.isScrollable()) {
            Rect r = bounds(n);
            if (r.height() >= screenH() / 2 && r.height() > bestH[0]) { bestH[0] = r.height(); best[0] = n; }
        }
        for (int i = 0; i < n.getChildCount(); i++) findScroller(n.getChild(i), best, bestH, depth + 1);
    }

    /** The ancestor of n (or n) whose parent is `container` — i.e. n's row in the page. */
    static AccessibilityNodeInfo childOf(AccessibilityNodeInfo n, AccessibilityNodeInfo container) {
        AccessibilityNodeInfo x = n;
        for (int i = 0; x != null && i < 60; i++) {
            AccessibilityNodeInfo p = x.getParent();
            if (p == null) return null;
            if (p.equals(container)) return x;
            x = p;
        }
        return null;
    }

    static boolean tryAction(AccessibilityNodeInfo n, AccessibilityNodeInfo.AccessibilityAction... acts) {
        if (n == null) return false;
        List<AccessibilityNodeInfo.AccessibilityAction> has = n.getActionList();
        for (AccessibilityNodeInfo.AccessibilityAction a : acts) {
            if (a == null || !has.contains(a)) continue;
            try { if (n.performAction(a.getId())) return true; } catch (Exception ignored) { }
        }
        return false;
    }

    /** Nothing further that way on screen: scroll the page (Up/Down) or the row (Left/Right). */
    void scrollFor(AccessibilityNodeInfo root, AccessibilityNodeInfo c, int key) {
        boolean vertical = key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_UP;
        boolean fwd = key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_RIGHT;
        AccessibilityNodeInfo target = null;
        if (vertical) target = pageScroller(root);
        else if (c != null) {
            for (AccessibilityNodeInfo p = c.getParent(); p != null; p = p.getParent()) if (p.isScrollable()) { target = p; break; }
        }
        AccessibilityNodeInfo.AccessibilityAction dir;
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_DOWN: dir = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN; break;
            case KeyEvent.KEYCODE_DPAD_UP: dir = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: dir = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT; break;
            default: dir = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT; break;
        }
        boolean ok = tryAction(target, dir, fwd ? AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD
            : AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        log("scroll " + KeyEvent.keyCodeToString(key) + " target=" + (target == null ? "none" : bounds(target).toString()) + " ok=" + ok);
        if (!ok) { swipe(key, curRect); return; }
        final int k = key;
        waitStable(() -> afterScroll(k));
    }

    /** After a page scroll, highlight the first new thing in that direction. */
    void afterScroll(int key) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null || !NETFLIX.contentEquals(root.getPackageName())) return;
        if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            // The row moved a whole screen: the next item is the first full-size one at that edge.
            List<AccessibilityNodeInfo> all = targets(root);
            int w = screenW();
            boolean right = key == KeyEvent.KEYCODE_DPAD_RIGHT;
            List<AccessibilityNodeInfo> row = new ArrayList<>();
            int maxW = 0;
            for (AccessibilityNodeInfo n : all) {
                Rect r = bounds(n);
                if (curRect != null && !sameRow(curRect, r)) continue;
                row.add(n);
                maxW = Math.max(maxW, r.width());
            }
            AccessibilityNodeInfo best = null;
            for (AccessibilityNodeInfo n : row) {
                Rect r = bounds(n);
                if (r.width() < maxW * 9 / 10 || r.left <= 2 || r.right >= w - 2) continue;   // cut off at an edge
                if (best == null || (right ? r.left < bounds(best).left : r.right > bounds(best).right)) best = n;
            }
            log("after row scroll best=" + (best == null ? "none" : bounds(best).toString()));
            if (best != null) select(best); else move(root, key, false);
            return;
        }
        // After a page scroll: the first row now showing in that direction, near the same column.
        AccessibilityNodeInfo page = pageScroller(root);
        List<AccessibilityNodeInfo> t = page != null ? targets(page) : targets(root);
        if (t.isEmpty()) return;
        Rect pb = page != null ? bounds(page) : new Rect(0, 0, screenW(), screenH());
        int x = curRect == null ? 0 : curRect.centerX();
        boolean down = key == KeyEvent.KEYCODE_DPAD_DOWN;
        AccessibilityNodeInfo best = null; long bestS = Long.MAX_VALUE;
        int h = screenH();
        for (AccessibilityNodeInfo n : t) {
            Rect r = bounds(n);
            if (r.top > h * 0.88f || r.bottom < h * 0.12f) continue;                 // bars
            // node bounds come back already clipped, so "touching the page edge" = cut off
            if (r.top <= Math.max(pb.top, 0) + 2 || r.bottom >= Math.min(pb.bottom, h) - 2) continue;
            long edge = down ? Math.abs(Math.max(r.top, pb.top) - pb.top) : Math.abs(pb.bottom - Math.min(r.bottom, pb.bottom));
            long s = edge * 4 + Math.abs(r.centerX() - x);
            if (s < bestS) { bestS = s; best = n; }
        }
        if (best != null) select(best);
    }

    /** The biggest scrolling container holding this node (the page, not a row), or null. */
    AccessibilityNodeInfo scroller(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo best = null; int bestH = 0;
        AccessibilityNodeInfo p = n.getParent();
        Rect screen = new Rect(0, 0, screenW(), screenH());
        for (int i = 0; p != null && i < 40; i++, p = p.getParent()) {
            if (!p.isScrollable()) continue;
            Rect r = bounds(p);
            if (!r.intersect(screen) || r.height() < screen.height() / 3) continue;
            if (r.height() > bestH) { bestH = r.height(); best = p; }
        }
        return best;
    }

    static boolean isInside(AccessibilityNodeInfo n, AccessibilityNodeInfo ancestor) {
        AccessibilityNodeInfo p = n.getParent();
        for (int i = 0; p != null && i < 60; i++, p = p.getParent()) if (p.equals(ancestor)) return true;
        return false;
    }

    /** Slow one-row drag inside the list (held at the end so it doesn't fling), then re-pick. */
    void drag(int key, Rect from, Rect sb) {
        float x = sb.exactCenterX();
        int dist = Math.max(150, Math.min((int) (from.height() * 1.15f), sb.height() * 6 / 10));
        float y1, y2;
        if (key == KeyEvent.KEYCODE_DPAD_DOWN) { y1 = Math.min(sb.bottom - 20, from.bottom); y2 = Math.max(sb.top + 10, y1 - dist); }
        else { y1 = Math.max(sb.top + 20, from.top); y2 = Math.min(sb.bottom - 10, y1 + dist); }
        Path p = new Path(); p.moveTo(x, y1); p.lineTo(x, y2);
        Path hold = new Path(); hold.moveTo(x, y2); hold.lineTo(x, y2 + (y2 > y1 ? 1 : -1));
        final int k = key;
        try {
            GestureDescription.StrokeDescription s1 = new GestureDescription.StrokeDescription(p, 0, 450, true);
            GestureDescription.StrokeDescription s2 = s1.continueStroke(hold, 450, 150, false);
            boolean sent = dispatchGesture(new GestureDescription.Builder().addStroke(s1).build(), new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription g) {
                    dispatchGesture(new GestureDescription.Builder().addStroke(s2).build(), new GestureResultCallback() {
                        @Override public void onCompleted(GestureDescription g2) { ui.postDelayed(() -> retry(k), 250); }
                        @Override public void onCancelled(GestureDescription g2) { ui.postDelayed(() -> retry(k), 250); }
                    }, ui);
                }
                @Override public void onCancelled(GestureDescription g) { log("drag cancelled"); retry(k); }
            }, ui);
            log("drag sent=" + sent + " " + y1 + "->" + y2);
        } catch (Exception ex) { log("drag error " + ex); swipe(key, from); }
    }

    void retry(int key) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && root.getPackageName() != null && NETFLIX.contentEquals(root.getPackageName())) move(root, key, false);
    }

    void select(AccessibilityNodeInfo n) { select(n, false); }

    /** Highlight n. If the page is about to scroll it into view, keep the box hidden until it settles. */
    void select(AccessibilityNodeInfo n, boolean willScroll) {
        if (n == null) return;
        n = cardOf(n, null);   // never a small "Show details" / "More options" button inside a card
        cur = n;
        curKey = keyOf(n);
        curRect = bounds(n);
        log("SEL " + curRect + " \"" + (curKey.length() > 40 ? curKey.substring(0, 40) : curKey) + "\"");
        settling = willScroll;
        settleStart = SystemClock.uptimeMillis();
        stableTicks = 0;
        showBox(curRect, !willScroll);
        ui.removeCallbacks(tracker);
        ui.postDelayed(tracker, willScroll ? 90 : 150);
    }

    private boolean settling = false;
    private long settleStart = 0;
    private int stableTicks = 0;

    /** Keeps the highlight on its node while lists scroll; hides it when the node goes away. */
    private final Runnable tracker = new Runnable() {
        @Override public void run() {
            if (box == null || cur == null) return;
            if (busy) { ui.postDelayed(this, 150); return; }   // waiting for a scroll to finish
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || root.getPackageName() == null || !NETFLIX.contentEquals(root.getPackageName())) { clearSelection(); return; }
            if (cur.refresh() && cur.isVisibleToUser() && !curKey.isEmpty() && !curKey.equals(keyOf(cur))) {
                // Netflix reused this view for another title: find our card again by its label.
                AccessibilityNodeInfo same = findByKey(targets(root));
                log("recycled view; refound=" + (same != null));
                if (same != null) { cur = same; }
                else { AccessibilityNodeInfo n = current(targets(root)); if (n == null) { hideBox(); return; } select(n); return; }
            }
            if (cur.refresh() && cur.isVisibleToUser()) {
                Rect r = bounds(cur);
                if (!r.equals(curRect)) { curRect = r; stableTicks = 0; showBox(r, !settling); }
                else stableTicks++;
                if (settling && (stableTicks >= 2 || SystemClock.uptimeMillis() - settleStart > 1500)) {
                    settling = false;
                    showBox(curRect, true);
                }
            } else if (settling && SystemClock.uptimeMillis() - settleStart < 1500) {
                // still scrolling into view: wait for it
            } else if (inPlayer) {
                hideBox(); cur = null;   // player controls faded out
                return;
            } else {
                AccessibilityNodeInfo n = current(targets(root));
                if (n == null) { hideBox(); return; }
                cur = n; curRect = bounds(n); settling = false; showBox(curRect, true);
            }
            ui.postDelayed(this, settling || stableTicks < 4 ? 90 : 500);
        }
    };

    void refreshLater() {
        if (box == null) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null || !NETFLIX.contentEquals(root.getPackageName())) { clearSelection(); return; }
        AccessibilityNodeInfo n = current(targets(root));
        if (n == null) { hideBox(); return; }
        select(n);
    }

    void press(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> t = targets(root);
        if (inPlayer && t.size() < 2) { playerKey(KeyEvent.KEYCODE_DPAD_CENTER); return; }
        // The highlighted card itself if it's still there (same title); only otherwise search again.
        AccessibilityNodeInfo c = box == null ? null : (curIntact() ? cur : current(t));
        if (c != null && c != cur) { select(c); log("press: highlight moved to the card it will open"); }
        if (c == null) {
            if (t.isEmpty()) tap(screenW() / 2f, screenH() / 2f);
            else select(firstTarget(t));
            return;
        }
        Rect r = bounds(c);
        Rect vis = new Rect(r);
        vis.intersect(new Rect(0, 0, screenW(), screenH()));
        boolean clicked = false;
        try { clicked = c.isClickable() && c.performAction(AccessibilityNodeInfo.ACTION_CLICK); } catch (Exception ignored) { }
        log("press " + vis + " click=" + clicked + " \"" + keyOf(c) + "\" cls=" + c.getClassName());
        if (!clicked) tap(vis.exactCenterX(), vis.exactCenterY());
        afterScreenChange(250);
    }

    /**
     * OK or Back may open or close a screen. Hold presses while the new screen loads,
     * then put the box back on the same card if it's still there, otherwise on the nearest one.
     */
    void afterScreenChange(long delay) {
        busy = true;
        busyStart = SystemClock.uptimeMillis();
        if (box != null) box.setAlpha(0f);
        ui.postDelayed(() -> {
            if (!busy) return;
            waitStable(() -> {
                AccessibilityNodeInfo r = getRootInActiveWindow();
                if (r == null || r.getPackageName() == null || !NETFLIX.contentEquals(r.getPackageName())) { clearSelection(); return; }
                List<AccessibilityNodeInfo> t = targets(r);
                if (t.isEmpty()) { hideBox(); return; }
                AccessibilityNodeInfo n = current(t);
                if (n == null) n = firstTarget(t);
                select(n);
            });
        }, delay);
    }

    /** Player with controls hidden: Left/Right = double-tap to skip 10s, anything else shows controls. */
    void playerKey(int key) {
        int w = screenW(), h = screenH();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo surface = root == null ? null : biggestClickable(root, null, 0);
        if (surface != null) {
            try { if (surface.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { log("player: click surface"); return; } } catch (Exception ignored) { }
        }
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) doubleTap(w * 0.2f, h / 2f);
        else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) doubleTap(w * 0.8f, h / 2f);
        else tap(w / 2f, h / 2f);
    }

    AccessibilityNodeInfo biggestClickable(AccessibilityNodeInfo n, AccessibilityNodeInfo best, int depth) {
        if (n == null || depth > 60 || !n.isVisibleToUser()) return best;
        if (n.isClickable()) {
            Rect r = bounds(n);
            if (best == null || (long) r.width() * r.height() > (long) bounds(best).width() * bounds(best).height()) best = n;
        }
        for (int i = 0; i < n.getChildCount(); i++) best = biggestClickable(n.getChild(i), best, depth + 1);
        return best;
    }

    /** Arrow pressed at the edge of what's on screen: drag the page so more comes into view. */
    void swipe(int key, Rect from) {
        int w = screenW(), h = screenH();
        float x = from == null ? w / 2f : Math.max(40, Math.min(w - 40, from.exactCenterX()));
        float y = from == null ? h / 2f : Math.max(40, Math.min(h - 40, from.exactCenterY()));
        float x1 = x, y1 = y, x2 = x, y2 = y;
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_DOWN: x1 = x2 = w / 2f; y1 = h * 0.70f; y2 = h * 0.35f; break;
            case KeyEvent.KEYCODE_DPAD_UP:   x1 = x2 = w / 2f; y1 = h * 0.35f; y2 = h * 0.70f; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: x1 = w * 0.75f; x2 = w * 0.35f; break;
            case KeyEvent.KEYCODE_DPAD_LEFT:  x1 = w * 0.35f; x2 = w * 0.75f; break;
            default: return;
        }
        Path p = new Path();
        p.moveTo(x1, y1); p.lineTo(x2, y2);
        final int k = key;
        gesture(new GestureDescription.StrokeDescription(p, 0, 320), () -> ui.postDelayed(() -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && root.getPackageName() != null && NETFLIX.contentEquals(root.getPackageName())) move(root, k, false);
        }, 450));
    }

    void tap(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        gesture(new GestureDescription.StrokeDescription(p, 0, 50), null);
    }

    void doubleTap(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        gesture(new GestureDescription.StrokeDescription(p, 0, 40),
            () -> ui.postDelayed(() -> tap(x, y), 70));
    }

    void gesture(GestureDescription.StrokeDescription s, Runnable done) {
        try {
            boolean sent = dispatchGesture(new GestureDescription.Builder().addStroke(s).build(), new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription g) { log("gesture done"); if (done != null) done.run(); }
                @Override public void onCancelled(GestureDescription g) { log("gesture cancelled"); }
            }, ui);
            log("gesture sent=" + sent);
        } catch (Exception ex) { log("gesture error " + ex); }
    }

    // ---------- highlight overlay ----------
    // One full-screen, untouchable overlay window; the box inside it glides between items.

    private android.widget.FrameLayout layer;

    void showBox(Rect r) { showBox(r, true); }

    void showBox(Rect r, boolean visible) {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) return;
        int pad = 6;
        try {
            if (layer == null) {
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.START;
                layer = new android.widget.FrameLayout(this);
                box = new View(this);
                GradientDrawable d = new GradientDrawable();
                d.setColor(0x22FFFFFF);
                d.setStroke(5, 0xFFFFFFFF);
                d.setCornerRadius(14);
                box.setBackground(d);
                box.setAlpha(0f);
                layer.addView(box, new android.widget.FrameLayout.LayoutParams(r.width() + pad * 2, r.height() + pad * 2));
                wm.addView(layer, lp);
            }
            android.view.ViewGroup.LayoutParams blp = box.getLayoutParams();
            if (blp.width != r.width() + pad * 2 || blp.height != r.height() + pad * 2) {
                blp.width = r.width() + pad * 2;
                blp.height = r.height() + pad * 2;
                box.setLayoutParams(blp);
            }
            float x = r.left - pad, y = r.top - pad;
            if (!visible) {
                box.animate().cancel();
                box.setAlpha(0f); box.setX(x); box.setY(y);
            } else if (box.getAlpha() < 0.5f) {
                box.animate().cancel();
                box.setX(x); box.setY(y);
                box.animate().alpha(1f).setDuration(90).start();
            } else {
                box.animate().x(x).y(y).alpha(1f).setDuration(110).start();
            }
        } catch (Exception ex) { log("overlay error " + ex); layer = null; box = null; }
    }

    void hideBox() {
        ui.removeCallbacks(tracker);
        settling = false;
        if (layer == null) { box = null; return; }
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        try { if (wm != null) wm.removeView(layer); } catch (Exception ignored) { }
        layer = null;
        box = null;
    }

    void clearSelection() {
        hideBox();
        cur = null;
        curRect = null;
    }
}
