# Legacy Workflows (Archived)

This directory contains workflows and configuration files from the original SuperHackers CI/CD pipeline, archived for reference purposes.

## Files

### Workflow Files (`.yml`)

- **`android-unit-tests.yml`** — the launcher-only Android job (`testDebugUnitTest` + `lintDebug` +
  `assembleLauncherOnly`, no native build). Retired 06/11/2026 at the repository owner's request:
  it duplicated nothing, but it was a second CI surface to keep green for a build path that already
  gets verified locally before every push (AGENTS.md asks for local builds only). Nothing depended
  on it — no `needs:`, no reusable-workflow `uses:`, and nothing consumed its artifacts — so
  removing it from the active set does not affect the APK build (`build-android.yml`) or a release
  (`publish-release.yml`, which attaches an APK built locally). Revive by moving it back up one
  directory; the two traps it encodes are worth keeping: `yes | sdkmanager --licenses` needs
  `|| true` under `set -o pipefail` (SIGPIPE 141), and the Gradle steps need
  `working-directory: android` because the repo root has no Gradle build.
- **`build-historical.yml`** — Historical build workflow (legacy)
- **`build-toolchain.yml`** — Toolchain-based build (used by old ci.yml for vc6/win32 presets)
- **`check-replays.yml`** — Replay compatibility testing (deferred until game is feature-complete)
- **`validate-pull-request.yml`** — PR validation workflow (not used in GeneralsX CI)
- **`weekly-release.yml`** — Weekly release automation (not applicable to active development)
- **`ci.yml.backup`** — Original SuperHackers CI pipeline (for reference)

### Configuration Files

- **`valid-tags.txt`** — Tag validation reference (legacy)

## Rationale

The GeneralsX project focuses on cross-platform ports (Linux, macOS, Windows) of the modern SDL3+DXVK+OpenAL stack. The original SuperHackers CI tested multiple legacy presets (vc6, win32, vc6-profile, etc.) and replay compatibility, which are outside the scope of the current modernization effort.

**Active CI**: See `.github/workflows/ci.yml` for the current GeneralsX pipeline.

## Future Use

These workflows may be revived if:
- Legacy VC6/Win32 presets become part of the CI baseline
- Replay testing infrastructure is needed
- Release automation is implemented

For historical context and upstream baseline testing, refer to `ci.yml.backup`.
