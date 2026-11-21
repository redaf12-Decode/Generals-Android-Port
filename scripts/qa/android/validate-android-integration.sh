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
#   --lib-dir <dir>    directory of staged shared libraries (jniLibs/arm64-v8a).
#   --java-sdl <dir>   staged SDL Java root (android/app/src/main/java-sdl); enables
#                      the single-SDLActivity / JNI registration consistency checks.
#   --apk <file>       packaged APK (Phase 5+ APK-content checks).
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

# Repo root derived from this script's location (scripts/qa/android/ -> repo root),
# so source-level checks (Phase 4) work without extra arguments.
_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${_SCRIPT_DIR}/../../.." && pwd)"

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
JAVA_SDL=""
APK=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --lib-dir)  LIB_DIR="${2:-}"; shift 2 ;;
        --java-sdl) JAVA_SDL="${2:-}"; shift 2 ;;
        --apk)      APK="${2:-}"; shift 2 ;;
        -h|--help)  sed -n '2,40p' "${BASH_SOURCE[0]}"; exit 0 ;;
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

# === Phase 3: SDL3 / JNI integration =========================================
# The target runs one SDL3 stack end to end: the launcher loads "SDL3" first
# (GeneralsZHActivity.getLibraries()), then one engine built from the same
# in-tree SDL3 source the Java glue is staged from. These checks guard the
# invariants the reference's second-SDL-layer hack (stage-rfx-glue.py +
# reforged-rfx-jni-prefix.patch) would violate, and confirm the JNI native-
# method registration entry point is actually exported by libSDL3.so.

# Engine base names the launcher can System.loadLibrary() (SetupActivity
# .engineLibraries() + the "SDL3" literal in GeneralsZHActivity.getLibraries()).
ENGINE_LIB_BASES=("main" "main60" "generals" "generals60")

# True if a dynamic symbol name appears in the library. Uses readelf --dyn-syms
# when available, else falls back to `strings` (the name lives in .dynstr for any
# exported symbol, including a stripped library).
lib_exports_symbol() {
    local file="$1" sym="$2"
    if [[ -n "${READELF}" ]] && "${READELF}" --dyn-syms "${file}" 2>/dev/null | grep -qw "${sym}"; then
        return 0
    fi
    # Portable fallback: an exported dynamic symbol's name lives verbatim in the
    # binary's string table (.dynstr), so a binary-safe grep finds it even in a
    # stripped library and without binutils installed.
    grep -aq "${sym}" "${file}"
}

check_sdl_java_layer() {
    local dir="$1"
    [[ -d "${dir}" ]] || { fail "SDL Java root not found: ${dir}"; return; }
    local count
    count="$(find "${dir}" -name 'SDLActivity.java' -type f | wc -l | tr -d ' ')"
    if [[ "${count}" == "1" ]]; then
        pass "exactly one SDLActivity.java in the staged SDL Java layer"
    elif [[ "${count}" == "0" ]]; then
        fail "no SDLActivity.java under ${dir}"
    else
        fail "${count} SDLActivity.java files under ${dir} (competing SDLActivity implementations)"
    fi
    # The reference's rfx glue renames the SDL Java package to org.libsdl.apprfx
    # so two SDL versions can coexist. That is exactly the duplicate-SDL layering
    # this adaptation must not introduce.
    if find "${dir}" -type d -path '*/org/libsdl/apprfx' | grep -q .; then
        fail "org.libsdl.apprfx package present (duplicate SDL Java layer)"
    else
        pass "no duplicate org.libsdl.apprfx SDL layer"
    fi
}

check_jni_entry_points() {
    local dir="$1"
    # libSDL3.so must export JNI_OnLoad: SDL_android.c registers every
    # Java_org_libsdl_app_* native method from there (dynamic registration, not
    # exported Java_ symbols), so its presence is the SDL JNI presence proof.
    if [[ -f "${dir}/libSDL3.so" ]]; then
        if lib_exports_symbol "${dir}/libSDL3.so" "JNI_OnLoad"; then
            pass "libSDL3.so exports JNI_OnLoad (SDL native-method registration present)"
        else
            fail "libSDL3.so does not export JNI_OnLoad (SDL JNI registration missing)"
        fi
    else
        fail "libSDL3.so missing (launcher loads \"SDL3\" first)"
    fi
    # At least one engine library the launcher can name must be present.
    local found=0 name
    for name in "${ENGINE_LIB_BASES[@]}"; do
        [[ -f "${dir}/lib${name}.so" ]] && { found=1; break; }
    done
    if [[ "${found}" -eq 1 ]]; then
        pass "at least one engine library present (${ENGINE_LIB_BASES[*]} family)"
    else
        fail "no engine library present (expected one of: ${ENGINE_LIB_BASES[*]})"
    fi
}

# --- Phase 3 driver ----------------------------------------------------------
if [[ -n "${LIB_DIR}" && -d "${LIB_DIR}" ]]; then
    section "SDL3 / JNI integration: JNI entry points"
    check_jni_entry_points "${LIB_DIR}"
    if [[ -n "${JAVA_SDL}" ]]; then
        section "SDL3 / JNI integration: SDL Java layer"
        check_sdl_java_layer "${JAVA_SDL}"
    else
        info "skipping SDL Java layer checks (no --java-sdl given)"
    fi
