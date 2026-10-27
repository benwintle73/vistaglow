package com.benwintle.tvhome;

import java.util.Locale;

/**
 * Plain-Java decisions behind the Netflix remote helper, kept free of Android classes so they can be
 * tested on any JVM (tests/RemoteLogicTest.java). HomeService feeds these the key timings and node facts.
 */
final class RemoteLogic {
    private RemoteLogic() { }

    /** Holding Back this long (inside Netflix) turns the Netflix remote helper off. */
    static final long BACK_HOLD_MS = 1200;
    /** Holding OK this long performs a long-click instead of a click. */
    static final long OK_LONG_MS = 600;
    /** Held arrows: pause before the first repeat, then this gap between moves. */
    static final long FIRST_REPEAT_MS = 300, REPEAT_MS = 160;

    enum Press { SHORT, LONG }

    /** Short or long press, from when the key went down and came up. */
    static Press classify(long downAt, long upAt, long longMs) {
        return upAt - downAt >= longMs ? Press.LONG : Press.SHORT;
    }

    /** Should a held arrow's auto-repeat move again now? repeatCount is KeyEvent.getRepeatCount(). */
    static boolean repeatAllowed(int repeatCount, long now, long lastMove) {
        if (repeatCount <= 0) return true;
        long gap = repeatCount == 1 ? FIRST_REPEAT_MS : REPEAT_MS;
        return now - lastMove >= gap;
    }

    /** A view that is very likely the video picture: a surface/texture/video view, or an id naming the player. */
    static boolean isVideoView(String className, String viewId) {
        String c = className == null ? "" : className.toLowerCase(Locale.US);
        String id = viewId == null ? "" : viewId.toLowerCase(Locale.US);
        return c.contains("surfaceview") || c.contains("textureview") || c.contains("videoview")
            || id.contains("player") || id.contains("video_view") || id.contains("videoview");
    }

    /** Covers at least 90% of the screen (Netflix home rows autoplay small previews; those don't count). */
    static boolean coversScreen(int left, int top, int right, int bottom, int screenW, int screenH) {
        int w = Math.max(0, Math.min(right, screenW) - Math.max(left, 0));
        int h = Math.max(0, Math.min(bottom, screenH) - Math.max(top, 0));
        long area = (long) w * h, full = (long) screenW * screenH;
        return full > 0 && area * 10 >= full * 9;
    }

    /** Full-screen playback with the player's own controls hidden: let the remote talk to the player. */
    static boolean passToPlayer(boolean fullScreenVideo, boolean playerActivity, int controlsVisible) {
        return (fullScreenVideo || playerActivity) && controlsVisible < 2;
    }
}
