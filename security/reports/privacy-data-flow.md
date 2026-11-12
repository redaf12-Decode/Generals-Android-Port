# Privacy and data-flow review — `com.ports.generalsx` — 12/11/2026

Phase 6 of the security remediation. Scope: every field the launcher collects, where it goes, where
it is stored, how long it lives, and how the user controls it. Sources inspected:
`NetworkDiagnostics.java`, `GeneralsOnlineSession.java`, `NetworkTrace.java`,
`NetworkDiagnosticsActivity.java`, `GeneralsOnlineActivity.java`, `UpdateChecker.java`,
`ApkInstaller.java`, `DataPackInstaller.java`, and the two backup-rule XML files.

## Data-flow table

| Data | Purpose | Destination | Storage | Retention | User control |
|---|---|---|---|---|---|
| Install id (random UUID) | Stand-in for the PC client's `machine_guid`; one stable value per install | GeneralsOnline API (`LoginCode`/`CheckLogin`/auth) | `SharedPreferences` `generalsonline_session` (`install_id`) | Until app data is cleared | Implicit; no opt-out (needed to sign in) |
| Synthetic MAC | Stand-in for `mac_addr` | GeneralsOnline API | Derived from install id, not stored separately | As install id | Implicit |
| Synthetic volume serial | Stand-in for `vol_serial` | GeneralsOnline API | Derived from install id | As install id | Implicit |
| Device manufacturer + model | Diagnostics context (which phone reported the problem) | Diagnostic report shown on screen; shared only if the user taps Share | Not persisted by the app | Report lifetime | Share is user-initiated |
| Android version + API level | Diagnostics context | Same as above | Not persisted | Report lifetime | Share is user-initiated |
| DNS servers, proxy, VPN/private-DNS state | Explain a failed sign-in (ISP filtering, captive portal) | Diagnostic report | Not persisted | Report lifetime | Share is user-initiated |
| Display name, user id | Account identity in the lobby | GeneralsOnline API | `SharedPreferences` + in-memory session | Session | Sign-out clears it |
| Session token | Authorize API calls; read by the native game | GeneralsOnline API (Authorization header) | `files/generalsonline_session.txt` + `SharedPreferences` | Until it expires or sign-out | Sign-out deletes both |
| Refresh token | Obtain a new session token without re-login | GeneralsOnline API | `files/generalsonline_session.txt` + `SharedPreferences` | Until sign-out | Sign-out deletes both |
| Network trace (`generals-network.log`) | Request/response lines from sign-in attempts, for support | Written to `getExternalFilesDir()`; shared only via the Share button | External files dir, rotated to `.prev` | Rotated on size; deleted on "Clear" | Share and Clear are user actions |

## Synthetic identifiers — stated plainly

`installId()` is a random UUID generated once and kept. `syntheticMac()` and
`syntheticVolumeSerial()` are SHA-256-derived from it and are **not** read from any hardware:

- `syntheticMac` sets the locally-administered bit (`0x02`) and clears the multicast bit, so it is
  structurally a locally-administered address, never a real adapter's.
- `syntheticVolumeSerial` is 8 hex digits derived from the install id.
- Neither carries the Windows client's `YY`/`ZZ` provenance suffixes.

They are per-installation, not per-device: clearing app data or reinstalling produces a new one.
This is documented in `NetworkDiagnostics.java`'s own comments. **Residual**: nothing user-facing
says so. Recommendation — the diagnostic report header or its help text should state that the
`install id` line is an app-generated value, not a hardware identifier. This is a wording change,
not a code defect.

## Logging and redaction

- `NetworkTrace.redact()` removes a bearer token from an `Authorization`-style header, the
  `session_token` / `refresh_token` / `access_token` JSON fields, and the `key=value` form used in
  `files/generalsonline_session.txt`. It replaces the value with `<token, N chars>`, keeping the
  length (so "was a token sent, and was it the right size" stays answerable) but never the value.
- `NetworkTrace.snippet()` redacts **before** truncating, so a reply cut mid-token cannot leak the
  first half.
- `GeneralsOnlineSession` no longer writes the raw bearer token into the trace line (Phase 6): it
  logs `auth=Bearer <token, N chars>`.
- Response bodies are logged as snippets; the auth responses' tokens are covered by the redaction
  patterns above.

## Backup exclusions

`res/xml/backup_rules.xml` (Android 11 and below) and `res/xml/data_extraction_rules.xml`
(Android 12+, cloud backup and device transfer) both exclude:

- `files/generalsonline_session.txt` — the session marker (session + refresh tokens)
- `sharedpref/generalsonline_session.xml` — the preferences copy of the same tokens
- `external/generals-network.log` and `.prev` — the trace

So a cloud backup or a phone-to-phone transfer cannot carry the account or the device's traffic
log off the device. These were verified present and correct; no change needed.

## FileProvider exposure

`res/xml/file_paths.xml` exposes three roots: `cache-path .`, `files-path .`, and
`external-files-path .`. The provider is `exported="false"` and grants read access only per-Uri.

Three call sites share a specific file: `ApkInstaller` (the verified APK), `LogViewerActivity` (a
zip of logs), and `NetworkDiagnosticsActivity` (the trace log). A fourth potential call site was
considered and **not** added: `NetworkDiagnosticsActivity.shareReport()` already falls back to
copying the report to the clipboard when no trace file exists, so the `files-path .` root is not
used to expose a report file. If it ever is, it must use a **narrow subdirectory**
(e.g. `<files-path name="diag" path="diagnostics/" />`) rather than the whole `files/` root, so the
session marker and `update/` tree (holding a downloaded engine and a verified APK) cannot be named
by a Uri.

The `.` roots are required by the existing log-sharing and APK-install features and are read-only
per-Uri; changing them to narrower paths is a hardening improvement, not a defect fix.

## Sign-out and retention

`GeneralsOnlineSession.clearSession()` clears the `generalsonline_session` preferences **and**
deletes `files/generalsonline_session.txt`, so both token copies go together. It is called from
`GeneralsOnlineActivity.clearSession()` (sign-out button) and the account-switch path. The trace log
is separately cleared by the Log viewer's "Clear" action and rotates by size.

## Verdict

The launcher collects the minimum needed for sign-in and support. The hardware-shaped fields are
honestly synthetic and documented in code. Tokens are redacted from logs, stored in app-private
storage, excluded from every backup path, and removed on sign-out. The only open item is the
user-facing wording that the synthetic identifiers are not real hardware identifiers.
