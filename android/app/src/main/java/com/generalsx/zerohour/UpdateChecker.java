package com.generalsx.zerohour;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * GeneralsX @feature Android port 09/11/2026 A simple "a newer version exists" check, separate
 * from {@link UpdateManager}.
 *
 * {@code UpdateManager} replaces the engine inside an already-installed APK from a signed
 * manifest. This class does the other, much smaller job: it reads a plain, unsigned
 * {@code update.json} published on the project's own GitHub repository, compares its numeric
 * {@code versionCode} with the version code Android reports for this install, and, when the
 * remote one is higher, shows a dialog with the version name and the release notes. It downloads
 * nothing and installs nothing -- Phase 1 is a notification only.
 *
 * The URL is a compile-time constant ({@link #UPDATE_URL}), fetched over HTTPS. The check runs on
 * a background thread, is attempted at most once per application session, and any failure
 * (offline, timeout, HTTP error, malformed JSON) is swallowed silently: the launcher keeps working
 * exactly as it would have without the check.
 *
 * The remote JSON is untrusted input. It is only ever read for three fields, the version code must
 * be a non-negative integer (a JSON number or a string of digits -- never a sign, a decimal or an
 * exponent), the body is size-capped, and the release notes are shown as plain text. Nothing in
 * the JSON is executed, and no Android permission beyond INTERNET (already declared) is used.
 */
final class UpdateChecker {

    private static final String TAG = "GXAppUpdate";

    /** Where the published version lives. Must stay HTTPS; there is no other configuration. */
    static final String UPDATE_URL =
        "https://raw.githubusercontent.com/redaf12-Decode/Generals-Android-Port/New-app/update.json";

    /** The detached signature of {@link #UPDATE_URL}'s bytes (base64 ECDSA-P256/SHA-256). */
    static final String UPDATE_SIGNATURE_URL =
        "https://raw.githubusercontent.com/redaf12-Decode/Generals-Android-Port/New-app/update.json.sig";

    /**
     * GeneralsX @feature Android security 10/11/2026 The public half of the update-metadata
     * signing key. update.json is unsigned data from the network; without this check a
     * substitution on the way (or in the repository) could hand the launcher an APK URL, a
     * package name and a checksum of the attacker's choosing. The private key never enters the
     * repository -- it is a GitHub Actions secret and the metadata is signed at release time
     * (scripts/update/generate-update-json.py + the release workflow). The format is the same
     * SubjectPublicKeyInfo (DER, base64) the engine-update manifest already uses.
     */
    static final String PUBLIC_KEY_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE/KZ+Tz9g/zR2Fp5E3drAjbOuJXveYBUsbVzbVfFZLT1AZI3SniD6OUw5gQSmB68unf5HBbrY5tw3WKa6IWDOpw==";

    /** A few kilobytes is generous for this file; anything larger is not ours and is refused. */
    private static final int MAX_BYTES = 128 * 1024;

    /** A release APK is ~60 MB today; refuse anything that could not plausibly be one. */
    static final long MAX_APK_BYTES = 512L * 1024L * 1024L;

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 10000;

    private static final String PREFS = "gx_app_update";
    private static final String KEY_NOTIFIED = "notified_version_code";

    /** Once per process, however many times the launcher screen is created. */
    private static boolean sCheckedThisSession;

    private UpdateChecker() {
    }

    /**
     * GeneralsX @feature Android port 09/11/2026 Phase 2: the GitHub repository whose Releases
     * host the APK asset. Only an apkUrl under this prefix is accepted, so the remote JSON cannot
     * point the download anywhere else. Must stay a plain https:// host prefix.
     */
    static final String APK_URL_PREFIX = "https://github.com/redaf12-Decode/Generals-Android-Port/";

    /** The exact host apkUrl must name; a prefix check alone can be spoofed (see isAcceptableApkUrl). */
    static final String TRUSTED_APK_HOST = "github.com";

    /** The path prefix under that host a release asset must live under. */
    static final String TRUSTED_APK_PATH_PREFIX = "/redaf12-Decode/Generals-Android-Port/releases/download/";

    /** The remote version, or null when the check could not produce one. */
    static final class Update {
        final int versionCode;
        final String versionName;
        final List<String> releaseNotes;
        // Phase 2: the downloadable APK, when the release publishes one. All four are optional
        // together -- a JSON without them still drives Phase 1's notification, it just has no
        // Install action.
        final String packageName;
        final String apkUrl;
        final String apkSha256;
        final long apkSizeBytes;

        Update(int versionCode, String versionName, List<String> releaseNotes,
               String packageName, String apkUrl, String apkSha256, long apkSizeBytes) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.releaseNotes = releaseNotes;
            this.packageName = packageName;
            this.apkUrl = apkUrl;
            this.apkSha256 = apkSha256;
            this.apkSizeBytes = apkSizeBytes;
        }

        /** True when this update carries a download this launcher is willing to fetch. */
        boolean hasApk() {
            return !apkUrl.isEmpty();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Version information

    /** The version code Android has for this installed package, or 0 if it cannot be read. */
    static int installedVersionCode(Context ctx) {
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            long code = info.getLongVersionCode();
            return code > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) code;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /** The installed version name, or "" if it cannot be read. */
    static String installedVersionName(Context ctx) {
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    /** Numeric comparison of the remote code against the installed one. Never uses the name. */
    static boolean isNewer(Update update, int installedVersionCode) {
        return update != null && update.versionCode > installedVersionCode;
    }

    // ---------------------------------------------------------------------------------------
    // Parsing

    /**
     * Reads an {@link Update} out of the raw JSON, or null when it is malformed or its version
     * code is missing, negative or not a plain non-negative integer.
     */
    static Update parse(String json) {
        if (json == null) {
            return null;
        }
        JSONObject obj;
        try {
            obj = new JSONObject(json);
        } catch (JSONException e) {
            return null;
        }
        int versionCode = readVersionCode(obj);
        if (versionCode < 0) {
            return null;
        }
        String versionName = obj.optString("versionName", "").trim();

        List<String> notes = new ArrayList<>();
        JSONArray array = obj.optJSONArray("releaseNotes");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                Object item = array.opt(i);
                if (item instanceof String) {
                    String note = ((String) item).trim();
                    if (!note.isEmpty()) {
                        notes.add(note);
                    }
                }
            }
        }
        // Phase 2 fields. They are read strictly: an apkUrl that is missing, not an https URL on
        // this project's GitHub host, whose sha256 is not 64 hex characters (and not the
        // all-zero placeholder), whose size is zero/negative/implausibly large, or whose
        // packageName is missing or malformed drops the whole download (leaving apkUrl empty),
        // so an Update either carries a fully-specified, fetchable APK or none.
        String packageName = obj.optString("packageName", "").trim();
        String apkUrl = obj.optString("apkUrl", "").trim();
        String apkSha256 = obj.optString("apkSha256", "").trim().toLowerCase(Locale.ROOT);
        long apkSizeBytes = obj.optLong("apkSizeBytes", 0L);
        boolean apkOk = isAcceptableApkUrl(apkUrl)
            && isUsableSha256(apkSha256)
            && isUsableApkSize(apkSizeBytes)
            && isPackageName(packageName);
        if (!apkOk) {
            apkUrl = "";
            apkSha256 = "";
            apkSizeBytes = 0L;
        }

        return new Update(versionCode, versionName, Collections.unmodifiableList(notes),
            packageName, apkUrl, apkSha256, apkSizeBytes);
    }

    /**
     * GeneralsX @bugfix Android security 10/11/2026 Parses the download URL as a real URI and
     * checks the scheme, host and path separately, instead of a string-prefix test. A prefix
     * test accepts "https://github.com.evil.example/..." and "https://github.com@evil.example/",
     * both of which a naive downloader would fetch from the attacker; comparing URI components
     * cannot be fooled that way. Only an https URL, on github.com exactly, under this
     * repository's release-download path, is accepted.
     */
    static boolean isAcceptableApkUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        try {
            URI uri = new URI(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            if (!TRUSTED_APK_HOST.equalsIgnoreCase(uri.getHost())) {
                return false;
            }
            if (uri.getUserInfo() != null) {
                return false;
            }
            int port = uri.getPort();
            if (port != -1 && port != 443) {
                return false;
            }
            String path = uri.getPath();
            return path != null && path.startsWith(TRUSTED_APK_PATH_PREFIX);
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** Exactly 64 hexadecimal characters and not the all-zero placeholder. */
    static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }

    /** A real digest: correct shape and not the "0000...0" placeholder update.json shipped with. */
    static boolean isUsableSha256(String value) {
        return isSha256(value) && !value.matches("0{64}");
    }

    /** A believable APK size: greater than zero and no larger than a release could plausibly be. */
    static boolean isUsableApkSize(long bytes) {
        return bytes > 0 && bytes <= MAX_APK_BYTES;
    }

    /** A plausible Android package name: dot-separated identifiers, no scheme/space/separator. */
    static boolean isPackageName(String value) {
        return value != null
            && value.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    }

    /**
     * A version code accepted as a JSON number or as a string of digits. Anything else -- a sign,
     * a decimal point, an exponent, an empty string, a value past Integer.MAX_VALUE -- is refused
     * rather than coerced.
     */
    private static int readVersionCode(JSONObject obj) {
        Object raw = obj.opt("versionCode");
        if (raw instanceof Number) {
            double value = ((Number) raw).doubleValue();
            // A fractional version code (1.5) is as invalid as a negative one; only whole
            // numbers up to Integer.MAX_VALUE are accepted.
            if (value != Math.floor(value) || value < 0 || value > Integer.MAX_VALUE) {
                return -1;
            }
            return (int) value;
        }
        if (raw instanceof String) {
            String text = ((String) raw).trim();
            if (!text.matches("\\d+")) {
                return -1;
            }
            try {
                long value = Long.parseLong(text);
                return value >= 0 && value <= Integer.MAX_VALUE ? (int) value : -1;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------------------------------
    // Checking

    /**
     * Blocking; call off the UI thread. Returns the remote version only when it is newer than the
     * installed one, otherwise null. Performs at most one network request per application session.
     */
    static Update check(Context ctx) {
        synchronized (UpdateChecker.class) {
            if (sCheckedThisSession) {
                return null;
            }
            sCheckedThisSession = true;
        }
        try {
            byte[] body = fetch(UPDATE_URL, MAX_BYTES);
            byte[] sig = fetch(UPDATE_SIGNATURE_URL, 8 * 1024);
            if (!verify(body, sig)) {
                // GeneralsX @feature Android security 10/11/2026 Unsigned or wrongly signed
                // metadata is refused outright: an update that cannot be proven ours is not
                // offered at all, not offered with a warning.
                Log.w(TAG, "update.json signature did not verify; ignoring the update");
                return null;
            }
            Update update = parse(new String(body, StandardCharsets.UTF_8));
            return isNewer(update, installedVersionCode(ctx)) ? update : null;
        } catch (IOException e) {
            // Offline, a timeout, an HTTP error: nothing to show and nothing to fix.
            Log.i(TAG, "update check skipped: " + e);
            return null;
        } catch (RuntimeException e) {
            // A JSON shape this class did not expect must not take the launcher down with it.
            Log.i(TAG, "update check skipped: " + e);
            return null;
        }
    }

    /**
     * GeneralsX @feature Android security 10/11/2026 Verifies a detached base64 ECDSA-P256/SHA-256
     * signature over the exact bytes of update.json against {@link #PUBLIC_KEY_B64}. The same
     * scheme the engine-update manifest already uses (UpdateManager.verify), so one key can sign
     * both, but the public key is pinned in this class so a change to one does not silently change
     * the other's trust.
     */
    static boolean verify(byte[] data, byte[] signatureText) {
        return verify(data, signatureText, PUBLIC_KEY_B64);
    }

    /** As {@link #verify(byte[], byte[])}, but against a caller-supplied public key (tests). */
    static boolean verify(byte[] data, byte[] signatureText, String publicKeyB64) {
        if (data == null || signatureText == null || signatureText.length == 0
                || publicKeyB64 == null) {
            return false;
        }
        try {
            byte[] keyBytes = Base64.decode(publicKeyB64, Base64.DEFAULT);
            PublicKey key = KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(keyBytes));
            byte[] sig = Base64.decode(
                new String(signatureText, StandardCharsets.US_ASCII).trim(), Base64.DEFAULT);
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(key);
            verifier.update(data);
            return verifier.verify(sig);
        } catch (Exception e) {
            Log.w(TAG, "signature check failed", e);
            return false;
        }
    }

    /** Runs {@link #check} on a background thread and shows the dialog on the UI thread. */
    static void maybeCheck(final Activity activity) {
        new Thread(() -> {
            final Update update = check(activity);
            if (update == null) {
                return;
            }
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                if (!shouldNotify(activity, update)) {
                    return;
                }
                UpdateDialog.show(activity, update);
            });
        }, "gx-app-update").start();
    }

    // ---------------------------------------------------------------------------------------
    // Notification memory

    /** The version code whose notification the user has already seen, or 0. */
    static int notifiedVersionCode(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_NOTIFIED, 0);
    }

    static void markNotified(Context ctx, int versionCode) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_NOTIFIED, versionCode).apply();
    }

    /** True unless this exact version has already been shown (a newer one always notifies). */
    static boolean shouldNotify(Context ctx, Update update) {
        return update != null && update.versionCode > notifiedVersionCode(ctx);
    }

    // ---------------------------------------------------------------------------------------
    // Transfer

    private static byte[] fetch(String url, int maxBytes) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "application/json");
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status + " for " + url);
            }
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > maxBytes) {
                        throw new IOException("update.json is larger than " + maxBytes + " bytes");
                    }
                }
                return out.toByteArray();
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
