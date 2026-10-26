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
 *    While Netflix is in front, the arrows move a highlight between its buttons/posters and
 *    OK taps the highlighted one. In the player, with the controls hidden, OK shows them and
 *    Left/Right skip back/forward 10 seconds. Back is left to Netflix.
 */
public class HomeService extends AccessibilityService {

    static final String[] STOCK_HOMES = {
        "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx",
        "com.android.tv.launcher", "com.mediatek.wwtv.tvcenter", "com.android.launcher3"
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
        if (netflixEnabled() && isNavKey(k)) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && root.getPackageName() != null && NETFLIX.contentEquals(root.getPackageName())) {
                handleNetflixKey(e, root);
                return true;
            }
        }
        if (k == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) ui.postDelayed(this::refreshLater, 400);
        return false;
    }

    @Override public void onInterrupt() { }

    @Override public void onDestroy() { clearSelection(); super.onDestroy(); }

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

    static void log(String m) { android.util.Log.d("VistaGlowRemote", m); }

    int screenW() { DisplayMetrics m = getResources().getDisplayMetrics(); return m.widthPixels; }
    int screenH() { DisplayMetrics m = getResources().getDisplayMetrics(); return m.heightPixels; }

    void handleNetflixKey(KeyEvent e, AccessibilityNodeInfo root) {
        int k = e.getKeyCode();
        log("key " + KeyEvent.keyCodeToString(k) + " action=" + e.getAction() + " repeat=" + e.getRepeatCount());
        if (isOkKey(k)) {
            // act on the press (some remotes only deliver the down reliably); ignore auto-repeat
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) press(root);
            return;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return;
        long now = SystemClock.uptimeMillis();
        if (e.getRepeatCount() > 0 && now - lastMove < 140) return;   // held arrow: ~7 moves/sec
        lastMove = now;
        move(root, k, true);
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

    /** Re-finds the highlighted node on screen (it may have moved or been replaced). */
    AccessibilityNodeInfo current(List<AccessibilityNodeInfo> t) {
        if (cur != null && cur.refresh() && cur.isVisibleToUser()) {
            Rect r = bounds(cur);
            for (AccessibilityNodeInfo n : t) if (bounds(n).equals(r)) { curRect = r; return n; }
        }
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
        Rect a = bounds(c);
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
                    if (b.bottom <= a.top || b.top >= a.bottom) continue;   // stay in the same row
                    primary = Math.max(0, b.left - a.right); secondary = Math.abs(b.centerY() - a.centerY()); break;
                default: // LEFT
                    if (b.centerX() >= a.centerX() - 4 || b.right > a.right - a.width() / 3) continue;
                    if (b.bottom <= a.top || b.top >= a.bottom) continue;
                    primary = Math.max(0, a.left - b.right); secondary = Math.abs(b.centerY() - a.centerY()); break;
            }
            long s = primary + secondary * 3;
            if (s < bestS) { bestS = s; best = n; }
        }
        boolean vertical = key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_UP;
        if (vertical && scrollIfStuck) {
            // About to leave the scrolling page (e.g. onto Netflix's bottom tab bar or the top bar)?
            // Scroll the page a row first; only leave it once it can't scroll any further.
            AccessibilityNodeInfo sc = scroller(c);
            int h = screenH();
            boolean curInBar = a.centerY() > h * 0.85f || a.centerY() < h * 0.15f;
            boolean leaving = best == null
                || (sc != null && !isInside(best, sc))
                || (!curInBar && (bounds(best).centerY() > h * 0.85f || bounds(best).centerY() < h * 0.15f));
            log("vmove scroller=" + (sc == null ? "none" : bounds(sc).toString()) + " best="
                + (best == null ? "none" : bounds(best).toString()) + " leaving=" + leaving);
            if (leaving && !curInBar) {
                Rect sb = sc != null ? bounds(sc) : new Rect(0, 0, screenW(), h);
                sb.intersect(new Rect(0, (int) (h * 0.12f), screenW(), (int) (h * 0.85f)));
                drag(key, a, sb);
                return;
            }
        }
        if (best != null) { select(best); return; }
        if (scrollIfStuck) swipe(key, a);
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

    void select(AccessibilityNodeInfo n) {
        if (n == null) return;
        cur = n;
        curRect = bounds(n);
        Rect vis = new Rect(curRect);
        if (!vis.intersect(new Rect(0, 0, screenW(), screenH())) || vis.width() < curRect.width() || vis.height() < curRect.height()) {
            try { n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId()); } catch (Exception ignored) { }
        }
        showBox(curRect);
        ui.removeCallbacks(tracker);
        ui.postDelayed(tracker, 300);
    }

    /** Keeps the highlight on its node while lists scroll; hides it when the node goes away. */
    private final Runnable tracker = new Runnable() {
        @Override public void run() {
            if (box == null || cur == null) return;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || root.getPackageName() == null || !NETFLIX.contentEquals(root.getPackageName())) { clearSelection(); return; }
            if (cur.refresh() && cur.isVisibleToUser()) {
                Rect r = bounds(cur);
                if (!r.equals(curRect)) { curRect = r; showBox(r); }
            } else if (inPlayer) {
                hideBox(); cur = null;   // player controls faded out
                return;
            } else {
                AccessibilityNodeInfo n = current(targets(root));
                if (n == null) { hideBox(); return; }
                cur = n; curRect = bounds(n); showBox(curRect);
            }
            ui.postDelayed(this, 600);
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
        if (inPlayer && t.size() < 2) { tap(screenW() / 2f, screenH() / 2f); return; }
        AccessibilityNodeInfo c = box == null ? null : current(t);
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
        log("press " + vis + " click=" + clicked + " cls=" + c.getClassName());
        if (!clicked) tap(vis.exactCenterX(), vis.exactCenterY());
        ui.postDelayed(this::refreshLater, 700);
    }

    /** Player with controls hidden: Left/Right = double-tap to skip 10s, anything else shows controls. */
    void playerKey(int key) {
        int w = screenW(), h = screenH();
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) doubleTap(w * 0.2f, h / 2f);
        else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) doubleTap(w * 0.8f, h / 2f);
        else tap(w / 2f, h / 2f);
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

    void showBox(Rect r) {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) return;
        int pad = 6;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            r.width() + pad * 2, r.height() + pad * 2,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = r.left - pad;
        lp.y = r.top - pad;
        try {
            if (box == null) {
                box = new View(this);
                GradientDrawable d = new GradientDrawable();
                d.setColor(0x22FFFFFF);
                d.setStroke(5, 0xFFFFFFFF);
                d.setCornerRadius(14);
                box.setBackground(d);
                wm.addView(box, lp);
            } else {
                wm.updateViewLayout(box, lp);
            }
        } catch (Exception ignored) { box = null; }
    }

    void hideBox() {
        ui.removeCallbacks(tracker);
        if (box == null) return;
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        try { if (wm != null) wm.removeView(box); } catch (Exception ignored) { }
        box = null;
    }

    void clearSelection() {
        hideBox();
        cur = null;
        curRect = null;
    }
}
