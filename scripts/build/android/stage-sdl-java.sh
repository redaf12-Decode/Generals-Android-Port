#!/bin/bash
# Stage SDL3's Java glue (org.libsdl.app.SDLActivity et al) into the Gradle
# shell WITHOUT configuring or compiling the C++ engine.
#
# package-android-zh.sh copies the same files out of the CMake FetchContent
# tree (build/android-vulkan/_deps/sdl3-src/android-project/...), which is why
# it can only run after a full native build. The Java sources do not depend on
# that build at all -- they are plain files in the SDL3 release tarball -- so
# anything that only needs to compile Java (unit tests, lint, a launcher-only
# APK) can fetch the tarball and extract that one directory.
#
# The staged tree is identical to package-android-zh.sh's: same SDL3 version,
# same android/patches/*.patch applied in the same order. Version and URL are
# read from cmake/sdl3.cmake so there is one source of truth; if that file
# moves, this script fails loudly rather than staging a different SDL.
#
# Usage: ./scripts/build/android/stage-sdl-java.sh [--force]
#   --force  re-stage even when the destination is already populated
#
# Environment:
#   SDL3_TARBALL_CACHE  a directory to keep the verified SDL3 tarball in. When set, the tarball is
#                       read from there if it is present and passes its SHA256, and written there
#                       after a download otherwise, so CI can cache it between runs. The SHA256 is
#                       always checked, so the cache is reused, never trusted. Unset = the old
#                       behaviour (download to a temp dir every time).
set -euo pipefail

FORCE=0
for arg in "$@"; do
    case "$arg" in
        --force) FORCE=1 ;;
        *) echo "ERROR: unknown argument '$arg' (usage: $0 [--force])"; exit 1 ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
ANDROID_DIR="${PROJECT_ROOT}/android"
JAVA_SDL="${ANDROID_DIR}/app/src/main/java-sdl"
PATCH_DIR="${ANDROID_DIR}/patches"
SDL_CMAKE="${PROJECT_ROOT}/cmake/sdl3.cmake"

# --- version from cmake/sdl3.cmake ------------------------------------------
if [[ ! -f "${SDL_CMAKE}" ]]; then
    echo "ERROR: ${SDL_CMAKE} not found; cannot determine the SDL3 version."
    exit 1
fi
SDL3_VERSION="$(sed -n 's/^[[:space:]]*set(SDL3_VERSION[[:space:]]*"\([^"]*\)").*/\1/p' "${SDL_CMAKE}" | head -1)"
if [[ -z "${SDL3_VERSION}" ]]; then
    echo "ERROR: could not read SDL3_VERSION from ${SDL_CMAKE}."
    exit 1
fi
SDL3_URL="https://github.com/libsdl-org/SDL/releases/download/release-${SDL3_VERSION}/SDL3-${SDL3_VERSION}.tar.gz"
# The hash cmake/sdl3.cmake verifies FetchContent with, read from the same file
# so there is still one source of truth. Verified below before anything is
# extracted: these are sources the build compiles and CI executes.
SDL3_URL_HASH="$(sed -n 's/^[[:space:]]*set(SDL3_URL_HASH[[:space:]]*"SHA256=\([^"]*\)").*/\1/p' "${SDL_CMAKE}" | head -1)"
if [[ -z "${SDL3_URL_HASH}" ]]; then
    echo "ERROR: could not read SDL3_URL_HASH from ${SDL_CMAKE}."
    exit 1
fi

if [[ -f "${JAVA_SDL}/org/libsdl/app/SDLActivity.java" && ${FORCE} -eq 0 ]]; then
    echo "==> SDL3 Java glue already staged at ${JAVA_SDL} (use --force to re-stage)"
    exit 0
fi

command -v curl >/dev/null 2>&1 || { echo "ERROR: curl not found on PATH"; exit 1; }
command -v tar  >/dev/null 2>&1 || { echo "ERROR: tar not found on PATH"; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

TARBALL="${TMP}/SDL3.tar.gz"
# A caller may point SDL3_TARBALL_CACHE at a directory holding the tarball, so CI can restore it
# from a cache rather than re-download ~40 MB on every run. The SHA256 check below is applied to
# whatever ends up in ${TARBALL}, cached or downloaded, so a stale or tampered cache file is
# rejected exactly as a bad download would be -- the cache is never trusted, only reused.
CACHED=""
if [[ -n "${SDL3_TARBALL_CACHE:-}" ]]; then
    CACHED="${SDL3_TARBALL_CACHE}/SDL3-${SDL3_VERSION}.tar.gz"
    # Created up front so the directory exists even if the download below fails: the CI save step
    # runs with `if: always()`, and a save pointed at a path that does not exist fails the step.
    mkdir -p "${SDL3_TARBALL_CACHE}"
fi
if [[ -n "${CACHED}" && -f "${CACHED}" ]] \
    && echo "${SDL3_URL_HASH}  ${CACHED}" | sha256sum -c - >/dev/null 2>&1; then
    echo "==> Using cached SDL3 ${SDL3_VERSION} tarball (${CACHED})"
    cp "${CACHED}" "${TARBALL}"
else
    echo "==> Downloading SDL3 ${SDL3_VERSION} (Java sources only)"
    curl -fsSL -o "${TARBALL}" "${SDL3_URL}"
    if [[ -n "${CACHED}" ]]; then
        cp "${TARBALL}" "${CACHED}"
    fi
fi
echo "${SDL3_URL_HASH}  ${TARBALL}" | sha256sum -c - \
    || { echo "ERROR: SDL3 ${SDL3_VERSION} tarball failed its SHA256 check."; exit 1; }

# The release tarball's Android project is the only place these files live.
# Extracting one directory keeps the download's unpack cost to a few hundred KB
# instead of the whole ~40 MB tree.
SDL_JAVA_PATH="SDL3-${SDL3_VERSION}/android-project/app/src/main/java/org/libsdl/app"
tar xzf "${TMP}/SDL3.tar.gz" -C "${TMP}" --strip-components=6 "${SDL_JAVA_PATH}" \
    || { echo "ERROR: ${SDL_JAVA_PATH} not found in the SDL3 ${SDL3_VERSION} tarball."; exit 1; }
if [[ ! -f "${TMP}/org/libsdl/app/SDLActivity.java" ]]; then
    echo "ERROR: SDLActivity.java missing after extraction."
    exit 1
fi

# --- stage ------------------------------------------------------------------
rm -rf "${JAVA_SDL}"
mkdir -p "${JAVA_SDL}/org/libsdl/app"
cp "${TMP}"/org/libsdl/app/*.java "${JAVA_SDL}/org/libsdl/app/"
echo "==> Staged SDL3 Java glue ($(ls "${JAVA_SDL}/org/libsdl/app" | wc -l | tr -d ' ') files)"

# --- patches ----------------------------------------------------------------
# Applied fresh against the just-extracted vanilla sources, exactly as
# package-android-zh.sh does. patch(1) fails on an already-applied hunk, so a
# mismatch here means the tarball is not the version the patches were written
# for -- that must stop the build, not be skipped.
if compgen -G "${PATCH_DIR}/*.patch" >/dev/null; then
    for patch_file in "${PATCH_DIR}"/*.patch; do
        echo "==> Applying $(basename "${patch_file}") to SDL3 Java glue"
        (cd "${JAVA_SDL}" && patch -p1 --forward < "${patch_file}")
    done
fi

echo "==> SDL3 Java glue ready at ${JAVA_SDL}"
