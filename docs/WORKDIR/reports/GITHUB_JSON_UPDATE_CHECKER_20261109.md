# GitHub JSON update checker (Phase 1) — report

Date: 09/11/2026
Branch: `New-app`
Scope: an "a newer version is available" notification driven by a plain `update.json` on the
project's own GitHub repository. **No APK download and no installation** — Phase 1 is a check and a
dialog only.

## 1. Files changed and added

| File | Change |
|---|---|
| `update.json` | **new** — the published version file, at the repository root |
| `android/app/src/main/java/com/generalsx/zerohour/UpdateChecker.java` | **new** — the whole feature: fetch, parse, compare, remember, dialog |
| `android/app/src/main/java/com/generalsx/zerohour/SetupActivity.java` | 6 lines in `onCreate()` — one `UpdateChecker.maybeCheck(this)` call |
| `android/app/src/main/res/values*/strings.xml` (13 files) | **new** — four `update_dialog_*` strings in every locale |
| `android/app/src/test/java/com/generalsx/zerohour/UpdateCheckerTest.java` | **new** — 22 unit tests |
| `docs/WORKDIR/reports/GITHUB_JSON_UPDATE_CHECKER_20261109.md` | **new** — this report |
| `docs/DEV_BLOG/2026-11-DIARY.md` | **new entry** — the daily log |

No new dependency, no new permission (`INTERNET` is already declared), no Gradle change.

## 2. How the update system works

`SetupActivity` is the app's launcher entry point, so `onCreate()` is "the user opened the app".
It calls `UpdateChecker.maybeCheck(this)`, which:

1. spawns a background thread (`gx-app-update`) and fetches
   `https://raw.githubusercontent.com/redaf12-Decode/Generals-Android-Port/New-app/update.json`
   over HTTPS (`HttpURLConnection`, 10 s connect / 10 s read timeouts, 128 KiB body cap);
2. parses the JSON, requiring a **non-negative integer** `versionCode` (a JSON number or a string
   of digits) and treating `versionName` and `releaseNotes` as optional plain text;
3. compares the remote `versionCode` **numerically** with the installed one, read from
   `PackageManager.getPackageInfo(...).getLongVersionCode()`;
4. if the remote is higher, returns on the UI thread and, when that version has not already been
   shown, builds and shows the dialog.

Anything else — offline, DNS failure, timeout, HTTP 4xx/5xx, oversized body, malformed JSON, a bad
version code — returns `null` and the launcher continues with no dialog and no error message.

The dialog shows the remote `versionName`, a "What's New?" heading and the release notes as
bulleted plain text inside a height-capped `ScrollView` (so a long list scrolls instead of pushing
the dialog off the screen), a single **Later** button, the app's own launcher icon, and it inherits
the active launcher theme (palette + accent) because it is built with
`MaterialAlertDialogBuilder` and `UiKit` colours.

### Version comparison

`UpdateChecker.isNewer(update, installedVersionCode)` is `update.versionCode > installedVersionCode`
— a numeric comparison, never a string one and never on `versionName`. The required truth table
holds:

| Installed | GitHub | Result |
|---|---|---|
| 10402 | 10500 | update shown |
| 10500 | 10500 | no update |
| 10500 | 10402 | no update |
| 10402 | 10403 | update shown |

Both a JSON number (`"versionCode": 10500`) and a numeric string (`"versionCode": "10500"`) are
accepted; a sign, a decimal, an exponent or a value past `Integer.MAX_VALUE` is refused.

## 3. Where the update URL is configured

One place: the `UPDATE_URL` constant at the top of
`android/app/src/main/java/com/generalsx/zerohour/UpdateChecker.java`. It is `static final`, must
stay `https://`, and is not read from any preference or file — changing it is a code change.

## 4. Publishing a new version through update.json

Edit `update.json` at the repository root on the `New-app` branch:

```json
{
  "versionCode": 10500,
  "versionName": "1.5.0",
  "releaseNotes": [
    "Fixed known bugs.",
    "Improved application performance.",
    "Added a new feature."
  ]
}
```

- `versionCode` **must** be a whole number, greater than the currently shipped `versionCode`
  (`android/app/build.gradle`, `defaultConfig.versionCode`, today `10402`). Bump it in the same
  commit as the APK so the two never disagree.
- `versionName` is what the dialog shows next to "Version:".
- `releaseNotes` is a list of short lines, shown in order. Plain text only.

Commit and push `update.json` to `New-app`; GitHub's raw host serves the new copy within a minute
or two (and is itself cached — see below). Players see the dialog on the next launch of the
launcher, once per new `versionCode`.

## 5. Notification frequency

`SharedPreferences` file `gx_app_update`, key `notified_version_code`, holds the highest version
code already shown. `shouldNotify()` returns true only when the remote code is strictly greater, so
dismissing a notification is not repeated on the next launch, but a **newer** release always
notifies again.

## 6. Error handling and security

- HTTPS only; the URL is a compile-time constant.
- Body capped at 128 KiB and read into a bounded buffer; oversized responses are refused.
- The JSON is validated before use: `JSONObject` parse failure, a missing/negative/fractional/
  over-range `versionCode`, or a non-array `releaseNotes` are all handled without throwing.
- Release notes are rendered as plain text; nothing in the JSON is executed, and no shell/command
  path exists.
- No credential or token is embedded; the request is an anonymous GET to a public file.
- No permission is added (the check needs only `INTERNET`, already declared) and no third-party
  library is introduced — `org.json` and `HttpURLConnection` are part of the platform.
- `check()` is memoised per process (`sCheckedThisSession`), so re-entering the launcher screen in
  one session does not refetch.

## 7. Testing

`UpdateCheckerTest` (22 tests, Robolectric) covers: the four-row comparison table, numeric-vs-string
codes, numeric (not lexicographic) comparison, malformed/missing/negative/fractional/over-range
version codes, non-string and blank release-note entries, the notification memory (first show,
no repeat, re-notify on a newer code), and that the installed version code resolves from the
package.

Results (local, JDK 21 + Android SDK platform/build-tools 35 + Gradle 8.9, no NDK/CMake/DXVK):

- `:app:testDebugUnitTest` — **336 tests, 0 failures** (314 pre-existing + 22 new).
- `:app:lintDebug` — **BUILD SUCCESSFUL, 0 errors** (150 pre-existing warnings; none in the new
  code).
- `:app:assembleLauncherOnly` — **BUILD SUCCESSFUL**.
- `scripts/qa/android/check-translations.py` — green (13 locales × 708 strings, printf arguments
  match).

### Scenarios not exercised automatically

The network paths (GitHub reachable / unreachable / offline / HTTP error) are not unit-tested: the
test host has no device and the class deliberately has no injection point for the connection. They
are covered by inspection — every failure mode funnels through the same `try/catch` that returns
`null` — and by the fact that a failed check changes nothing the launcher reads. On-device
verification of the dialog's appearance is likewise left to the owner.

## 8. Compatibility

`UpdateChecker` uses only APIs available at `minSdk 28`: `HttpURLConnection`, `org.json`,
`PackageInfo.getLongVersionCode()` (API 28), `Activity.isDestroyed()` (API 17), and the existing
`UiKit`/`MaterialAlertDialogBuilder` the launcher already uses. No `minSdk`/`targetSdk` change, no
Gradle change, and nothing outside the launcher's own package is touched.
