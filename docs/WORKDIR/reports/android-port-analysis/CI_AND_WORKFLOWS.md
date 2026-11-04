# CI_AND_WORKFLOWS.md — GitHub Actions on `New-UI`

Requested by the task as item 8 (GitHub Actions and workflow analysis). Not one of the eight named
deliverables, so it is provided as a supplement. **[FACT]** unless marked otherwise.

---

## 1. Workflows present on `New-UI`

| File | Name | Triggers | Purpose |
|---|---|---|---|
| `build-android.yml` | Build Android | `pull_request` → `main` (paths: `GeneralsMD/**`, `Core/**`, `Dependencies/**`, `cmake/**`, `android/**`, `scripts/build/android/**`, `Patches/**`, `CMakeLists.txt`, `CMakePresets.json`, `vcpkg.json`, this file), `workflow_dispatch` | The Android build |
| `publish-release.yml` | Publish Android Release | `workflow_dispatch` (input: `version`) | Publishes a release from files already in the tree; **builds nothing** |
| `ci.yml` | GeneralsX CI | `push`/`pull_request` → `main`, `workflow_dispatch` | Linux Flatpak + macOS builds, then replay tests |
| `build-linux.yml`, `build-linux-flatpak.yml`, `build-macos.yml`, `build-windows.yml` | — | reusable (`workflow_call`) | the platform builds `ci.yml` calls |
| `replay-tests.yml` | Replay Tests | reusable | headless deterministic replay simulation |
| `release.yml` | Release Pipeline | `workflow_dispatch` | The inherited multi-platform release pipeline |
| `sign-update.yml` | Sign update | `workflow_dispatch` | Signs the update manifest |
| `old/*.yml` (8 files) | — | not triggered (moved aside, with `old/README.md`) | archived |

**[FACT]** The `push:` trigger for `build-android.yml` was **deliberately removed on 01/08/2026**,
with the reason in the file: every commit was kicking off two ~15-minute builds nobody watched,
because the session pushed to both `main` and the working branch. The only ways to start it are a
pull request into `main` and a manual `workflow_dispatch`.

**[FACT]** `mirror.yml` is **not** in `New-UI`. It lives on `main`
(`Mirror GeneralsZH Network Diagnostics`, `cron: '17 */6 * * *'`, secret `MIRROR_TOKEN`, source
`MYSOREZ/GeneralsZH-Android-Port@claude/network-diagnostics`). It is nevertheless running, because
scheduled workflows execute from the default branch — which is `main`. **This is why `New-UI` is
overwritten every six hours.**

---

## 2. `build-android.yml` in detail

### 2.1 Environment

| Setting | Value |
|---|---|
| Runner | `ubuntu-latest` |
| Preset | `android-vulkan` |
| NDK | `27.2.12479018` |
| cmdline-tools | `13114758`, sha1 `5fdcc763663eefb86a5b8879697aa6088b041e70` |
| Gradle | `8.9` (via `gradle/actions/setup-gradle@v4`) |
| JDK | 17, `temurin` |
| vcpkg | `42e4e33e1505c9f47b58c21e0f557c1571b751ee` |
| DXVK | the submodule gitlink commit, captured as `DXVK_REF` |
| `permissions` | `contents: write` (release + artifact cleanup), `pull-requests: read`, `actions: write` (artifact delete) |

### 2.2 Steps, in order

1. Checkout (`fetch-depth: 0`, `submodules: false`).
2. **Checkout DXVK fork submodule** — `git submodule update --init --depth 1 references/fbraz3-dxvk`
   **and** its own nested submodules (SPIRV-Headers, Vulkan-Headers, libdisplay-info), because meson
   dies with "Missing SPIRV-Headers" otherwise; then records `DXVK_REF`.
3. Set up JDK 17, restore the Android SDK cache, install SDK + NDK + `platforms;android-35` +
   `build-tools;35.0.0` + `platform-tools` via `sdkmanager`, export `ANDROID_SDK_ROOT` /
   `ANDROID_NDK_HOME`.
4. Set up Gradle.
5. **Install build tools**: `ninja-build meson pkg-config glslang-tools ccache` from apt, after
   removing `google-chrome.list` and `microsoft-prod.list` (their corrupt indexes used to kill the
   build with a "Hash Sum mismatch") and retrying `apt-get update` once.
6. Restore + configure ccache (`max_size=3G`, `compiler_check=content`).
7. Restore the vcpkg cache (`/opt/vcpkg` + `~/.cache/vcpkg`, keyed on the pinned commit **and**
   `hashFiles('vcpkg.json','vcpkg-lock.json','cmake/triplets/arm64-android.cmake')`) — this is what
   turns a ~45-minute ffmpeg/openssl/curl cross-build into a ~1-minute restore.
8. Restore the DXVK build cache, keyed with `github.repository` **in the key** because meson bakes
   the absolute checkout path into its private build state and a repository rename silently
   invalidated it.
