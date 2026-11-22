#!/bin/bash
# Build a Reforged APK: package the actual olcay Reforged engine (libmain.so,
# built by build-reforged-engine.sh) as the play engine of the target's Android
# application, with an isolated org.libsdl.apprfx SDL 3.4.16 Java layer and a
# ReforgedGameActivity that feeds the engine -root.
#
# Non-destructive: every change is applied to the working tree at build time
# (in CI) and never committed, so the MYSOREZ engine build is unaffected.
#
# What it does:
#   1. Build librfx_game.so -> libmain.so (build-reforged-engine.sh).
#   2. Stage the host's org.libsdl.app SDL 3.4.2 Java (so the app compiles).
#   3. Stage the Reforged org.libsdl.apprfx SDL 3.4.16 Java (stage-rfx-glue.py,
#      gesture-patched SDLActivity override).
#   4. Add ReforgedGameActivity.java and register it in the manifest.
#   5. Point SetupActivity's play action at ReforgedGameActivity.
#   6. Drop libmain.so into jniLibs/arm64-v8a (the only engine library).
#   7. gradle assembleDebug.
#   8. Verify the APK carries the Reforged engine and no MYSOREZ engine.
#
# Requirements: Android SDK/NDK (ANDROID_NDK_HOME), a C++ host compiler, ninja,
# meson, pkg-config, glslang, ccache, zlib, JDK 17, gradle (or ./gradlew), python3.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
ANDROID_DIR="${REPO_ROOT}/android"
APP_DIR="${ANDROID_DIR}/app"
JAVA_DIR="${APP_DIR}/src/main/java/com/generalsx/zerohour"
JAVA_SDL="${APP_DIR}/src/main/java-sdl"
JNILIBS="${APP_DIR}/src/main/jniLibs/arm64-v8a"
MANIFEST="${APP_DIR}/src/main/AndroidManifest.xml"
ADAPT="${REPO_ROOT}/reforged/android-adaptation"
ENGINE="${REPO_ROOT}/reforged-engine"
OUT_APK=""

JOBS="${JOBS:-$(nproc)}"
# Host SDL Java version (matches the target's cmake/sdl3.cmake pin).
HOST_SDL_VERSION="3.4.2"
HOST_SDL_SHA256="ef39a2e3f9a8a78296c40da701967dd1b0d0d6e267e483863ce70f8a03b4050c"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --out-apk) OUT_APK="${2:-}"; shift 2 ;;
        -h|--help) sed -n '2,30p' "${BASH_SOURCE[0]}"; exit 0 ;;
        *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
    esac
done

log() { echo "==> $*"; }
die() { echo "ERROR: $*" >&2; exit 1; }

# --- 1. the actual Reforged engine -----------------------------------------
log "Building the Reforged engine (libmain.so)"
GX_SKIP_SELFCHECK=1 JOBS="$JOBS" "${SCRIPT_DIR}/build-reforged-engine.sh" --out "${REPO_ROOT}/libmain.so"
[[ -f "${REPO_ROOT}/libmain.so" ]] || die "libmain.so not produced"

# --- 2. host org.libsdl.app SDL Java (so the app compiles) ------------------
SDL_JAVA_SRC="${REPO_ROOT}/rfx-host-sdl"
if [[ ! -d "${SDL_JAVA_SRC}/org/libsdl/app" ]]; then
    log "Fetching SDL ${HOST_SDL_VERSION} (host org.libsdl.app Java)"
    mkdir -p "${SDL_JAVA_SRC}"
    curl -fL --retry 3 -o /tmp/SDL3-host.tar.gz \
        "https://github.com/libsdl-org/SDL/releases/download/release-${HOST_SDL_VERSION}/SDL3-${HOST_SDL_VERSION}.tar.gz"
    echo "${HOST_SDL_SHA256}  /tmp/SDL3-host.tar.gz" | sha256sum -c -
    tar -xzf /tmp/SDL3-host.tar.gz -C "${SDL_JAVA_SRC}" --strip-components=6 \
        "SDL3-${HOST_SDL_VERSION}/android-project/app/src/main/java/org/libsdl/app"
