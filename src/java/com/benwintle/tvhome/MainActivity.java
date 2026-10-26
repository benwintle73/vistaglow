package com.benwintle.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    static final String[] DEFAULT_FAVS = {
        "com.netflix.mediaclient", "com.google.android.youtube.tv", "au.com.stan.and",
        "com.disney.disneyplus", "com.amazon.amazonvideo.livingroom", "au.net.abc.iview",
        "au.com.ninenow.ctv", "com.apple.atve.androidtv.appletv", "com.wbd.stream",
        "com.plexapp.android", "com.spotify.tv.android"
    };

    static class App {
        String pkg, label;
        Drawable banner;
        boolean hasBanner;
        int color;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, App> apps = new LinkedHashMap<>();
    private final Map<String, Bitmap> colorCache = new HashMap<>();
    private List<String> favs = new ArrayList<>();
    private List<String> order = new ArrayList<>();
    private final Map<String, View[]> cards = new HashMap<>();
    private String movingPkg;
    private boolean swallowUp;
    private boolean okDownSeen = false;
    private long resumedAt = 0;
    static final String SETTINGS_PKG = "com.android.tv.settings";
    static final String VG_SETTINGS = "vistaglow.settings";   // the VistaGlow tile: opens VistaGlow's own settings
    private final java.util.Set<String> hidden = new java.util.HashSet<>();
    private TextView weather, ticker, battery;
    private ImageView wifi;
    private final WifiGlyph wifiGlyph = new WifiGlyph();
    private long newsAt = 0;
    private long weatherAt = 0;
    private SharedPreferences prefs;
    private boolean stale = true;
    private int tab = 0;

    private ScrollView scroll;
    private LinearLayout content;
    private TextView tabForYou, tabApps;
    private FrameLayout hero;
    private TextView heroTitle, heroSub, heroOpen;
    private ImageView heroImage;
    private int heroIndex = 0;
    private String heroPkg;

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { stale = true; if (hasWindowFocus()) refresh(); }
    };

    private final Runnable heroTick = new Runnable() {
        @Override public void run() {
            if (hero != null && !hasFocusInside(hero) && !favApps().isEmpty()) {
                heroIndex = (heroIndex + 1) % favApps().size();
                bindHero(favApps().get(heroIndex), true);
            }
            ui.postDelayed(this, 9000);
        }
    };

    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        String saved = prefs.getString("favs", null);
        favs = new ArrayList<>(saved == null ? Arrays.asList(DEFAULT_FAVS)
                : (saved.isEmpty() ? Collections.<String>emptyList() : Arrays.asList(saved.split(","))));

        hidden.addAll(prefs.getStringSet("hidden", Collections.<String>emptySet()));
        String so = prefs.getString("order", "");
        if (!so.isEmpty()) order = new ArrayList<>(Arrays.asList(so.split(",")));

        FrameLayout root = new FrameLayout(this);
        rootView = root;
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF15171C, 0xFF0B0C0F});
        root.setBackground(bg);

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setSmoothScrollingEnabled(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, 0, dp(48));
        col.setClipChildren(false);
        col.setClipToPadding(false);
        col.addView(buildTopBar());
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        col.addView(content);
        scroll.addView(col);
        root.addView(scroll);
        setContentView(root);
        buildSaver();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);
        Intent sticky = registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        showBattery(sticky);
        IntentFilter wf = new IntentFilter(android.net.wifi.WifiManager.RSSI_CHANGED_ACTION);
        wf.addAction(android.net.wifi.WifiManager.NETWORK_STATE_CHANGED_ACTION);
        wf.addAction(android.net.ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(wifiReceiver, wf);
        registerReceiver(installReceiver, new IntentFilter(ACTION_INSTALLED), Context.RECEIVER_NOT_EXPORTED);
        updateWifi();
        try {
            getContentResolver().registerContentObserver(Settings.Global.getUriFor(ANKER_CHARGING), false,
                new android.database.ContentObserver(ui) { @Override public void onChange(boolean self) { showBattery(null); } });
        } catch (Exception ignored) { }
        ui.postDelayed(batteryTick, 60_000);
    }

    @Override protected void onDestroy() { unregisterReceiver(pkgReceiver); unregisterReceiver(batteryReceiver); unregisterReceiver(wifiReceiver); unregisterReceiver(installReceiver); super.onDestroy(); }

    private final BroadcastReceiver wifiReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateWifi(); }
    };

    void updateWifi() {
        int bars = -1;
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) bars = 3;
            else if (nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                int rssi = wm.getConnectionInfo().getRssi();
                int lvl = wm.calculateSignalLevel(rssi), max = Math.max(1, wm.getMaxSignalLevel());
                bars = Math.max(1, Math.round(lvl * 3f / max));
            }
        } catch (Exception ignored) { }
        wifiGlyph.bars = bars;
        wifiGlyph.invalidateSelf();
        sWifiGlyph.bars = bars;
        sWifiGlyph.invalidateSelf();
    }

    static class WifiGlyph extends Drawable {
        int bars = -1;
        final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            float w = b.width(), h = b.height(), cx = b.left + w / 2f, cy = b.top + h * 0.95f;
            float sw = h * 0.14f;
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(sw);
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            for (int k = 1; k <= 3; k++) {
                float r = h * 0.28f * k;
                p.setColor(bars >= k ? 0xFFE8EAED : 0x55E8EAED);
                c.drawArc(new android.graphics.RectF(cx - r, cy - r, cx + r, cy + r), 225, 90, false, p);
            }
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setColor(bars >= 0 ? 0xFFE8EAED : 0x55E8EAED);
            c.drawCircle(cx, cy - sw * 0.2f, sw * 0.7f, p);
            if (bars < 0) {
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setColor(0xFFF28B82);
                p.setStrokeWidth(sw * 0.9f);
                float x = b.right - h * 0.3f, y = b.top + h * 0.25f, d = h * 0.18f;
                c.drawLine(x - d, y - d, x + d, y + d, p);
                c.drawLine(x - d, y + d, x + d, y - d, p);
            }
        }
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { showBattery(i); }
    };

    static String readSys(String path) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(path))) {
            String l = r.readLine(); return l == null ? null : l.trim();
        } catch (Exception e) { return null; }
    }

    private final Runnable batteryTick = new Runnable() {
        @Override public void run() { showBattery(null); ui.postDelayed(this, 60_000); }
    };

    static final String ANKER_CHARGING = "Anker_Projector_ChargingStatus";

    void showBattery(Intent ignored) {
        if (battery == null) return;
        String v = null;
        try { v = Settings.Global.getString(getContentResolver(), ANKER_CHARGING); } catch (Exception e) { }
        if (v == null) { battery.setVisibility(View.GONE); return; }
        boolean plugged = !"0".equals(v.trim());
        battery.setText(plugged ? "\u26A1" : "\uD83D\uDD0B");
        if (sBattery != null) { sBattery.setText(battery.getText()); sBattery.setVisibility(View.VISIBLE); }
        battery.setVisibility(View.VISIBLE);
    }

    @Override protected void onResume() {
        super.onResume();
        resumedAt = android.os.SystemClock.uptimeMillis();
        okDownSeen = false;
        if (stale) refresh();
        if (System.currentTimeMillis() - weatherAt > 30 * 60 * 1000L) fetchWeather();
        if (ticker != null) ticker.setSelected(true);
        ui.removeCallbacks(heroTick);
        ui.postDelayed(heroTick, 9000);
        resetIdle();
        if (System.currentTimeMillis() - prefs.getLong("updAt", 0) > 12 * 3600_000L) ui.postDelayed(() -> checkUpdate(false), 20_000);
        if (!prefs.getBoolean("homeAsked", false) && !isDefaultHome() && !homeServiceOn()) ui.postDelayed(this::askHomeSetup, 1500);
    }

    @Override protected void onPause() {
        ui.removeCallbacks(heroTick); ui.removeCallbacks(idleTick);
        if (saverOn) { saverOn = false; ui.removeCallbacks(saverTick); saver.setVisibility(View.GONE); }
        super.onPause();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (i.getBooleanExtra("saver", false)) { showSaver(); return; }
        if (saverOn) hideSaver();
        if (Intent.ACTION_MAIN.equals(i.getAction()) && i.hasCategory(Intent.CATEGORY_HOME)) {
            if (tab != 0) { tab = 0; render(); }
            scroll.smoothScrollTo(0, 0);
            if (heroOpen != null) heroOpen.requestFocus();
        }
    }

    @Override public void onBackPressed() {
        if (tab != 0) { tab = 0; render(); return; }
        scroll.smoothScrollTo(0, 0);
        if (heroOpen != null) heroOpen.requestFocus();
    }

    // ---------- data ----------

    void refresh() {
        stale = false;
        loadApps();
        render();
    }

    void loadApps() {
        apps.clear();
        PackageManager pm = getPackageManager();
        List<ResolveInfo> all = new ArrayList<>();
        all.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
        all.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0));
        for (ResolveInfo ri : all) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || ai.packageName.equals(getPackageName()) || apps.containsKey(ai.packageName)) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.label = String.valueOf(ri.loadLabel(pm));
            Drawable ban = ai.loadBanner(pm);
            if (ban == null) ban = ai.applicationInfo.loadBanner(pm);
            a.hasBanner = ban != null;
            a.banner = ban != null ? ban : ri.loadIcon(pm);
            a.color = dominant(a.banner);
            apps.put(a.pkg, a);
        }
        App vg = new App();
        vg.pkg = VG_SETTINGS;
        vg.label = "VistaGlow";
        vg.banner = getDrawable(R.drawable.banner);
        vg.hasBanner = true;
        vg.color = dominant(vg.banner);
        apps.put(vg.pkg, vg);
        List<App> sorted = new ArrayList<>(apps.values());
        Collections.sort(sorted, (x, y) -> x.label.compareToIgnoreCase(y.label));
        apps.clear();
        for (App a : sorted) apps.put(a.pkg, a);
    }

    List<App> favApps() {
        List<App> out = new ArrayList<>();
        for (String p : favs) { App a = apps.get(p); if (a != null) out.add(a); }
        return out;
    }

    List<App> orderedApps() {
        List<String> out = new ArrayList<>();
        if (order.isEmpty()) { for (String p : favs) if (apps.containsKey(p)) out.add(p); }
        else for (String p : order) if (apps.containsKey(p) && !out.contains(p)) out.add(p);
        if (!out.contains(VG_SETTINGS)) out.add(0, VG_SETTINGS);   // VistaGlow tile starts first; it can be moved
        for (String p : apps.keySet()) if (!out.contains(p)) out.add(p);
        out.remove(SETTINGS_PKG);
        if (!out.equals(order)) { order = out; saveOrder(); }
        List<App> list = new ArrayList<>();
        for (String p : order) if (!hidden.contains(p) || VG_SETTINGS.equals(p)) list.add(apps.get(p));
        if (apps.containsKey(SETTINGS_PKG)) list.add(apps.get(SETTINGS_PKG));
        return list;
    }

    void saveOrder() { prefs.edit().putString("order", TextUtils.join(",", order)).apply(); }

    void startMove(String pkg) {
        movingPkg = pkg;
        styleMoving(pkg, true);
    }

    void styleMoving(String pkg, boolean on) {
        View[] v = cards.get(pkg);
        if (v == null) return;
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(15));
        ring.setStroke(dp(on ? 3 : 2), on ? 0xFF8AB4F8 : Color.WHITE);
        v[1].setBackground(ring);
        v[1].setVisibility(View.VISIBLE);
        TextView label = (TextView) v[2];
        label.setText(on ? "\u25C0 \u25B2 \u25BC \u25B6" : apps.get(pkg).label);
        label.setTextColor(on ? 0xFF8AB4F8 : 0xFFFFFFFF);
    }

    void moveBy(int delta) {
        int i = order.indexOf(movingPkg);
        if (i < 0) return;
        int t = Math.max(0, Math.min(order.size() - 1, i + delta));
        if (t == i) return;
        order.remove(i);
        order.add(t, movingPkg);
        saveOrder();
        render();
        View[] v = cards.get(movingPkg);
        if (v != null) { v[0].requestFocus(); styleMoving(movingPkg, true); }
    }

    void endMove() {
        String p = movingPkg;
        movingPkg = null;
        if (p != null) { styleMoving(p, false); View[] v = cards.get(p); if (v != null) v[0].requestFocus(); }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean okKey = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A;
        if (okKey) {
            // Ignore an OK release whose press happened in another app (e.g. the update installer).
            if (e.getAction() == KeyEvent.ACTION_DOWN) okDownSeen = true;
            else if (e.getAction() == KeyEvent.ACTION_UP) { if (!okDownSeen) return true; okDownSeen = false; }
        }
        if (saverOn) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) { hideSaver(); swallowAnyUp = true; }
            return true;
        }
        if (swallowAnyUp && e.getAction() == KeyEvent.ACTION_UP) { swallowAnyUp = false; return true; }
        resetIdle();
        boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_BUTTON_A;
        if (swallowUp && e.getAction() == KeyEvent.ACTION_UP && ok) { swallowUp = false; return true; }
        if (movingPkg == null) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_LEFT: moveBy(-1); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: moveBy(1); return true;
                case KeyEvent.KEYCODE_DPAD_UP: moveBy(-COLS); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: moveBy(COLS); return true;
                case KeyEvent.KEYCODE_BACK: endMove(); return true;
            }
            if (ok) { endMove(); swallowUp = true; return true; }
        }
        return true;
    }

    void saveFavs() { prefs.edit().putString("favs", TextUtils.join(",", favs)).apply(); }

    int dominant(Drawable d) {
        try {
            Bitmap bm = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            d.setBounds(0, 0, 16, 9);
            d.draw(c);
            long r = 0, g = 0, b = 0, n = 0;
            for (int x = 0; x < 16; x++) for (int y = 0; y < 9; y++) {
                int p = bm.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++;
            }
            bm.recycle();
            if (n == 0) return 0xFF2A3A55;
            float[] hsv = new float[3];
            Color.RGBToHSV((int) (r / n), (int) (g / n), (int) (b / n), hsv);
            hsv[1] = Math.min(1f, hsv[1] * 1.1f);
            hsv[2] = Math.max(0.28f, Math.min(0.55f, hsv[2]));
            return Color.HSVToColor(hsv);
        } catch (Exception e) { return 0xFF2A3A55; }
    }

    // ---------- UI ----------

    View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(56), dp(20), dp(56), dp(10));


        tabForYou = pill("For you");
        tabApps = pill("Apps");
        tabForYou.setOnClickListener(v -> { if (tab != 0) { tab = 0; render(); } });
        tabApps.setOnClickListener(v -> { if (tab != 1) { tab = 1; render(); } });

        ticker = new TextView(this);
        ticker.setSingleLine(true);
        ticker.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        ticker.setMarqueeRepeatLimit(-1);
        ticker.setHorizontallyScrolling(true);
        ticker.setTextColor(0xFFBDC1C6);
        ticker.setTextSize(17);
        ticker.setText("");
        ticker.setSelected(true);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.setMargins(0, 0, dp(16), 0);
        bar.addView(ticker, tl);



        wifi = new ImageView(this);
        wifi.setImageDrawable(wifiGlyph);
        LinearLayout.LayoutParams wfl = new LinearLayout.LayoutParams(dp(26), dp(20));
        wfl.setMargins(0, 0, dp(12), 0);
        bar.addView(wifi, wfl);

        battery = new TextView(this);
        battery.setTextColor(0xFFE8EAED);
        battery.setTextSize(19);
        battery.setVisibility(View.GONE);
        battery.setPadding(0, 0, 0, 0);
        bar.addView(battery);

        weather = pill("\u2026");
        weather.setTextSize(20);
        weather.setPadding(dp(10), dp(9), dp(12), dp(9));
        weather.setFocusable(false);   // display only: settings live on the VistaGlow tile
        weather.setClickable(false);
        bar.addView(weather);

        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEE d MMM");
        date.setFormat24Hour("EEE d MMM");
        date.setTextColor(0xFF9AA0A6);
        date.setTextSize(20);
        date.setPadding(dp(8), 0, 0, 0);
        bar.addView(date);

        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm a");
        clock.setFormat24Hour("h:mm a");
        clock.setTextColor(0xFFE8EAED);
        clock.setTextSize(20);
        clock.setPadding(dp(14), 0, 0, 0);
        bar.addView(clock);

        return bar;
    }

    TextView pill(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(16);
        t.setTextColor(0xFFBDC1C6);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(dp(20), dp(9), dp(20), dp(9));
        t.setFocusable(true);
        t.setClickable(true);
        t.setDefaultFocusHighlightEnabled(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(4), 0, dp(4), 0);
        t.setLayoutParams(lp);
        stylePill(t, false, false);
        t.setOnFocusChangeListener((v, has) -> stylePill(t, has, t == tabSelected()));
        return t;
    }

    TextView tabSelected() { return tab == 0 ? tabForYou : tabApps; }

    void stylePill(TextView t, boolean focused, boolean selected) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(24));
        if (focused) { g.setColor(0xFFE8EAED); t.setTextColor(0xFF202124); }
        else if (selected) { g.setColor(0x33FFFFFF); t.setTextColor(0xFFFFFFFF); }
        else { g.setColor(0x00000000); t.setTextColor(0xFFBDC1C6); }
        t.setBackground(g);
    }

    void render() {
        content.removeAllViews();
        cards.clear();
        hero = null; heroOpen = null;
        stylePill(tabForYou, tabForYou.hasFocus(), tab == 0);
        stylePill(tabApps, tabApps.hasFocus(), tab == 1);
        if (tab == 0) renderForYou(); else renderApps();
    }

    void renderForYou() {
        List<App> f = favApps();
        content.addView(buildHero());
        if (!f.isEmpty()) {
            if (heroIndex >= f.size()) heroIndex = 0;
            bindHero(f.get(heroIndex), false);
        }
        List<App> list = orderedApps();
        View g = grid(list, false);
        g.setPadding(g.getPaddingLeft(), dp(12), g.getPaddingRight(), g.getPaddingBottom());
        content.addView(g);
        ui.post(() -> { View cf = getCurrentFocus(); if (heroOpen != null && movingPkg == null && (cf == null || cf == weather)) heroOpen.requestFocus(); });
    }

    void renderApps() {
        TextView h = header("All apps  ·  hold OK on an app for options");
        content.addView(h);
        GridLayout grid = grid(new ArrayList<>(apps.values()), false);
        content.addView(grid);
        ui.post(() -> { if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus(); });
    }

    View buildHero() {
        hero = new FrameLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(200));
        lp.setMargins(dp(56), dp(4), dp(56), dp(12));
        hero.setLayoutParams(lp);
        round(hero, dp(20));

        heroImage = new ImageView(this);
        heroImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(dp(300), dp(169), Gravity.END | Gravity.CENTER_VERTICAL);
        ip.setMargins(0, 0, dp(40), 0);
        hero.addView(heroImage, ip);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setGravity(Gravity.CENTER_VERTICAL);
        text.setPadding(dp(40), 0, dp(360), 0);
        heroTitle = new TextView(this);
        heroTitle.setTextColor(Color.WHITE);
        heroTitle.setTextSize(32);
        heroTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heroTitle.setMaxLines(1);
        heroTitle.setPadding(0, 0, 0, dp(18));
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        heroSub = new TextView(this);
        heroSub.setTextColor(0xCCFFFFFF);
        heroSub.setTextSize(17);
        heroSub.setPadding(0, dp(4), 0, dp(16));
        heroOpen = new TextView(this);
        heroOpen.setText("▶  Open");
        heroOpen.setTextSize(17);
        heroOpen.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heroOpen.setPadding(dp(26), dp(11), dp(26), dp(11));
        heroOpen.setFocusable(true);
        heroOpen.setClickable(true);
        heroOpen.setDefaultFocusHighlightEnabled(false);
        styleHeroBtn(false);
        heroOpen.setOnFocusChangeListener((v, has) -> {
            styleHeroBtn(has);
            if (has) scroll.smoothScrollTo(0, 0);
        });
        heroOpen.setOnClickListener(v -> { if (heroPkg != null) launchPkg(heroPkg); });
        heroOpen.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            List<App> f = favApps();
            if (f.isEmpty()) return false;
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { heroIndex = (heroIndex + 1) % f.size(); bindHero(f.get(heroIndex), true); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) { heroIndex = (heroIndex - 1 + f.size()) % f.size(); bindHero(f.get(heroIndex), true); return true; }
            return false;
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        text.addView(heroTitle);
        text.addView(heroOpen, bp);
        hero.addView(text, new FrameLayout.LayoutParams(-1, -1));
        return hero;
    }

    void styleHeroBtn(boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(26));
        g.setColor(focused ? 0xFFFFFFFF : 0x40FFFFFF);
        heroOpen.setTextColor(focused ? 0xFF202124 : 0xFFFFFFFF);
        heroOpen.setBackground(g);
    }

    void bindHero(App a, boolean animate) {
        if (hero == null) return;
        heroPkg = a.pkg;
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF101114, blend(a.color, 0xFF101114, 0.35f), a.color});
        g.setCornerRadius(dp(20));
        hero.setBackground(g);
        heroTitle.setText(a.label);
        heroImage.setImageDrawable(a.banner);
        if (animate) {
            heroImage.setAlpha(0f);
            heroImage.animate().alpha(1f).setDuration(350).start();
            heroTitle.setAlpha(0f);
            heroTitle.animate().alpha(1f).setDuration(350).start();
        }
    }

    static int blend(int a, int b, float t) {
        return Color.rgb((int) (Color.red(a) * t + Color.red(b) * (1 - t)),
                (int) (Color.green(a) * t + Color.green(b) * (1 - t)),
                (int) (Color.blue(a) * t + Color.blue(b) * (1 - t)));
    }

    TextView header(String s) {
        TextView h = new TextView(this);
        h.setText(s);
        h.setTextColor(0xFFE8EAED);
        h.setTextSize(19);
        h.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        h.setPadding(dp(56), dp(10), dp(56), dp(6));
        return h;
    }

    static final int COLS = 7;

    GridLayout grid(List<App> list, boolean favRow) {
        GridLayout g = new GridLayout(this);
        g.setColumnCount(COLS);
        g.setPadding(dp(48), dp(2), dp(48), dp(2));
        g.setClipChildren(false);
        g.setClipToPadding(false);
        int usable = getResources().getDisplayMetrics().widthPixels - dp(96);
        int w = usable / COLS - dp(16);
        for (int i = 0; i < list.size(); i++) {
            App a = list.get(i);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(i / COLS), GridLayout.spec(i % COLS));
            lp.width = w + dp(16);
            lp.height = -2;
            g.addView(card(a, w, favs.contains(a.pkg)), lp);
        }
        return g;
    }

    View row(String title, List<App> list, boolean favRow) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.addView(header(title));
        box.addView(grid(list, favRow));
        return box;
    }

    View card(App a, int w, boolean isFav) {
        int h = Math.round((w - dp(8)) * 9f / 16f);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setClipChildren(false);
        wrap.setClipToPadding(false);
        wrap.setPadding(dp(8), dp(8), dp(8), dp(4));

        FrameLayout c = new FrameLayout(this);
        c.setFocusable(true);
        c.setClickable(true);
        c.setLongClickable(true);
        c.setDefaultFocusHighlightEnabled(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(a.hasBanner ? 0xFF202124 : a.color);
        c.setBackground(bg);
        round(c, dp(12));

        if (a.hasBanner) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(a.banner);
            c.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageDrawable(a.banner);
            int sz = Math.round(h * 0.62f);
            c.addView(iv, new FrameLayout.LayoutParams(sz, sz, Gravity.CENTER));
        }
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(15));
        ring.setStroke(dp(2), Color.WHITE);
        FrameLayout holder = new FrameLayout(this);
        holder.setClipChildren(false);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-1, -1);
        clp.setMargins(dp(4), dp(4), dp(4), dp(4));
        holder.addView(c, clp);
        View ringView = new View(this);
        ringView.setBackground(ring);
        ringView.setVisibility(View.INVISIBLE);
        holder.addView(ringView, new FrameLayout.LayoutParams(-1, -1));
        wrap.addView(holder, new LinearLayout.LayoutParams(w, h + dp(8)));

        TextView label = new TextView(this);
        label.setText(a.label);
        label.setTextColor(0xFF9AA0A6);
        label.setTextSize(12);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setPadding(0, dp(6), 0, dp(2));
        wrap.addView(label, new LinearLayout.LayoutParams(w, -2));

        cards.put(a.pkg, new View[]{c, ringView, label});
        c.setOnFocusChangeListener((v, has) -> {
            if (a.pkg.equals(movingPkg)) return;
            ringView.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
            holder.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).setDuration(140).start();
            label.setTextColor(has ? 0xFFFFFFFF : 0xFF9AA0A6);
            label.animate().translationY(has ? dp(4) : 0).setDuration(140).start();
            if (has && tab == 0) {
                if (isFav) { int i = favApps().indexOf(a); if (i >= 0) { heroIndex = i; bindHero(a, true); } }
                int[] loc = new int[2];
                wrap.getLocationInWindow(loc);
                int screenH = getResources().getDisplayMetrics().heightPixels;
                if (loc[1] + wrap.getHeight() > screenH - dp(24) || loc[1] < dp(80)) {
                    scroll.smoothScrollBy(0, loc[1] - screenH / 3);
                }
            }
        });
        c.setOnClickListener(v -> launchPkg(a.pkg));
        c.setOnLongClickListener(v -> { options(a); return true; });
        return wrap;
    }

    void round(View v, int r) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) { o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r); }
        });
        v.setClipToOutline(true);
    }

    boolean hasFocusInside(View v) { View f = getCurrentFocus(); while (f != null) { if (f == v) return true; f = f.getParent() instanceof View ? (View) f.getParent() : null; } return false; }

    void options(App a) {
        boolean fav = favs.contains(a.pkg);
        List<String> items = new ArrayList<>();
        boolean isSettings = SETTINGS_PKG.equals(a.pkg);
        boolean isVg = VG_SETTINGS.equals(a.pkg);
        if (!isSettings) items.add("Move");
        items.add(fav ? "Remove from banner" : "Show in banner");
        if (!isSettings && !isVg) items.add("Hide app");
        if (!VG_SETTINGS.equals(a.pkg)) items.add("App info");
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(a.label)
            .setItems(items.toArray(new String[0]), (d, which) -> {
                String choice = items.get(which);
                int i = favs.indexOf(a.pkg);
                switch (choice) {
                    case "Move": startMove(a.pkg); swallowUp = true; return;
                    case "Remove from banner": favs.remove(a.pkg); break;
                    case "Show in banner": favs.add(a.pkg); break;
                    case "Hide app":
                        hidden.add(a.pkg);
                        prefs.edit().putStringSet("hidden", new java.util.HashSet<>(hidden)).apply();
                        break;
                    case "App info":
                        launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg)));
                        return;
                }
                saveFavs();
                render();
            })
            .show();
    }

    // ---------- weather ----------

    static String http(String u) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(8000);
        try (java.io.InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toString("UTF-8");
        } finally { c.disconnect(); }
    }

    static String icon(int code, boolean day) {
        if (code == 0) return day ? "☀️" : "🌙";
        if (code <= 2) return day ? "🌤️" : "☁️";
        if (code == 3) return "☁️";
        if (code == 45 || code == 48) return "🌫️";
        if (code >= 51 && code <= 57) return "🌦️";
        if (code >= 61 && code <= 67) return "🌧️";
        if (code >= 71 && code <= 77) return "🌨️";
        if (code >= 80 && code <= 82) return "🌦️";
        if (code == 85 || code == 86) return "🌨️";
        if (code >= 95) return "⛈️";
        return "🌡️";
    }

    void fetchWeather() {
        final double lat = Double.parseDouble(prefs.getString("wlat", "-37.0457"));
        final double lon = Double.parseDouble(prefs.getString("wlon", "143.7390"));
        new Thread(() -> {
            try {
                org.json.JSONObject cur = new org.json.JSONObject(http(
                    "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                    + "&current=temperature_2m,weather_code,is_day"
                    + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                    + "&forecast_days=6&timezone=auto"));
                final String fc = forecastText(cur);
                ui.post(() -> { if (ticker != null) { ticker.setText(fc); ticker.setSelected(true); } if (sTicker != null) sTicker.setText(fc); });
                cur = cur.getJSONObject("current");
                final String txt = icon(cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1)
                    + "  " + Math.round(cur.getDouble("temperature_2m")) + "°";
                weatherAt = System.currentTimeMillis();
                ui.post(() -> { if (weather != null) weather.setText(txt); if (sWeather != null) sWeather.setText(txt); });
            } catch (Exception e) {
                ui.post(() -> { if (weather != null && weatherAt == 0) weather.setText("--°"); });
            }
        }).start();
        ui.removeCallbacks(weatherTick);
        ui.postDelayed(weatherTick, 30 * 60 * 1000L);
    }

    private final Runnable weatherTick = () -> fetchWeather();

    String forecastText(org.json.JSONObject root) throws Exception {
        org.json.JSONObject d = root.getJSONObject("daily");
        org.json.JSONArray time = d.getJSONArray("time"), code = d.getJSONArray("weather_code"),
            hi = d.getJSONArray("temperature_2m_max"), lo = d.getJSONArray("temperature_2m_min"),
            rain = d.optJSONArray("precipitation_probability_max");
        java.text.SimpleDateFormat in = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        java.text.SimpleDateFormat out = new java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault());
        StringBuilder sb = new StringBuilder(prefs.getString("wname", "Maryborough").toUpperCase() + " FORECAST");
        for (int i = 0; i < time.length(); i++) {
            String day = i == 0 ? "Today" : i == 1 ? "Tomorrow" : out.format(in.parse(time.getString(i)));
            sb.append("        ").append(day).append("  ").append(icon(code.getInt(i), true)).append("  ")
              .append(Math.round(hi.getDouble(i))).append("\u00B0 / ").append(Math.round(lo.getDouble(i))).append("\u00B0");
            if (rain != null && !rain.isNull(i) && rain.getInt(i) >= 10) sb.append("  \u2022  ").append(rain.getInt(i)).append("% rain");
        }
        return sb.toString();
    }

    void chooseLocation() {
        final android.widget.EditText in = new android.widget.EditText(this);
        in.setHint("Town or suburb");
        in.setSingleLine(true);
        in.setText(prefs.getString("wname", "Maryborough"));
        in.selectAll();
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Weather location")
            .setView(in)
            .setPositiveButton("Search", (d, w) -> searchLocation(in.getText().toString().trim()))
            .setNegativeButton("Cancel", null)
            .show();
    }

    void searchLocation(String q) {
        if (q.isEmpty()) return;
        new Thread(() -> {
            try {
                org.json.JSONObject r = new org.json.JSONObject(http(
                    "https://geocoding-api.open-meteo.com/v1/search?count=8&language=en&name="
                    + java.net.URLEncoder.encode(q, "UTF-8")));
                org.json.JSONArray arr = r.optJSONArray("results");
                if (arr == null || arr.length() == 0) { ui.post(() -> toast("No places found for " + q)); return; }
                final String[] names = new String[arr.length()];
                final org.json.JSONObject[] objs = new org.json.JSONObject[arr.length()];
                for (int i = 0; i < arr.length(); i++) {
                    objs[i] = arr.getJSONObject(i);
                    names[i] = objs[i].optString("name") + ", " + objs[i].optString("admin1") + ", " + objs[i].optString("country");
                }
                ui.post(() -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Choose location")
                    .setItems(names, (d, w) -> {
                        prefs.edit().putString("wlat", String.valueOf(objs[w].optDouble("latitude")))
                            .putString("wlon", String.valueOf(objs[w].optDouble("longitude")))
                            .putString("wname", objs[w].optString("name")).apply();
                        fetchWeather();
                    }).show());
            } catch (Exception e) { ui.post(() -> toast("Couldn't reach the weather service")); }
        }).start();
    }

    static final String[] NEWS_FEEDS = {
        "https://www.abc.net.au/news/feed/10719986/rss.xml",
        "https://www.abc.net.au/news/feed/51120/rss.xml"
    };

    void fetchNews() {
        new Thread(() -> {
            for (String feed : NEWS_FEEDS) {
                try {
                    org.xmlpull.v1.XmlPullParser x = android.util.Xml.newPullParser();
                    x.setInput(new java.io.StringReader(http(feed)));
                    List<String> titles = new ArrayList<>();
                    boolean inItem = false;
                    for (int ev = x.getEventType(); ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT && titles.size() < 12; ev = x.next()) {
                        if (ev == org.xmlpull.v1.XmlPullParser.START_TAG) {
                            if ("item".equals(x.getName())) inItem = true;
                            else if (inItem && "title".equals(x.getName())) { String t = x.nextText().trim(); if (!t.isEmpty()) titles.add(t); }
                        } else if (ev == org.xmlpull.v1.XmlPullParser.END_TAG && "item".equals(x.getName())) inItem = false;
                    }
                    if (titles.isEmpty()) continue;
                    final String txt = "ABC NEWS   \u2022   " + TextUtils.join("     \u2022     ", titles);
                    newsAt = System.currentTimeMillis();
                    ui.post(() -> { if (ticker != null) { ticker.setText(txt); ticker.setSelected(true); } });
                    break;
                } catch (Exception ignored) { }
            }
        }).start();
        ui.removeCallbacks(newsTick);
        ui.postDelayed(newsTick, 15 * 60 * 1000L);
    }

    private final Runnable newsTick = () -> fetchNews();

    void toast(String m) { android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_LONG).show(); }

    // ---------- screensaver ----------

    static final int[] SAVER_MINUTES = {1, 2, 5, 10, 15, 30, 0};
    static final int[] SAVER_SECONDS = {10, 20, 30, 60, 120, 300};
    static final int[] PICSUM_IDS = {10, 11, 15, 16, 17, 28, 29, 46, 47, 49, 50, 54, 57, 110, 112, 124, 128, 142, 152, 167};

    private FrameLayout rootView, saver;
    private ImageView saverA, saverB;
    private TextView sWeather, sTicker, sBattery, sCaption;
    private final WifiGlyph sWifiGlyph = new WifiGlyph();
    private final List<String[]> saverPics = new ArrayList<>(); // {url, caption}
    private int saverIdx = 0;
    private boolean saverOn = false, swallowAnyUp = false;
    private Bitmap saverBmp;

    int saverMinutes() { return prefs.getInt("saverMin", 5); }
    int saverSeconds() { return prefs.getInt("saverSec", 30); }

    private final Runnable idleTick = () -> { if (hasWindowFocus() && movingPkg == null) showSaver(); else resetIdle(); };
    private final Runnable saverTick = new Runnable() {
        @Override public void run() { nextPicture(); ui.postDelayed(this, saverSeconds() * 1000L); }
    };

    void resetIdle() {
        ui.removeCallbacks(idleTick);
        if (saverMinutes() > 0) ui.postDelayed(idleTick, saverMinutes() * 60_000L);
    }

    void buildSaver() {
        saver = new FrameLayout(this);
        saver.setBackgroundColor(Color.BLACK);
        saver.setVisibility(View.GONE);
        saverA = new ImageView(this); saverB = new ImageView(this);
        for (ImageView iv : new ImageView[]{saverA, saverB}) {
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setAlpha(0f);
            saver.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        }
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0x99000000, 0xDD000000}));
        saver.addView(scrim, new FrameLayout.LayoutParams(-1, dp(190), Gravity.BOTTOM));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(56), 0, dp(56), dp(34));

        sCaption = new TextView(this);
        sCaption.setTextColor(0xAAFFFFFF);
        sCaption.setTextSize(13);
        sCaption.setMaxLines(1);
        sCaption.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(sCaption);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, 0);

        sTicker = new TextView(this);
        sTicker.setSingleLine(true);
        sTicker.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        sTicker.setMarqueeRepeatLimit(-1);
        sTicker.setHorizontallyScrolling(true);
        sTicker.setTextColor(0xDDFFFFFF);
        sTicker.setTextSize(18);
        sTicker.setShadowLayer(dp(3), 0, dp(1), 0xAA000000);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.setMargins(0, 0, dp(24), 0);
        row.addView(sTicker, tl);

        ImageView w = new ImageView(this);
        w.setImageDrawable(sWifiGlyph);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(dp(26), dp(20));
        wl.setMargins(0, 0, dp(12), 0);
        row.addView(w, wl);

        sBattery = shadowText(19, 0xFFFFFFFF);
        sBattery.setPadding(0, 0, dp(12), 0);
        row.addView(sBattery);

        sWeather = shadowText(22, 0xFFFFFFFF);
        sWeather.setPadding(0, 0, dp(18), 0);
        row.addView(sWeather);

        TextClock d = new TextClock(this);
        d.setFormat12Hour("EEE d MMM"); d.setFormat24Hour("EEE d MMM");
        d.setTextColor(0xDDFFFFFF); d.setTextSize(22);
        d.setShadowLayer(dp(3), 0, dp(1), 0xAA000000);
        d.setPadding(0, 0, dp(18), 0);
        row.addView(d);

        TextClock t = new TextClock(this);
        t.setFormat12Hour("h:mm a"); t.setFormat24Hour("h:mm a");
        t.setTextColor(Color.WHITE); t.setTextSize(46);
        t.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        t.setShadowLayer(dp(4), 0, dp(1), 0xAA000000);
        row.addView(t);

        col.addView(row);
        saver.addView(col, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        rootView.addView(saver, new FrameLayout.LayoutParams(-1, -1));
    }

    TextView shadowText(float size, int color) {
        TextView v = new TextView(this);
        v.setTextSize(size); v.setTextColor(color);
        v.setShadowLayer(dp(3), 0, dp(1), 0xAA000000);
        return v;
    }

    void showSaver() {
        if (saverOn || saver == null) return;
        saverOn = true;
        if (weather != null) sWeather.setText(weather.getText());
        if (ticker != null) sTicker.setText(ticker.getText());
        if (battery != null) { sBattery.setText(battery.getText()); sBattery.setVisibility(battery.getVisibility()); }
        sTicker.setSelected(true);
        saver.setAlpha(0f);
        saver.setVisibility(View.VISIBLE);
        saver.bringToFront();
        saver.animate().alpha(1f).setDuration(800).start();
        if (saverPics.isEmpty()) loadSaverList(); else nextPicture();
        ui.removeCallbacks(saverTick);
        ui.postDelayed(saverTick, saverSeconds() * 1000L);
    }

    void hideSaver() {
        if (!saverOn) return;
        saverOn = false;
        ui.removeCallbacks(saverTick);
        saver.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            saver.setVisibility(View.GONE);
            saverA.setImageDrawable(null); saverB.setImageDrawable(null);
            saverA.setAlpha(0f); saverB.setAlpha(0f);
            if (saverBmp != null) { saverBmp.recycle(); saverBmp = null; }
        }).start();
        resetIdle();
    }

    void loadSaverList() {
        new Thread(() -> {
            List<String[]> list = new ArrayList<>();
            try {
                for (int idx : new int[]{0, 8}) {
                    org.json.JSONArray imgs = new org.json.JSONObject(http(
                        "https://www.bing.com/HPImageArchive.aspx?format=js&n=8&mkt=en-AU&idx=" + idx)).getJSONArray("images");
                    for (int i = 0; i < imgs.length(); i++) {
                        org.json.JSONObject o = imgs.getJSONObject(i);
                        String u = "https://www.bing.com" + o.getString("urlbase") + "_1920x1080.jpg";
                        boolean dup = false; for (String[] x : list) if (x[0].equals(u)) dup = true;
                        if (!dup) list.add(new String[]{u, o.optString("copyright", "")});
                    }
                }
            } catch (Exception ignored) { }
            if (list.isEmpty()) for (int id : PICSUM_IDS) list.add(new String[]{"https://picsum.photos/id/" + id + "/1920/1080", ""});
            Collections.shuffle(list);
            ui.post(() -> { saverPics.clear(); saverPics.addAll(list); if (saverOn) nextPicture(); });
        }).start();
    }

    void nextPicture() {
        if (!saverOn || saverPics.isEmpty()) return;
        final String[] pic = saverPics.get(saverIdx++ % saverPics.size());
        new Thread(() -> {
            Bitmap bm = null;
            try {
                java.io.File f = new java.io.File(getCacheDir(), "saver_" + Integer.toHexString(pic[0].hashCode()) + ".jpg");
                if (!f.exists() || f.length() == 0) {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(pic[0]).openConnection();
                    c.setConnectTimeout(10000); c.setReadTimeout(20000); c.setInstanceFollowRedirects(true);
                    try (java.io.InputStream in = c.getInputStream(); java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                        byte[] b = new byte[16384]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n);
                    } finally { c.disconnect(); }
                    trimSaverCache();
                }
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                o.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
                o.inSampleSize = o.outWidth > 2400 ? 2 : 1;
                o.inJustDecodeBounds = false;
                bm = android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
            } catch (Exception ignored) { }
            final Bitmap fbm = bm;
            ui.post(() -> {
                if (fbm == null || !saverOn) { if (fbm != null) fbm.recycle(); return; }
                ImageView in = saverA.getAlpha() > 0.5f ? saverB : saverA;
                ImageView out = in == saverA ? saverB : saverA;
                final Bitmap old = saverBmp;
                saverBmp = fbm;
                in.setImageBitmap(fbm);
                in.setScaleX(1f); in.setScaleY(1f);
                in.animate().alpha(1f).setDuration(1500).start();
                in.animate().scaleX(1.08f).scaleY(1.08f).setDuration(saverSeconds() * 1000L + 2000).start();
                out.animate().alpha(0f).setDuration(1500).withEndAction(() -> {
                    out.setImageDrawable(null);
                    if (old != null && old != saverBmp) old.recycle();
                }).start();
                String cap = pic[1];
                sCaption.setText(cap == null ? "" : cap.replaceAll("\\s*\\(©.*\\)$", ""));
            });
        }).start();
    }

    void trimSaverCache() {
        java.io.File[] fs = getCacheDir().listFiles((d, n) -> n.startsWith("saver_"));
        if (fs == null || fs.length <= 20) return;
        java.util.Arrays.sort(fs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < fs.length - 20; i++) fs[i].delete();
    }

    void chooseSaverDelay() {
        String[] names = new String[SAVER_MINUTES.length];
        for (int i = 0; i < names.length; i++) names[i] = SAVER_MINUTES[i] == 0 ? "Never" : SAVER_MINUTES[i] + (SAVER_MINUTES[i] == 1 ? " minute" : " minutes");
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Start screensaver after")
            .setItems(names, (d, w) -> { prefs.edit().putInt("saverMin", SAVER_MINUTES[w]).apply(); resetIdle(); }).show();
    }

    void choosePictureTime() {
        String[] names = new String[SAVER_SECONDS.length];
        for (int i = 0; i < names.length; i++) names[i] = SAVER_SECONDS[i] < 60 ? SAVER_SECONDS[i] + " seconds" : (SAVER_SECONDS[i] / 60) + (SAVER_SECONDS[i] == 60 ? " minute" : " minutes");
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Change picture every")
            .setItems(names, (d, w) -> prefs.edit().putInt("saverSec", SAVER_SECONDS[w]).apply()).show();
    }

    String saverDelayLabel() { int m = saverMinutes(); return m == 0 ? "Never" : m + " min"; }
    String pictureLabel() { int s = saverSeconds(); return s < 60 ? s + " sec" : (s / 60) + " min"; }

    // ---------- updates (GitHub releases) ----------

    static final String RELEASES = "https://api.github.com/repos/benwintle73/vistaglow/releases/latest";
    static final String ACTION_INSTALLED = "com.benwintle.tvhome.INSTALL_STATUS";

    long myVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode(); }
        catch (Exception e) { return 0; }
    }

    void checkUpdate(boolean manual) {
        prefs.edit().putLong("updAt", System.currentTimeMillis()).apply();
        new Thread(() -> {
            try {
                org.json.JSONObject r = new org.json.JSONObject(http(RELEASES));
                String tag = r.optString("tag_name", "");
                long ver = Long.parseLong(tag.replaceAll("[^0-9]", ""));
                String url = null;
                org.json.JSONArray as = r.optJSONArray("assets");
                for (int i = 0; as != null && i < as.length(); i++) {
                    String n = as.getJSONObject(i).optString("name");
                    if (n.endsWith(".apk")) url = as.getJSONObject(i).optString("browser_download_url");
                }
                final String fu = url; final String notes = r.optString("body", "").trim();
                if (fu != null && ver > myVersion()) ui.post(() -> offerUpdate(ver, fu, notes));
                else if (manual) ui.post(() -> toast("VistaGlow is up to date"));
            } catch (Exception e) {
                if (manual) ui.post(() -> toast("Couldn't check for updates"));
            }
        }).start();
    }

    void offerUpdate(long ver, String url, String notes) {
        if (saverOn) return;
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("VistaGlow update available")
            .setMessage((notes.isEmpty() ? "A new version is ready." : notes) + "\n\nVersion " + ver)
            .setPositiveButton("Install", (d, w) -> downloadAndInstall(url))
            .setNegativeButton("Later", null).show();
    }

    void downloadAndInstall(String url) {
        toast("Downloading update…");
        new Thread(() -> {
            try {
                android.content.pm.PackageInstaller pi = getPackageManager().getPackageInstaller();
                android.content.pm.PackageInstaller.SessionParams sp = new android.content.pm.PackageInstaller.SessionParams(
                    android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                sp.setAppPackageName(getPackageName());
                int id = pi.createSession(sp);
                try (android.content.pm.PackageInstaller.Session s = pi.openSession(id)) {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    c.setInstanceFollowRedirects(true); c.setConnectTimeout(15000); c.setReadTimeout(30000);
                    try (java.io.InputStream in = c.getInputStream(); java.io.OutputStream out = s.openWrite("vistaglow.apk", 0, -1)) {
                        byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n);
                        s.fsync(out);
                    } finally { c.disconnect(); }
                    Intent cb = new Intent(ACTION_INSTALLED).setPackage(getPackageName());
                    android.app.PendingIntent p = android.app.PendingIntent.getBroadcast(this, id, cb,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_MUTABLE);
                    s.commit(p.getIntentSender());
                }
            } catch (Exception e) {
                ui.post(() -> toast("Update failed: " + e.getMessage()));
            }
        }).start();
    }

    private final BroadcastReceiver installReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999);
            if (st == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) { confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(confirm); }
            } else if (st != android.content.pm.PackageInstaller.STATUS_SUCCESS) {
                String m = i.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE);
                toast("Update not installed" + (m == null ? "" : ": " + m));
            }
        }
    };

    // ---------- Home button setup ----------

    boolean isDefaultHome() {
        ResolveInfo ri = getPackageManager().resolveActivity(
            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        return ri != null && ri.activityInfo != null && getPackageName().equals(ri.activityInfo.packageName);
    }

    boolean homeServiceOn() {
        String on = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return on != null && on.contains(getPackageName() + "/");
    }

    void askHomeSetup() {
        prefs.edit().putBoolean("homeAsked", true).apply();
        if (isDefaultHome()) { toast("VistaGlow is already your Home screen"); return; }
        if (homeServiceOn()) {
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Home button")
                .setMessage("The Home button already opens VistaGlow. To stop it, turn off \"VistaGlow remote helper\" in Accessibility settings.")
                .setPositiveButton("Open settings", (d, w) -> launch(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .setNegativeButton("Close", null).show();
            return;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Make VistaGlow your Home screen?")
            .setMessage("So the Home button on your remote opens VistaGlow:\n\n"
                + "1. Choose Open settings below\n"
                + "2. Select \"VistaGlow remote helper\"\n"
                + "3. Turn it on and confirm\n\n"
                + "If it says the setting is restricted, go to Settings \u2192 Apps \u2192 VistaGlow and choose \"Allow restricted settings\", then try again.")
            .setPositiveButton("Open settings", (d, w) -> launch(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
            .setNegativeButton("Not now", null).show();
    }

    void launcherMenu() {
        List<String> items = new ArrayList<>();
        items.add("Weather location: " + prefs.getString("wname", "Maryborough"));
        items.add("Screensaver starts after: " + saverDelayLabel());
        items.add("Change picture every: " + pictureLabel());
        items.add("Show screensaver now");
        items.add(isDefaultHome() || homeServiceOn() ? "Home button: opens VistaGlow \u2713" : "Set up Home button");
        items.add("Netflix remote control: " + (!prefs.getBoolean("netflixRemote", true) ? "Off"
            : homeServiceOn() ? "On \u2713" : "needs setup"));
        items.add("Check for updates (version " + myVersion() + ")");
        if (!hidden.isEmpty()) items.add("Show hidden apps (" + hidden.size() + ")");
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("VistaGlow settings")
            .setItems(items.toArray(new String[0]), (d, w) -> {
                String c = items.get(w);
                if (c.startsWith("Weather")) chooseLocation();
                else if (c.startsWith("Screensaver starts")) chooseSaverDelay();
                else if (c.startsWith("Change picture")) choosePictureTime();
                else if (c.startsWith("Show screensaver")) ui.postDelayed(this::showSaver, 400);
                else if (c.startsWith("Netflix remote")) netflixMenu();
                else if (c.startsWith("Check for updates")) checkUpdate(true);
                else if (c.startsWith("Set up Home") || c.startsWith("Home button")) askHomeSetup();
                else unhideMenu();
            }).show();
    }

    void netflixMenu() {
        boolean on = prefs.getBoolean("netflixRemote", true);
        if (on && !homeServiceOn()) {
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Netflix remote control")
                .setMessage("Lets the remote work in Netflix: arrows move a white highlight, OK presses it. "
                    + "In a show, Left/Right skip 10 seconds and OK brings up the controls.\n\n"
                    + "1. Choose Open settings below\n"
                    + "2. Select \"VistaGlow remote helper\"\n"
                    + "3. Turn it on and confirm\n\n"
                    + "If it says the setting is restricted, go to Settings \u2192 Apps \u2192 VistaGlow and choose \"Allow restricted settings\", then try again.")
                .setPositiveButton("Open settings", (d, w) -> launch(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .setNeutralButton("Turn off", (d, w) -> prefs.edit().putBoolean("netflixRemote", false).apply())
                .setNegativeButton("Not now", null).show();
            return;
        }
        prefs.edit().putBoolean("netflixRemote", !on).apply();
        toast("Netflix remote control " + (on ? "off" : "on"));
    }

    void unhideMenu() {
        final List<String> pk = new ArrayList<>(hidden);
        final String[] names = new String[pk.size()];
        final boolean[] checked = new boolean[pk.size()];
        PackageManager pm = getPackageManager();
        for (int i = 0; i < pk.size(); i++) {
            String n = pk.get(i);
            try { n = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pk.get(i), 0))); } catch (Exception ignored) { }
            names[i] = n;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Tick apps to show again")
            .setMultiChoiceItems(names, checked, (d, w, c) -> checked[w] = c)
            .setPositiveButton("Done", (d, w) -> {
                for (int i = 0; i < pk.size(); i++) if (checked[i]) hidden.remove(pk.get(i));
                prefs.edit().putStringSet("hidden", new java.util.HashSet<>(hidden)).apply();
                render();
            })
            .setNegativeButton("Cancel", null).show();
    }

    void launchPkg(String pkg) {
        if (android.os.SystemClock.uptimeMillis() - resumedAt < 1000) return;
        if (movingPkg != null) return;
        if (VG_SETTINGS.equals(pkg)) { launcherMenu(); return; }
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i != null) launch(i);
    }

    void launch(Intent i) {
        try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); } catch (Exception ignored) { }
    }
}