fi

# === Phase 4: native dependency compatibility ================================
# Android/AArch64 has no bionic pthread_cancel and a reduced set of system
# libraries. These checks verify the staged libraries' runtime dependency
# (DT_NEEDED) closure resolves against the staged set or standard Android system
# libraries, and guard the pthread-cancellation semantics the audit flags:
# cancellation must be a safe no-op, never a detach substituted for cancel.

# Standard Android system libraries a staged .so is allowed to resolve against
# without being shipped in jniLibs (they are provided by the platform linker).
ANDROID_SYSTEM_LIBS_RE='libc\.so|libm\.so|libdl\.so|liblog\.so|libandroid\.so|libEGL\.so|libGLESv2\.so|libGLESv3\.so|libvulkan\.so|libOpenSLES\.so|libaaudio\.so|libz\.so|libjnigraphics\.so|libmediandk\.so|libsync\.so|libnativewindow\.so|libcamera2ndk\.so|libstdc\+\+\.so|libc\+\+_shared\.so'

check_elf_needed() {
    local dir="$1"
    if [[ -z "${READELF}" ]]; then
        info "readelf/llvm-readelf not available: skipping DT_NEEDED dependency check"
        info "(run in CI with ANDROID_NDK_HOME set, or install binutils, to enable it)"
        return
    fi
    shopt -s nullglob
    local sos=("${dir}"/*.so)
    shopt -u nullglob
    local so needed name unresolved
    for so in "${sos[@]}"; do
        unresolved=0
        while read -r needed; do
            [[ -z "${needed}" ]] && continue
            name="$(basename "${needed}")"
            grep -qE "^(${ANDROID_SYSTEM_LIBS_RE})$" <<< "${name}" && continue
            if [[ ! -f "${dir}/${needed}" && ! -f "${dir}/${name}" ]]; then
                fail "$(basename "${so}") DT_NEEDED '${needed}' is not staged and not a system library"
                unresolved=1
            fi
        done < <("${READELF}" -d "${so}" 2>/dev/null | awk -F'[][]' '/\(NEEDED\)/{print $2}')
        [[ "${unresolved}" -eq 0 ]] && pass "$(basename "${so}") DT_NEEDED dependencies resolve (staged or system)"
    done
}

check_pthread_cancel_semantics() {
    local root="$1"
    # The reference's gamespy-android-pthread.patch replaces pthread_cancel with
    # pthread_detach. The target deliberately does the opposite: a no-op cancel on
    # Android (detach changes the thread lifecycle the audit warns against). Guard
    # that decision against a regression.
    local tc="${root}/GeneralsMD/Code/CompatLib/Source/thread_compat.cpp"
    if [[ -f "${tc}" ]]; then
        if grep -A20 'defined(__ANDROID__)' "${tc}" | grep -q 'pthread_detach'; then
            fail "thread_compat.cpp: Android TerminateThread uses pthread_detach (detach-as-cancel); expected a safe no-op"
        else
            pass "thread_compat.cpp: Android TerminateThread is a no-op (no detach-as-cancel)"
        fi
    else
        info "thread_compat.cpp not found (skipping pthread source check)"
    fi
    local gs="${root}/cmake/gamespy.cmake"
    if [[ -f "${gs}" ]]; then
        if grep -q 'pthread_cancel(t)=(0)' "${gs}"; then
            pass "cmake/gamespy.cmake: Android compiles pthread_cancel to a no-op"
        else
            fail "cmake/gamespy.cmake: expected Android '-Dpthread_cancel(t)=(0)' no-op"
        fi
    else
        info "cmake/gamespy.cmake not found (skipping gamespy pthread check)"
    fi
}

# --- Phase 4 driver ----------------------------------------------------------
section "Native dependency compatibility: pthread cancellation semantics"
check_pthread_cancel_semantics "${REPO_ROOT}"
if [[ -n "${LIB_DIR}" && -d "${LIB_DIR}" ]]; then
    section "Native dependency compatibility: ELF DT_NEEDED"
    check_elf_needed "${LIB_DIR}"
fi

# === Phase 5: runtime packaging and game data ================================
# The target keeps user game data OUT of the APK (the user selects a folder via
# FolderPickerActivity / MANAGE_EXTERNAL_STORAGE) and ships only a small runtime
# asset set (fonts, dxvk.conf, DefaultOptions.ini, fallback Turnip driver). These
# checks verify the manifest/permission configuration and, for a packaged APK,
# that it carries only arm64-v8a native libraries and the runtime assets -- no
# full game data, no desktop binaries, no foreign ABIs.

check_manifest() {
    local root="$1"
    local m="${root}/android/app/src/main/AndroidManifest.xml"
    [[ -f "${m}" ]] || { info "AndroidManifest.xml not found (skipping manifest checks)"; return; }
    local s; s="$(cat "${m}")"

    grep -q 'android:name=".GeneralsZHActivity"' <<< "${s}" \
        && pass "manifest: game surface activity (.GeneralsZHActivity) declared" \
        || fail "manifest: .GeneralsZHActivity not declared (the SDLActivity game surface)"
    grep -q 'android.permission.INTERNET' <<< "${s}" \
        && pass "manifest: INTERNET permission declared" \
        || fail "manifest: INTERNET permission missing (GeneralsOnline needs it)"
    # Existing user-data mechanism: MANAGE_EXTERNAL_STORAGE (FolderPickerActivity).
    # Guard against its accidental removal; do not add new broad permissions here.
    grep -q 'android.permission.MANAGE_EXTERNAL_STORAGE' <<< "${s}" \
        && pass "manifest: MANAGE_EXTERNAL_STORAGE (user game-data folder mechanism) declared" \
        || fail "manifest: MANAGE_EXTERNAL_STORAGE missing (folder picker cannot browse)"
    # adrenotools needs real on-disk .so files (nativeLibraryDir); extractNativeLibs
    # must not be forced false in the manifest (useLegacyPackaging handles it).
    if grep -qE 'android:extractNativeLibs="false"' <<< "${s}"; then
        fail "manifest: android:extractNativeLibs=\"false\" (breaks adrenotools nativeLibraryDir)"
    else
        pass "manifest: extractNativeLibs not forced false"
    fi
}

check_apk() {
    local apk="$1"
    [[ -f "${apk}" ]] || { fail "APK not found: ${apk}"; return; }
    command -v zipinfo >/dev/null 2>&1 || { info "zipinfo not available: skipping APK content checks"; return; }
    local names; names="$(zipinfo -1 "${apk}" 2>/dev/null)" || { fail "cannot read APK: ${apk}"; return; }

    local name
    for name in libSDL3.so libmain.so; do
        if grep -qx "lib/arm64-v8a/${name}" <<< "${names}"; then
            pass "APK: lib/arm64-v8a/${name} present"
        else
            fail "APK: lib/arm64-v8a/${name} missing"
        fi
    done
    if grep -qE '^lib/(armeabi-v7a|x86|x86_64)/.+\.so$' <<< "${names}"; then
        fail "APK ships non-arm64 ABI libraries (armeabi-v7a/x86/x86_64)"
    else
        pass "APK: only arm64-v8a native libraries (no foreign ABIs)"
    fi
    if grep -qE '\.so$' <<< "${names}" && grep -E '\.so$' <<< "${names}" | grep -qvE '^lib/arm64-v8a/'; then
        fail "APK: a .so file sits outside lib/arm64-v8a/"
    else
        pass "APK: every .so is under lib/arm64-v8a/"
    fi
    if grep -qiE '\.big$' <<< "${names}"; then
        fail "APK packages .big game archives (game data must stay user-provided)"
    else
        pass "APK: no .big game archives packaged"
    fi
    if grep -qiE '\.(exe|dll)(/|$)' <<< "${names}"; then
        fail "APK contains desktop binaries (.exe/.dll)"
    else
        pass "APK: no desktop .exe/.dll binaries"
    fi
    grep -qE '^assets/gamedata/fonts/.+' <<< "${names}" \
        && pass "APK: runtime fonts present (assets/gamedata/fonts)" \
        || fail "APK: runtime fonts missing (assets/gamedata/fonts)"
    grep -qE '^assets/gamedata/dxvk.conf$' <<< "${names}" \
        && pass "APK: dxvk.conf present" \
        || fail "APK: dxvk.conf missing (assets/gamedata)"

    # Size summary (uncompressed content + top contributors).
    if command -v unzip >/dev/null 2>&1; then
        local total; total="$(unzip -l "${apk}" 2>/dev/null | awk 'NR>3 && $1 ~ /^[0-9]+$/ && NF>=4 {s+=$1} END{print s+0}')"
        info "APK on disk: $(du -h "${apk}" | cut -f1); uncompressed content: ${total} bytes"
        local line
        while read -r line; do
            [[ -n "${line}" ]] && info "APK size contributor: ${line}"
        done < <(unzip -l "${apk}" 2>/dev/null | awk 'NR>3 && $1 ~ /^[0-9]+$/ && NF>=4 {n=$4; sub(/\/$/,"",n); split(n,a,"/"); key=(a[1]=="lib" && a[2]!="")?"lib/"a[2]:a[1]; sum[key]+=$1} END{for(k in sum) printf "%10d bytes  %s/\n", sum[k], k}' | sort -rn | head -6)
    fi
}

# --- Phase 5 driver ----------------------------------------------------------
section "Runtime packaging: manifest and permissions"
check_manifest "${REPO_ROOT}"
if [[ -n "${APK}" ]]; then
    section "Runtime packaging: APK contents"
    check_apk "${APK}"
fi

# === Phase 6 sections are appended by that phase ===========================

# --- summary -----------------------------------------------------------------
section "Summary"
printf '      %d checks, %d failed\n' "${CHECKS}" "${FAILS}"
if [[ "${FAILS}" -ne 0 ]]; then
    printf 'RESULT: FAIL\n'
    exit 1
fi
printf 'RESULT: PASS\n'