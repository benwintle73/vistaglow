package com.benwintle.tvhome;

/** Plain-JVM tests for RemoteLogic. Run: ./run-tests.sh (exits non-zero on any failure). */
public class RemoteLogicTest {
    static int failures = 0, passed = 0;

    static void check(String name, boolean ok) {
        if (ok) passed++; else { failures++; System.out.println("FAIL " + name); }
    }

    public static void main(String[] args) {
        // Back hold
        check("back tap is short", RemoteLogic.classify(1000, 1300, RemoteLogic.BACK_HOLD_MS) == RemoteLogic.Press.SHORT);
        check("back just under hold is short", RemoteLogic.classify(0, 1199, RemoteLogic.BACK_HOLD_MS) == RemoteLogic.Press.SHORT);
        check("back hold is long", RemoteLogic.classify(0, 1200, RemoteLogic.BACK_HOLD_MS) == RemoteLogic.Press.LONG);

        // OK long press
        check("ok tap is short", RemoteLogic.classify(0, 150, RemoteLogic.OK_LONG_MS) == RemoteLogic.Press.SHORT);
        check("ok hold is long", RemoteLogic.classify(0, 700, RemoteLogic.OK_LONG_MS) == RemoteLogic.Press.LONG);

        // Held-arrow repeat pacing
        check("first press always moves", RemoteLogic.repeatAllowed(0, 0, 0));
        check("first repeat waits 300ms", !RemoteLogic.repeatAllowed(1, 250, 0));
        check("first repeat after 300ms moves", RemoteLogic.repeatAllowed(1, 300, 0));
        check("later repeats wait 160ms", !RemoteLogic.repeatAllowed(5, 100, 0));
        check("later repeats after 160ms move", RemoteLogic.repeatAllowed(5, 160, 0));

        // Video view detection
        check("SurfaceView is video", RemoteLogic.isVideoView("android.view.SurfaceView", ""));
        check("TextureView is video", RemoteLogic.isVideoView("android.view.TextureView", null));
        check("player id is video", RemoteLogic.isVideoView("android.widget.FrameLayout", "com.netflix.mediaclient:id/player_view"));
        check("poster is not video", !RemoteLogic.isVideoView("android.widget.ImageView", "com.netflix.mediaclient:id/poster"));
        check("nulls are safe", !RemoteLogic.isVideoView(null, null));

        // Full screen
        check("full screen surface", RemoteLogic.coversScreen(0, 0, 1920, 1080, 1920, 1080));
        check("hero preview not full screen", !RemoteLogic.coversScreen(0, 0, 1920, 600, 1920, 1080));
        check("nearly full (letterbox) counts", RemoteLogic.coversScreen(0, 30, 1920, 1050, 1920, 1080));
        check("offscreen parts are clipped", !RemoteLogic.coversScreen(-1920, 0, 960, 1080, 1920, 1080));
        check("empty screen is safe", !RemoteLogic.coversScreen(0, 0, 10, 10, 0, 0));

        // Playback: which keys go to Netflix
        check("playing, controls hidden", RemoteLogic.playingWithoutControls(true, false, 0));
        check("playing, controls showing", !RemoteLogic.playingWithoutControls(true, false, 4));
        check("player activity counts", RemoteLogic.playingWithoutControls(false, true, 1));
        check("browsing is not playback", !RemoteLogic.playingWithoutControls(false, false, 0));
        check("OK goes to the player", RemoteLogic.passToPlayer(true, true));
        check("arrows stay with VistaGlow (no pointer)", !RemoteLogic.passToPlayer(true, false));
        check("nothing passed while browsing", !RemoteLogic.passToPlayer(false, true));

        // Title page: Play button labels
        check("Play", RemoteLogic.isPlayLabel("Play"));
        check("Resume", RemoteLogic.isPlayLabel(" Resume "));
        check("Play S1:E1", RemoteLogic.isPlayLabel("Play S1:E1"));
        check("poster 'Play Below' is not the button", !RemoteLogic.isPlayLabel("Play Below"));
        check("poster 'Play Seinfeld' is not the button", !RemoteLogic.isPlayLabel("Play Seinfeld"));
        check("Resume Episode 3", RemoteLogic.isPlayLabel("Resume Episode 3"));
        check("Download is not play", !RemoteLogic.isPlayLabel("Download"));
        check("null label", !RemoteLogic.isPlayLabel(null));

        // Top bar / bottom tabs (1080 tall)
        check("bottom Home tab is in the bars", RemoteLogic.inBars(968, 1080, 1080));
        check("top back arrow is in the bars", RemoteLogic.inBars(8, 96, 1080));
        check("Play button is page content", !RemoteLogic.inBars(600, 700, 1080));
        check("poster row is page content", !RemoteLogic.inBars(409, 896, 1080));

        System.out.println(passed + " passed, " + failures + " failed");
        if (failures > 0) System.exit(1);
    }
}
