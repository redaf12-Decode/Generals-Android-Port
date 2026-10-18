# GeneralsX @build Android 18/10/2026 Fail-fast guard for the Android cross-compile.
#
# The android-vulkan preset (CMakePresets.json) asks for three things at once: vcpkg's toolchain
# for the target triplet arm64-android, the NDK toolchain chainloaded behind it, and
# ANDROID_ABI=arm64-v8a. Two different things can go wrong with that, and neither is visible in
# a log line:
#
#   1. vcpkg also builds HOST tools for the machine doing the compiling -- vcpkg-cmake,
#      vcpkg-cmake-get-vars, pkgconf, protobuf's protoc, ffmpeg-bin2c, and the abseil/utf8-range
#      those need. On a Linux runner those are *correctly* x64-linux: they are executed during the
#      build, never linked into the engine. So "x64-linux" in a configure log is not a symptom,
#      and treating it as one sends you looking for a triplet bug that is not there.
#   2. CMake does NOT switch toolchains in a build directory that already has a CMakeCache.txt --
#      it keeps the cached toolchain and only warns. A build directory that was ever configured
#      for another target therefore stays that target, and the engine gets compiled and linked
#      for the host while every log line still looks plausible. That failure IS real, and it is
#      the one worth failing on: the resulting .so only dies at dlopen() time on a phone.
#
# What this file does, in that order:
#   a. If the configure ASKS for Android (a *-android target triplet, or the NDK toolchain
#      chainloaded) but the NDK toolchain never took effect, the build directory is stale (or the
#      toolchain was dropped) -- stop with the exact remedy instead of producing a host binary.
#   b. Print the values that decide the target, so the configure log states the target instead of
#      leaving it to be inferred from library names or package paths.
#   c. Refuse an Android configure whose system/ABI/triplet drifted off Android/arm64-v8a/arm64-android.
#
# Included unconditionally from the top-level CMakeLists.txt, right after project(): every
# non-Android configure falls straight through (a no-op), including the iOS preset, whose
# arm64-ios triplet does not match *-android.

set(GX_ANDROID_REQUESTED FALSE)
if(DEFINED VCPKG_TARGET_TRIPLET AND VCPKG_TARGET_TRIPLET MATCHES "-android$")
    set(GX_ANDROID_REQUESTED TRUE)
endif()
if(DEFINED VCPKG_CHAINLOAD_TOOLCHAIN_FILE AND VCPKG_CHAINLOAD_TOOLCHAIN_FILE MATCHES "[.]toolchain[.]cmake$")
    set(GX_ANDROID_REQUESTED TRUE)
endif()

if(GX_ANDROID_REQUESTED AND NOT ANDROID)
    message(FATAL_ERROR "Refusing to configure: Android was requested, but the NDK toolchain did not take effect.
  VCPKG_TARGET_TRIPLET          = '${VCPKG_TARGET_TRIPLET}'
  VCPKG_CHAINLOAD_TOOLCHAIN_FILE = '${VCPKG_CHAINLOAD_TOOLCHAIN_FILE}'
  CMAKE_SYSTEM_NAME (in effect) = '${CMAKE_SYSTEM_NAME}'
CMake keeps the toolchain already recorded in this build directory and only warns, so the engine would be built for the build machine (x86_64 Linux) instead of arm64-v8a. The .so would then only fail when a phone tries to load it.
Remedy: delete ${CMAKE_BINARY_DIR}/CMakeCache.txt (or the whole build directory) and configure again. The caches that are safe to keep (ccache, vcpkg's binary cache, DXVK's meson build directory) live outside it.")
endif()

if(ANDROID)
    # The NDK toolchain records the ABI as CMAKE_ANDROID_ARCH_ABI; ANDROID_ABI is the input spelling
    # the preset passes. Accept either, and only fail on positive evidence of a wrong value -- an
    # unset variable is reported, not treated as drift, so this cannot fail a valid configure.
    set(GX_ANDROID_ABI "${CMAKE_ANDROID_ARCH_ABI}")
    if(NOT GX_ANDROID_ABI AND ANDROID_ABI)
        set(GX_ANDROID_ABI "${ANDROID_ABI}")
    endif()

    message(STATUS "[android] target: CMAKE_SYSTEM_NAME      = ${CMAKE_SYSTEM_NAME}")
    message(STATUS "[android] target: CMAKE_ANDROID_ARCH_ABI = ${GX_ANDROID_ABI}")
    message(STATUS "[android] target: CMAKE_TOOLCHAIN_FILE   = ${CMAKE_TOOLCHAIN_FILE}")
    if(DEFINED VCPKG_CHAINLOAD_TOOLCHAIN_FILE)
        message(STATUS "[android] target: VCPKG_CHAINLOAD_TOOLCHAIN_FILE = ${VCPKG_CHAINLOAD_TOOLCHAIN_FILE}")
    endif()
    # VCPKG_HOST_TRIPLET stays x64-linux unless someone sets it: host tools only, never linked.
    message(STATUS "[android] target: VCPKG_TARGET_TRIPLET   = ${VCPKG_TARGET_TRIPLET} (host tools: ${VCPKG_HOST_TRIPLET})")
    message(STATUS "[android] target: CMAKE_ANDROID_NDK      = ${CMAKE_ANDROID_NDK}")

    if(NOT CMAKE_SYSTEM_NAME STREQUAL "Android")
        message(FATAL_ERROR "Android configure resolved CMAKE_SYSTEM_NAME='${CMAKE_SYSTEM_NAME}', expected 'Android'.")
    endif()
    if(GX_ANDROID_ABI AND NOT GX_ANDROID_ABI STREQUAL "arm64-v8a")
        message(FATAL_ERROR "Android configure resolved the ABI as '${GX_ANDROID_ABI}', expected 'arm64-v8a'. This port ships arm64-v8a only (CMakePresets.json android-vulkan: ANDROID_ABI=arm64-v8a).")
    endif()
    if(DEFINED VCPKG_TARGET_TRIPLET AND NOT VCPKG_TARGET_TRIPLET STREQUAL "arm64-android")
        message(FATAL_ERROR "vcpkg target triplet is '${VCPKG_TARGET_TRIPLET}', expected 'arm64-android'. Every dependency the engine links (ffmpeg, curl, openal-soft, freetype, zlib, glm, gli, gamenetworkingsockets) must come from the arm64-android tree; an x64-linux triplet here would link host libraries into the APK.")
    endif()
    if(DEFINED VCPKG_CHAINLOAD_TOOLCHAIN_FILE AND NOT VCPKG_CHAINLOAD_TOOLCHAIN_FILE MATCHES "android[.]toolchain[.]cmake$")
        message(FATAL_ERROR "VCPKG_CHAINLOAD_TOOLCHAIN_FILE='${VCPKG_CHAINLOAD_TOOLCHAIN_FILE}' is not the NDK's build/cmake/android.toolchain.cmake, so vcpkg's ports would not be cross-compiled by the same toolchain the engine is built with. CMakePresets.json android-vulkan sets it to ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake.")
    endif()
endif()
