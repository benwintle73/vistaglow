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

    static void log(String m) { android.util.Log.w("VistaGlowRemote", m); }

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

    void handleNetflixKey(KeyEvent e, AccessibilityNodeInfo root) {
        int k = e.getKeyCode();
        log("key " + KeyEvent.keyCodeToString(k) + " action=" + e.getAction() + " repeat=" + e.getRepeatCount());
        if (e.getAction() != KeyEvent.ACTION_DOWN) return;
        if (busy) {
            // Netflix is still scrolling from the last press: remember this one and do it once it stops.
            if (e.getRepeatCount() == 0 || queuedKey == 0) queuedKey = k;
            log("busy: queued " + KeyEvent.keyCodeToString(k));
            return;
        }
        if (isOkKey(k)) {
            if (e.getRepeatCount() == 0) press(root);   // ignore auto-repeat
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (e.getRepeatCount() > 0 && now - lastMove < 140) return;   // held arrow: ~7 moves/sec
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
                int k = queuedKey; queuedKey = 0;
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
                if (it != null) { log("S row -> " + nb + " item " + bounds(it)); select(it, false); return true; }
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
                        if (!rowFullyShown(target) && bounds(it).bottom >= screenH() * 0.89f) {
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
            final AccessibilityNodeInfo rowList = list, want = next;
            waitStable(() -> {
                if (want != null && want.refresh() && want.isVisibleToUser() && bounds(want).width() > a.width() / 2) {
                    log("S after row scroll -> wanted " + bounds(want));
                    select(want, false);
                } else pickAfterRowScroll(rowList, right);
            });
            return true;
        }
        if (next != null) { select(next, false); return true; }
        return true;   // first/last poster of the row: stay put
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
            if (r.width() * 10 < maxW * 6) continue;   // mostly off screen
            if (best == null || (right ? r.left < bounds(best).left : r.right > bounds(best).right)) best = n;
        }
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

    AccessibilityNodeInfo nearestX(List<AccessibilityNodeInfo> items, int x) {
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
        cur = n;
        curRect = bounds(n);
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
