package com.benwintle.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/** Makes the remote's Home button (and the stock home screen) open VistaGlow. */
public class HomeService extends AccessibilityService {

    static final String[] STOCK_HOMES = {
        "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx",
        "com.android.tv.launcher", "com.mediatek.wwtv.tvcenter", "com.android.launcher3"
    };

    private long lastLaunch = 0;

    boolean enabled() { return getSharedPreferences("home", MODE_PRIVATE).getBoolean("homeOverride", true); }

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
        if (!enabled() || e == null) return;
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && isStockHome(e.getPackageName())) {
            openVistaGlow();
        }
    }

    @Override protected boolean onKeyEvent(KeyEvent e) {
        if (!enabled() || e.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (e.getAction() == KeyEvent.ACTION_UP) openVistaGlow();
        return true;
    }

    @Override public void onInterrupt() { }
}
