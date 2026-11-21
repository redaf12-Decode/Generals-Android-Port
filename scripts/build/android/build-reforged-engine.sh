#!/bin/bash
# Build the ACTUAL olcay Reforged engine as an Android ARM64 shared library.
#
# This is the real Reforged-engine integration: it checks out the pinned olcay
# engine (docs/reforged-integration/engine-source.manifest.json), applies the
# vendored Android adaptation layer (reforged/android-adaptation/patches/),
# fetches the engine's own third-party dependencies (Tools/vendor.sh), and
# cross-compiles the rfx_game target to librfx_game.so (renamed libmain.so).
#
# The produced library is verified (ELF aarch64, SDL_main exported) before the
# script succeeds. A launcher-only build or a static check is NOT this script.
#
# Requirements: Android NDK (ANDROID_NDK_HOME or ANDROID_NDK_HOME auto-detected),
# ninja, meson, pkg-config, glslang, ccache, a C++17 host compiler, zlib headers.
#
# Usage:
#   build-reforged-engine.sh [--engine <dir>] [--build <dir>] [--out <lib>] [--skip-selfcheck]
#
# Env knobs:
#   GX_SKIP_SELFCHECK=1   skip the build-only sdl_gpu_frame_selfcheck gate
#   JOBS=<n>              parallelism (default nproc)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
MANIFEST="${REPO_ROOT}/docs/reforged-integration/engine-source.manifest.json"
PATCHES="${REPO_ROOT}/reforged/android-adaptation/patches"

ENGINE="${REPO_ROOT}/reforged-engine"
BUILD="${REPO_ROOT}/rfx-build"
OUT="${REPO_ROOT}/libmain.so"
SKIP_SELFCHECK="${GX_SKIP_SELFCHECK:-0}"
JOBS="${JOBS:-$(nproc)}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --engine) ENGINE="${2:-}"; shift 2 ;;
        --build)  BUILD="${2:-}"; shift 2 ;;
        --out)    OUT="${2:-}"; shift 2 ;;
        --skip-selfcheck) SKIP_SELFCHECK=1; shift ;;
        -h|--help) sed -n '2,25p' "${BASH_SOURCE[0]}"; exit 0 ;;
        *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
    esac
done

log() { echo "==> $*"; }
die() { echo "ERROR: $*" >&2; exit 1; }

