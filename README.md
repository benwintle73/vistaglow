# VistaGlow

An ad-free, Google TV–style home screen for Android TV projectors and TVs (built on a Nebula Mars 3, Android TV 14).

- One app grid you can rearrange (hold OK → Move), Settings pinned last, hide apps, banner of favourites
- Top bar: 6-day forecast ticker, Wi-Fi, power (Nebula/Anker), weather, date and time
- Landscape screensaver with clock and weather
- Updates itself from this repo's GitHub Releases

## Install on your TV / projector
1. Install **Downloader** (by AFTVnews) from the Play Store.
2. In Downloader, enter: `github.com/benwintle73/vistaglow/releases/latest/download/vistaglow.apk`
3. Allow Downloader to install apps when asked, then press **Install**.
4. Open VistaGlow. It offers to make the **Home** button open VistaGlow — choose **Open settings**, select **VistaGlow Home button** and turn it on.
   If Android says the setting is restricted: Settings → Apps → VistaGlow → **Allow restricted settings**, then try again.
5. Change the weather town: select the temperature (top bar) → Weather location.

## How it's built
Every push to `main` builds a signed APK with `build.sh` (no Gradle) on GitHub Actions and publishes it as a release; the launcher checks twice a day and offers to install it.