fi
mkdir -p "${JAVA_SDL}/org/libsdl/app"
cp "${SDL_JAVA_SRC}/org/libsdl/app/"*.java "${JAVA_SDL}/org/libsdl/app/"
log "Staged host SDL Java ($(ls "${JAVA_SDL}/org/libsdl/app" | wc -l | tr -d ' ') files)"
# Apply the target's SDL Java patches (same as package-android-zh.sh).
PATCH_DIR="${ANDROID_DIR}/patches"
if [[ -d "${PATCH_DIR}" ]]; then
    for p in "${PATCH_DIR}"/*.patch; do
        [[ -f "$p" ]] || continue
        echo "==> Applying $(basename "$p") to host SDL Java"
        patch -p1 -d "${JAVA_SDL}" < "$p" || die "host SDL patch failed: $(basename "$p")"
    done
fi

# --- 3. isolated org.libsdl.apprfx SDL 3.4.16 Java (Reforged) --------------
APPRFX_GLUE_SRC="${ENGINE}/GeneralsMD/Code/Libraries/Source/SDL3/android-project/app/src/main/java/org/libsdl/app"
[[ -d "${APPRFX_GLUE_SRC}" ]] || die "Reforged SDL3 Java not found at ${APPRFX_GLUE_SRC} (engine not built?)"
rm -rf "${JAVA_SDL}/org/libsdl/apprfx"
log "Staging Reforged org.libsdl.apprfx SDL Java"
GX_GLUE_SRC="${APPRFX_GLUE_SRC}" \
GX_APP_JAVA="${JAVA_SDL}" \
GX_GLUE_SDL_ACTIVITY_OVERRIDE="${ADAPT}/patches/c4-gesture-3416-patched-SDLActivity.java" \
python3 "${ADAPT}/tools/stage-rfx-glue.py"

# --- 4. ReforgedGameActivity + manifest ------------------------------------
cp "${ADAPT}/java/ReforgedGameActivity.java" "${JAVA_DIR}/ReforgedGameActivity.java"
log "Registered ReforgedGameActivity"
python3 - "${MANIFEST}" <<'PY'
import sys
path = sys.argv[1]
text = open(path, encoding='utf-8').read()
if '.ReforgedGameActivity' in text:
    print('ReforgedGameActivity already declared'); sys.exit(0)
block = '''        <activity
            android:name=".ReforgedGameActivity"
            android:exported="false"
            android:screenOrientation="landscape"
            android:launchMode="singleInstance"
            android:theme="@android:style/Theme.NoTitleBar.Fullscreen"
            android:configChanges="layoutDirection|locale|orientation|uiMode|screenLayout|screenSize|smallestScreenSize|keyboard|keyboardHidden|navigation" />
'''
text = text.replace('</application>', block + '    </application>', 1)
open(path, 'w', encoding='utf-8').write(text)
print('injected ReforgedGameActivity into', path)
PY

# --- 5. route the launcher's play action to the Reforged activity ----------
log "Routing SetupActivity play action -> ReforgedGameActivity"
sed -i 's/GeneralsZHActivity\.class/ReforgedGameActivity.class/g' "${JAVA_DIR}/SetupActivity.java"
grep -q "ReforgedGameActivity.class" "${JAVA_DIR}/SetupActivity.java" || die "play redirect did not take"

# --- 6. jniLibs: the Reforged engine only ---------------------------------
rm -rf "${APP_DIR}/src/main/jniLibs"
mkdir -p "${JNILIBS}"
cp "${REPO_ROOT}/libmain.so" "${JNILIBS}/libmain.so"
log "Staged jniLibs:"; ls -la "${JNILIBS}"

# --- 7. gradle assembleDebug ----------------------------------------------
if [[ -x "${ANDROID_DIR}/gradlew" ]]; then GRADLE="./gradlew"; elif command -v gradle >/dev/null; then GRADLE="gradle"; else die "no gradle"; fi
log "gradle assembleDebug"
( cd "${ANDROID_DIR}" && "$GRADLE" --no-daemon assembleDebug )
APK="${APP_DIR}/build/outputs/apk/debug/app-debug.apk"
[[ -f "$APK" ]] || die "APK not produced at $APK"
[[ -n "$OUT_APK" ]] && cp "$APK" "$OUT_APK"
log "APK: $APK"

# --- 8. verify the APK carries the Reforged engine, not MYSOREZ ------------
log "Verifying APK contents"
UNZ="$(mktemp -d)"
unzip -q "$APK" -d "$UNZ"
LIBAPK="$UNZ/lib/arm64-v8a"
[[ -f "$LIBAPK/libmain.so" ]] || die "APK missing lib/arm64-v8a/libmain.so"
# No competing engines or host SDL/audio libs may be present.
for forbidden in libmain60.so libgenerals.so libgenerals60.so libSDL3.so libopenal.so libmainrfx.so libmainrfxgame.so; do
    [[ ! -f "$LIBAPK/$forbidden" ]] || die "APK contains unexpected library $forbidden"
done
READELF="$(ls "${ANDROID_NDK_HOME}"/toolchains/llvm/prebuilt/*/bin/llvm-readelf 2>/dev/null | head -1 || true)"
NM="$(ls "${ANDROID_NDK_HOME}"/toolchains/llvm/prebuilt/*/bin/llvm-nm 2>/dev/null | head -1 || true)"
if [[ -n "$READELF" ]]; then
    "$READELF" -h "$LIBAPK/libmain.so" | grep -q AArch64 || die "APK libmain.so not AArch64"
fi
if [[ -n "$NM" ]]; then
    "$NM" -D "$LIBAPK/libmain.so" | tr -d '\r' | awk '{for(i=2;i<=NF;i++) if($i=="SDL_main" && $(i-1)=="T") f=1} END{exit !f}' \
        || die "APK libmain.so does not export SDL_main (not the Reforged engine)"
fi
# apprfx SDL Java must be packaged (proves the isolated layer is present).
if command -v dexdump >/dev/null 2>&1; then
    CLASSES="$(unzip -Z1 "$APK" 'classes*.dex' 2>/dev/null || true)"
    unzip -p "$APK" classes.dex 2>/dev/null | strings | grep -q "Lorg/libsdl/apprfx/SDLActivity;" \
        || die "apprfx SDLActivity class missing from APK dex"
fi
log "APK verified: contains the Reforged engine (libmain.so, SDL_main) and no MYSOREZ engine"
rm -rf "$UNZ"