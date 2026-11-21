#!/bin/bash
# Validate the Android integration of a built/staged GeneralsX Zero Hour APK
# payload WITHOUT needing a device.
#
# This is the non-invasive adaptation of the reference project's "trust no
# successful exit code" philosophy (see docs/android-port/REFERENCE-ADAPTATION-AUDIT.md):
# the reference verifies artifacts inside its own build; the target verifies the
# build script's build-dir output, but nothing gated the *packaged* payload. This
# script closes that gap with static checks over the staged native libraries
# (jniLibs/arm64-v8a) and, later, the packaged APK.
#
# It is additive: it reads artifacts, it never modifies the build, the Gradle
# project, or runtime behavior.
#
# Usage:
#   validate-android-integration.sh --lib-dir <dir>      # staged jniLibs/arm64-v8a
#   validate-android-integration.sh --apk <file.apk>      # packaged APK (Phase 5+)
#
# Environment:
#   ANDROID_NDK_HOME   optional; when set, its llvm-readelf is preferred over the
#                      host readelf for ELF inspection.
#   GX_VALIDATE_REQUIRED_LIBS
#                      optional space-separated override of the required .so set
#                      (default: the core runtime set a launch needs).
#
# Exit code: 0 if every check passed, 1 otherwise. Each check prints PASS/FAIL
# with a reason; the summary lists anything that failed.

set -euo pipefail

PROG="$(basename "${BASH_SOURCE[0]}")"

# --- output helpers ----------------------------------------------------------
FAILS=0
CHECKS=0
pass() { CHECKS=$((CHECKS + 1)); printf 'PASS  %s\n' "$*"; }
fail() { CHECKS=$((CHECKS + 1)); FAILS=$((FAILS + 1)); printf 'FAIL  %s\n' "$*"; }
info() { printf '      %s\n' "$*"; }
section() { printf '\n== %s ==\n' "$*"; }

die() { printf 'ERROR %s: %s\n' "$PROG" "$*" >&2; exit 2; }

# --- argument parsing --------------------------------------------------------
LIB_DIR=""
APK=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --lib-dir) LIB_DIR="${2:-}"; shift 2 ;;
        --apk)     APK="${2:-}"; shift 2 ;;
        -h|--help) sed -n '2,40p' "${BASH_SOURCE[0]}"; exit 0 ;;
        *) die "unknown argument '$1'" ;;
    esac
done
[[ -n "${LIB_DIR}" || -n "${APK}" ]] || die "one of --lib-dir or --apk is required"

# --- tool discovery ----------------------------------------------------------
pick_readelf() {
    if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
        local cand
        cand="$(ls "${ANDROID_NDK_HOME}"/toolchains/llvm/prebuilt/*/bin/llvm-readelf 2>/dev/null | head -1 || true)"
        if [[ -n "${cand}" && -x "${cand}" ]]; then printf '%s' "${cand}"; return; fi
    fi
    command -v llvm-readelf >/dev/null 2>&1 && { command -v llvm-readelf; return; }
    command -v readelf >/dev/null 2>&1 && { command -v readelf; return; }
    printf ''
}
READELF="$(pick_readelf)"

# === Phase 2: native engine build integration ================================
# These checks verify that the native engine libraries the build produced are
# genuinely Android/AArch64 (not a desktop toolchain slipping through) and that
# every shared library a launch dlopen()s is actually present in the staged set.
# Correspondence: the target's CMake preset android-vulkan (arm64-v8a,
# ANDROID_PLATFORM=android-28, ANDROID_STL=c++_shared) is what the reference's
# build-apk.yml drives too; build-android-zh.sh already checks the build-dir
# libmain.so, and this extends the same guarantee to the *packaged* payload.

# The core runtime set a launch needs. libmain.so is the engine; the rest are its
# DT_NEEDED shared libraries (checked in Phase 4) plus the NDK C++ runtime.
DEFAULT_REQUIRED_LIBS=(
    "libmain.so"
    "libSDL3.so"
    "libdxvk_d3d8.so"
    "libdxvk_d3d9.so"
)

check_elf_aarch64() {
    local file="$1"
    if [[ -n "${READELF}" ]]; then
        local header
        header="$("${READELF}" -h "${file}" 2>/dev/null || true)"
        if grep -q "AArch64" <<< "${header}"; then
            pass "$(basename "${file}") is AArch64"
        else
            fail "$(basename "${file}") is not AArch64 (wrong toolchain reached the build)"
        fi
        return
    fi
    # Fallback for environments without readelf (e.g. a CI image with no NDK on
    # PATH): the ELF header's e_machine field (2 bytes, little-endian, at offset
    # 18) is 0x00B7 (183) for AArch64. `od` is POSIX and always present.
    local machine
    machine="$(od -An -tx1 -j18 -N2 "${file}" 2>/dev/null | tr -d ' \n' || true)"
    if [[ "${machine}" == "b700" ]]; then
        pass "$(basename "${file}") is AArch64"
    elif [[ -z "${machine}" ]]; then
        fail "cannot check $(basename "${file}"): no readelf and ELF header unreadable"
    else
        fail "$(basename "${file}") is not AArch64 (e_machine=0x${machine}, expected 0xb700)"
    fi
}

check_required_libs() {
    local dir="$1"; shift
    local required=("$@")
    local name
    for name in "${required[@]}"; do
        if [[ -f "${dir}/${name}" ]]; then
            pass "required library present: ${name}"
        else
            fail "required library MISSING: ${name} (launch will fail at System.loadLibrary/dlopen)"
        fi
    done
}

# --- Phase 2 driver: scan a directory of staged .so files --------------------
if [[ -n "${LIB_DIR}" ]]; then
    [[ -d "${LIB_DIR}" ]] || die "--lib-dir does not exist: ${LIB_DIR}"

    section "Native engine build: AArch64 architecture"
    shopt -s nullglob
    so_files=("${LIB_DIR}"/*.so)
    shopt -u nullglob
    if [[ ${#so_files[@]} -eq 0 ]]; then
        fail "no .so files found under ${LIB_DIR}"
    else
        info "found ${#so_files[@]} shared libraries under ${LIB_DIR}"
        for so in "${so_files[@]}"; do
            check_elf_aarch64 "${so}"
        done
    fi

    section "Native engine build: required shared libraries"
    # GX_VALIDATE_REQUIRED_LIBS overrides the default set (e.g. a launcherOnly
    # payload that ships no engine at all should pass an empty override).
    if [[ -n "${GX_VALIDATE_REQUIRED_LIBS:-}" ]]; then
        # shellcheck disable=SC2206
        required=(${GX_VALIDATE_REQUIRED_LIBS})
    else
        required=("${DEFAULT_REQUIRED_LIBS[@]}")
    fi
    check_required_libs "${LIB_DIR}" "${required[@]}"
fi

# === Phase 3/4/5/6 sections are appended by their phases =====================

# --- summary -----------------------------------------------------------------
section "Summary"
printf '      %d checks, %d failed\n' "${CHECKS}" "${FAILS}"
if [[ "${FAILS}" -ne 0 ]]; then
    printf 'RESULT: FAIL\n'
    exit 1
fi
printf 'RESULT: PASS\n'