# --- toolchain discovery ---------------------------------------------------
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    for cand in "${ANDROID_SDK_ROOT:-}/ndk"/* "${ANDROID_HOME:-}/ndk"/* "$HOME/Android/Sdk/ndk"/*; do
        [[ -f "$cand/build/cmake/android.toolchain.cmake" ]] && { export ANDROID_NDK_HOME="$cand"; break; }
    done
fi
[[ -n "${ANDROID_NDK_HOME:-}" && -f "${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake" ]] \
    || die "No Android NDK found. Set ANDROID_NDK_HOME to a valid NDK."
llvm_nm()  { ls "${ANDROID_NDK_HOME}"/toolchains/llvm/prebuilt/*/bin/llvm-nm 2>/dev/null | head -1; }
llvm_readelf() { ls "${ANDROID_NDK_HOME}"/toolchains/llvm/prebuilt/*/bin/llvm-readelf 2>/dev/null | head -1; }
NM="$(llvm_nm)"; READELF="$(llvm_readelf)"
[[ -x "$NM" ]] || die "llvm-nm not found under $ANDROID_NDK_HOME"
[[ -x "$READELF" ]] || die "llvm-readelf not found under $ANDROID_NDK_HOME"

# --- pinned engine checkout (reuse fetch-reforged-engine.sh) ---------------
if [[ ! -d "${ENGINE}/.git" ]]; then
    log "Fetching pinned Reforged engine"
    "${SCRIPT_DIR}/fetch-reforged-engine.sh" --dest "${ENGINE}"
fi
PIN="$(grep -oE '"commit"[[:space:]]*:[[:space:]]*"[^"]+"' "${MANIFEST}" | head -1 | sed -E 's/.*"([^"]+)"$/\1/')"
ACTUAL="$(git -C "${ENGINE}" rev-parse HEAD)"
[[ "$ACTUAL" == "$PIN" ]] || die "Engine at $ACTUAL, expected $PIN. Re-run fetch-reforged-engine.sh."
log "Engine pinned at $PIN (verified)"

# --- patch helpers ---------------------------------------------------------
SRC="${ENGINE}"
apply_patch() {
    local p="$1"; shift
    log "Applying $(basename "$p")"
    git -C "$SRC" apply --check "$p" || die "patch does not apply: $(basename "$p")"
    git -C "$SRC" apply "$@" "$p"
}

# --- stages 1-3: c1, frame-selfcheck-entry, frame-selfcheck-mali -----------
apply_patch "${PATCHES}/reforged-c1-android.patch"
apply_patch "${PATCHES}/reforged-frame-selfcheck-entry.patch"
apply_patch "${PATCHES}/reforged-frame-selfcheck-mali-tolerances.patch"
[[ -f "$SRC/GeneralsMD/Code/Tests/frame_selfcheck_main.cpp" ]] || die "frame_selfcheck_main.cpp missing"

# --- vendor dependencies (SDL3 3.4.16, GameSpy, FreeType, glslang, ...) ----
log "Running Tools/vendor.sh (fetches pinned third-party sources)"
bash "$SRC/GeneralsMD/Code/Tools/vendor.sh"

# --- stages 4-5: rfx-jni-prefix (SDL3), gamespy-android-pthread ------------
apply_patch "${PATCHES}/reforged-rfx-jni-prefix.patch"
grep -q "org_libsdl_apprfx" "$SRC/GeneralsMD/Code/Libraries/Source/SDL3/src/core/android/SDL_android.c" \
    || die "rfx-jni-prefix did not take effect"
GS="$SRC/GeneralsMD/Code/Libraries/Source/GameSpy"
[[ -f "$GS/src/common/linux/gsthreadlinux.c" ]] || die "vendored GameSpy not found"
log "Applying gamespy-android-pthread.patch"
GIT_CEILING_DIRECTORIES="$SRC/GeneralsMD/Code/Libraries/Source" \
    git -C "$GS" -c core.autocrlf=false apply --check "${PATCHES}/gamespy-android-pthread.patch" \
    || die "gamespy patch does not apply"
GIT_CEILING_DIRECTORIES="$SRC/GeneralsMD/Code/Libraries/Source" \
    git -C "$GS" -c core.autocrlf=false apply "${PATCHES}/gamespy-android-pthread.patch"

# --- host tool the engine build needs --------------------------------------
mkdir -p "${REPO_ROOT}/host-tools"
HOSTCXX="${CXX:-c++}"
"$HOSTCXX" -O2 -std=c++17 "$SRC/GeneralsMD/Code/Tools/fingerprint/build_fingerprint.cpp" \
    -o "${REPO_ROOT}/host-tools/build_fingerprint"
export PATH="${REPO_ROOT}/host-tools:$PATH"

# --- configure (Android arm64) ---------------------------------------------
log "Configuring CMake (Android arm64-v8a, API 28)"
cmake -S "$SRC/GeneralsMD/Code" -B "$BUILD" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_COMPILER_LAUNCHER=ccache -DCMAKE_CXX_COMPILER_LAUNCHER=ccache

# --- optional build-only self-check gate (ships nothing) -------------------
# Builds the frame selfcheck to prove the c1 CMake/bionic adaptation and the
# Android cross-compile work before the (much longer) full game build. The
# selfcheck is a test harness, NOT the engine: it does not export SDL_main (that
# is verified on librfx_game.so below), so this gate only asserts that it built.
if [[ "$SKIP_SELFCHECK" != "1" ]]; then
    log "Building sdl_gpu_frame_selfcheck (build-only compile gate)"
    cmake --build "$BUILD" --target sdl_gpu_frame_selfcheck -j"$JOBS"
    [[ -f "$BUILD/libsdl_gpu_frame_selfcheck.so" ]] || die "selfcheck: library not produced"
    log "selfcheck built OK"
fi

# --- stage 6: c4 game-library patch ----------------------------------------
apply_patch "${PATCHES}/reforged-c4-game-library.patch"
grep -q "add_library(rfx_game SHARED" "$SRC/GeneralsMD/Code/CMakeLists.txt" || die "rfx_game target missing"
grep -q "zh_c4_force_pic" "$SRC/GeneralsMD/Code/CMakeLists.txt" || die "zh_c4_force_pic missing"

# --- build the actual engine ----------------------------------------------
log "Building rfx_game (the actual Reforged engine)"
cmake --build "$BUILD" --target rfx_game -j"$JOBS"
cp "$BUILD/librfx_game.so" "$OUT"

# --- verify the ACTUAL engine library --------------------------------------
log "Verifying $OUT"
"$READELF" -h "$OUT" | grep -q "AArch64" || die "not AArch64"
"$READELF" -h "$OUT" | grep -q "DYN"     || die "not a shared object"
"$NM" -D "$OUT" | grep -q " T SDL_main"  || die "SDL_main not exported"
log "DT_NEEDED:"
"$READELF" -d "$OUT" | grep NEEDED || true

log "OK: Reforged engine built at $OUT (ELF aarch64, SDL_main exported)"
echo "    Rename to libmain.so and stage into android/app/src/main/jniLibs/arm64-v8a/ to ship it."