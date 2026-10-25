#!/usr/bin/env bash
# Builds VistaGlow without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner
# Env: VERSION_CODE, KEYSTORE (path), KS_PASS, KS_ALIAS
set -euo pipefail
cd "$(dirname "$0")"
VC="${VERSION_CODE:?set VERSION_CODE}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
BT="$SDK/build-tools/34.0.0"; AJ="$SDK/platforms/android-34/android.jar"
if [ ! -x "$BT/aapt2" ] || [ ! -f "$AJ" ]; then
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null || true
  "$SDK/cmdline-tools/latest/bin/sdkmanager" "platforms;android-34" "build-tools;34.0.0" >/dev/null
fi
B=build; rm -rf "$B"; mkdir -p "$B/classes" "$B/gen" "$B/dex"
"$BT/aapt2" compile --dir src/res -o "$B/res.zip"
"$BT/aapt2" link -I "$AJ" --manifest src/AndroidManifest.xml -o "$B/base.apk" "$B/res.zip" --java "$B/gen" \
  --min-sdk-version 28 --target-sdk-version 34 --version-code "$VC" --version-name "$VC"
javac -nowarn -source 11 -target 11 -encoding UTF-8 -classpath "$AJ" -d "$B/classes" $(find src/java "$B/gen" -name '*.java')
"$BT/d8" --min-api 28 --lib "$AJ" --output "$B/dex" $(find "$B/classes" -name '*.class')
cp "$B/base.apk" "$B/unsigned.apk"; (cd "$B/dex" && zip -q -u ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$B/unsigned.apk" "$B/aligned.apk"
"$BT/apksigner" sign --ks "${KEYSTORE:?}" --ks-pass "pass:${KS_PASS:?}" --ks-key-alias "${KS_ALIAS:?}" --out "$B/vistaglow.apk" "$B/aligned.apk"
"$BT/apksigner" verify "$B/vistaglow.apk"
ls -l "$B/vistaglow.apk"