9. Bootstrap vcpkg: clone or re-pin to `VCPKG_COMMIT`, `bootstrap-vcpkg.sh -disableMetrics`.
10. Configure CMake (`android-vulkan`).
11. **Build**: `cmake --build build/android-vulkan --target z_generals dxvk_d3d8_install main_hook file_redirect_hook gsl_alloc_hook hook_impl -- -k 0` with `set -o pipefail` on every `| tee`.
12. **Verify artifacts** — AArch64 check with a five-attempt retry, DXVK libraries present,
    `strings libdxvk_d3d9.so | grep Sdl3WsiDriver`.
13. Collect the symbol table **before** stripping.
14. Strip debug symbols (this is what keeps the APK near 42 MB instead of 118 MB).
15. Set the artifact version, package the APK (`./scripts/build/android/package-android-zh.sh`).
16. Locate the APK; **verify the APK contains every library `libmain.so` needs**.
17. Upload the APK artifact (7-day retention), the symbol table (90 days), and the dependency build
    logs (7 days, on failure).
18. Optional: create/publish a GitHub Release when `create_release` is set.
19. **Delete superseded artifacts across all branches** — the artifact quota would otherwise fill.
20. Save the SDK, vcpkg, DXVK and ccache caches.

### 2.3 What it does *not* do — and why that matters

**[CONFIRMED]** The build target list contains `z_generals` and **no `g_generals`**:

```
$ grep -c "g_generals\|libgenerals" .github/workflows/build-android.yml
0
```

**[CONFIRMED]** The packaging step calls `package-android-zh.sh`, which packages `libgenerals.so`
only when it exists:

```bash
BASE_GAME_LIB="$(find "${BUILD_DIR}" -name libgenerals.so -not -path "*/_deps/*" | head -1)"
if [[ -n "${BASE_GAME_LIB}" ]]; then
    cp "${BASE_GAME_LIB}" "${JNILIBS}/libgenerals.so"
```

**[CONFIRMED]** `publish-release.yml` requires **both** `libmain.so` and `libmain60.so` in the APK:

```
for lib in libmain.so libmain60.so; do
  if ! unzip -l "${APK}" "lib/arm64-v8a/${lib}" >/dev/null; then
    echo "::error::${APK} has no lib/arm64-v8a/${lib} -- build it with build-dual-hz.sh."
```

**Conclusion. [CONFIRMED]** CI as configured can produce a **Zero Hour-only, 30 Hz-only** APK. That
APK can never be published by `publish-release.yml`, and it cannot represent a release. The
workflow's own header acknowledges the local-build policy; what it does not say is that its output
is not release-shaped.

**[CONFIRMED]** The correct shape exists — on `main`:
`.github/workflows/GeneralsZH-Android-five-stage-build.yml` builds **Stage 1/4 Generals 30 Hz**,
**Stage 2/4 Generals 60 Hz**, **Stage 3/4 Zero Hour 30 Hz**, **Stage 4/4 Zero Hour 60 Hz**, and a
fifth stage assembles and packages. So the working configuration is on the wrong branch.

### 2.4 Run history on this fork

**[FACT]** GitHub API, 2026-11-04, the last 100 runs:

| Workflow | success | failure | cancelled |
|---|---|---|---|
| Mirror GeneralsZH Network Diagnostics | 35 | 1 | 0 |
| Build Android | 11 | 10 | 3 |
| Build Android (Four Engines) | 0 | 3 | 0 |
| Build Android - Four Engines (30Hz + 60Hz) | 0 | 1 | 0 |
| `.github/workflows/build-android-split.yml` | 0 | 3 | 0 |
| Commit Transfer (Live Branch Selection) | 0 | 5 | 0 |
| Download and Compress to 7z / Download, Compress and Upload Artifact | 2 | 4 | 0 |
| Clean GitHub Actions Cache | 1 | 0 | 0 |
| Mirror Network Diagnostics | 0 | 1 | 0 |

**[FACT]** The most recent `Build Android` failure, run `40084873349` (2026-10-24, branch
`fix-ui-Draw-18-10`, `workflow_dispatch`), has one job `Build 60Hz engines (fresh runner)`, which
failed at step 17 `Verify 60Hz engine outputs before handing off`, and step 18 (upload) was skipped.
The log's failing line:

```
2026-10-24T10:40:36.1050776Z ##[error]build/libmain60.so is not AArch64
```

with the file listed immediately before as present and 292 519 648 bytes.

**[CONFIRMED] Root cause.** The step is the **one-shot** form:

```yaml
- name: Verify artifacts
  run: |
    set -euo pipefail
    READELF="$(ls "${ANDROID_NDK_HOME}"/toolchains/llvm/prebuilt/*/bin/llvm-readelf | head -1)"
    for lib in build/libmain60.so build/libgenerals60.so; do
      test -s "$lib" || { echo "::error::Missing or empty 60Hz engine: $lib"; exit 1; }
      "$READELF" -h "$lib" | grep -q AArch64 || { echo "::error::$lib is not AArch64"; exit 1; }
    done
```

while the same workflow, for `libmain.so` at lines 337–345, carries a five-attempt retry with a
comment that names this exact failure:

