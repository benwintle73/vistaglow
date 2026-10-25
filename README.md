# VistaGlow

An ad-free, Google TV–style home screen for the Nebula Mars 3 projector (Android TV 14).

- One app grid you can rearrange, Settings pinned last, hide apps, banner of favourites
- Top bar: 6-day forecast ticker, Wi-Fi, power, weather, date and time
- Landscape screensaver with clock and weather
- Updates itself from this repo's GitHub Releases

Every push to `main` builds a signed APK (`build.sh`, no Gradle) and publishes it as a release; the launcher checks daily and offers to install it.

Built automatically by GitHub Actions.