> *"this step has intermittently failed `libmain.so is not AArch64` (builds #118, #127, #129) within
> milliseconds of ninja's own `Linking ... libmain.so` line, always on an otherwise-clean build
> (re-running the identical commit always passes) … the file's content isn't always visible to a
> process started microseconds later. Retry a few times with a short sleep before failing for real."*

**[INFERENCE]** The fix that was applied to `libmain.so` was never applied to the 60 Hz pair — the
60 Hz hand-off verification is the *newer* step and reintroduced the same race. This is a
build-reliability defect, not a compile error.

**[FACT]** Earlier failures (2026-10-18 … 2026-10-22) are from `Commit Transfer`,
`Download and Compress to 7z`, `build-android-split.yml` and three separate "four engines"
workflows, i.e. from fork-specific plumbing that no longer exists on `New-UI`.

**[FACT]** The runner also logs: *"Node.js 20 is deprecated. The following actions target Node.js 20
but are being forced to run on Node.js 24: actions/setup-java@v4, gradle/actions/setup-gradle@v4."*

---

## 3. `publish-release.yml` in detail

**[FACT]** Deliberately builds nothing. Steps:

1. Checkout.
2. **Check the release files**: `docs/releases/v<version>/notes.md` must exist; exactly **one** APK
   must be in `apk/`; the APK's `AndroidManifest.xml` (binary XML, NULs stripped) must contain the
   version string; `lib/arm64-v8a/libmain.so` **and** `libmain60.so` must be inside it.
3. `softprops/action-gh-release@v2` with `tag_name v<version>`, `body_path` the notes, and the APK
   plus `docs/releases/v<version>/*.symbols.txt.gz` as assets, `make_latest: true`.

**[FACT]** This is why releases are cheap: a few seconds of Actions budget instead of a build.

**[CONFIRMED][GAP]** It cannot run on this fork as-is: it requires an APK built by
`build-dual-hz.sh` (four engines) in `apk/`, and `apk/` currently holds exactly one APK that does
contain both `libmain.so` and `libmain60.so` — but that APK is `a48`, a **test-channel** build
(`assets/update_channel.txt` = `test`) at version 1.4.2, while the repository has **0 releases**.

---

## 4. `sign-update.yml`

**[FACT]** `workflow_dispatch` only. Signs the update manifest with the private key held as a
repository secret, producing `manifest.json.sig` for the `updates` branch. The engine verifies it
with `GXSignedSettings` / `UpdateManager` using the public key embedded in the Java and in the C++.

**[FACT]** It is the only piece of the update pipeline that must run in CI, because the private key
must not be on a workstation. Everything else (`features.py`, `publish-update.py`,
`push-updates-branch.sh`) runs locally.

---

## 5. Are the workflows compatible with the project's actual requirements?

| Requirement (from `AGENTS.md` and the code) | CI status |
|---|---|
| Build locally, do not burn the Actions budget | **[FACT]** respected: no `push` trigger; `publish-release.yml` builds nothing |
| Releases carry both engines of both games | **[CONFIRMED] not satisfiable by `build-android.yml`** (one game, one tick rate) |
| `publish-release.yml` needs `libmain60.so` | **[CONFIRMED]** it checks for it, so it is compatible *provided* the APK came from `build-dual-hz.sh` |
| Every non-system `DT_NEEDED` of `libmain.so` must be in the APK | **[FACT]** there is a dedicated step |
| DXVK must be built with the SDL3 WSI, not a silent fallback | **[FACT]** checked with `strings … | grep Sdl3WsiDriver` |
| The DXVK submodule and its own submodules must be present | **[FACT]** handled, with the reason in a comment |
| vcpkg / ccache / SDK / DXVK caches must not be trusted blindly | **[FACT]** each has a version or hash in its key, and vcpkg is re-pinned by comparing `HEAD` |
| Artifact quota must not fill | **[FACT]** there is a cleanup step with a comment about ~110 MB artifacts |
| `libgenerals60.so` must exist for the base game at 60 Hz | **[CONFIRMED] never built** |
| No tests are required by CI | **[CONFIRMED]** there is no test task anywhere |

---

## 6. Recommended workflow changes (mirrors `ROADMAP.md` P1.4/P1.5)

1. **P1.4** — give the 60 Hz hand-off verification the same five-attempt retry as `libmain.so`.
   One step, no risk, unblocks CI verification.
2. **P1.5** — decide and record: either bring the four-stage structure from `main` onto the working
   branch, or state in `AGENTS.md` that CI is an engine smoke check and releases are always local.
   The second matches the existing policy and costs one paragraph.
3. **Optional** — add the JVM unit tests (`ROADMAP.md` P3.1) as a job. They need no NDK, no vcpkg
   and no device: a checkout, JDK 17 and `gradle test` is under a minute, and it is the only way the
   launcher's pure logic ever gets checked in CI.
4. **Optional** — pin `actions/setup-java` and `gradle/actions/setup-gradle` to versions that run on
   the runner's Node, to clear the deprecation warning.
