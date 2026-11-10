// GeneralsX @feature Android port 10/11/2026
//
// The application update check. This is the ONE update mechanism the launcher has: it reads the
// signed update metadata published as a release asset on this project's own GitHub repository,
// verifies its ECDSA P-256 signature against a public key compiled into the app, and -- only when
// the metadata is authentic and describes a newer version -- offers the APK the metadata names.
//
// It replaces two things that are gone:
//   * the old engine-OTA system that read a signed manifest from a MYSOREZ repository's `updates`
//     branch and swapped the engine inside an installed APK (removed 10/11/2026);
//   * the earlier unsigned update.json on a branch, which a hand edit could repoint at any APK.
//
// The metadata (update.json) is untrusted until its signature verifies. Only then are its URL,
// version, size, checksum and certificate trusted, and every one of them is checked again against
// the downloaded APK in ApkInstaller before Android's installer is asked to run. A checksum alone
// would prove nothing about who wrote the metadata, which is why the signature is the gate.

package com.ports.generalsx;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class UpdateChecker {

    private static final String TAG = "GXAppUpdate";

    /** The only repository whose releases are ever considered. */
    static final String REPOSITORY = "redaf12-Decode/Generals-Android-Port";

    /** The package every accepted APK must declare. */
    static final String EXPECTED_PACKAGE = "com.ports.generalsx";

    /**
     * GeneralsX @feature Android port 10/11/2026 The public half of the metadata signing key
     * (ECDSA P-256, SubjectPublicKeyInfo/DER, base64). The matching private key lives only in the
     * UPDATE_METADATA_KEY GitHub Actions secret and never in this repository. A release signs
     * update.json with it; this constant is what makes the signature meaningful. Replacing it
     * (scripts/update/generate-metadata-key.py) invalidates every previously signed metadata.
     */
    static final String PUBLIC_KEY_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAErGnt3DRECsEmAbHMe8sezG7DL37b1KVo1MOSWC032TEMMlrDll6QR5eBosC5xeCg2T3b9k1+Rw8X59Jw9nqKDw==";

    /**
     * Where the signed metadata is published. The release workflow uploads update.json and
     * update.json.sig as assets of every release, so the newest release's copies are read here
     * through GitHub's "latest release" alias. HTTPS, this repository only, and not configurable
     * at runtime.
     */
    static final String METADATA_URL =
        "https://github.com/" + REPOSITORY + "/releases/latest/download/update.json";
    static final String METADATA_SIG_URL = METADATA_URL + ".sig";

    /** An https URL only under this repository is ever accepted as an APK download. */
    static final String APK_URL_PREFIX = "https://github.com/" + REPOSITORY + "/";

    private static final int MAX_METADATA_BYTES = 64 * 1024;
    private static final int MAX_SIG_BYTES = 4 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 10000;

    private static final String PREFS = "gx_app_update";
    private static final String KEY_NOTIFIED = "notified_version_code";

    /** Once per process, however many times the launcher screen is created. */
    private static boolean sCheckedThisSession;

    private UpdateChecker() {
    }

    /** A verified update, carrying everything ApkInstaller needs to fetch and re-check the APK. */
    static final class Update {
        final int versionCode;
        final String versionName;
        final List<String> releaseNotes;
        final String packageName;
        final String apkUrl;
        final String apkSha256;
        final long apkSizeBytes;
        final String signingCertificateSha256;

        Update(int versionCode, String versionName, List<String> releaseNotes, String packageName,
               String apkUrl, String apkSha256, long apkSizeBytes, String signingCertificateSha256) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.releaseNotes = releaseNotes;
            this.packageName = packageName;
            this.apkUrl = apkUrl;
            this.apkSha256 = apkSha256;
            this.apkSizeBytes = apkSizeBytes;
            this.signingCertificateSha256 = signingCertificateSha256;
        }

        boolean hasApk() {
            return !apkUrl.isEmpty();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Installed version

    static int installedVersionCode(Context ctx) {
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            long code = info.getLongVersionCode();
            return code > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) code;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    static String installedVersionName(Context ctx) {
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    static boolean isNewer(Update update, int installedVersionCode) {
        return update != null && update.versionCode > installedVersionCode;
    }

    // ---------------------------------------------------------------------------------------
    // Metadata parsing + verification

    /**
     * Verifies {@code signatureBase64} over the exact bytes of {@code payload} with the embedded
     * public key. Returns false for anything malformed -- a wrong key, a tampered payload, a bad
     * base64 string -- never throwing.
     */
    static boolean verify(byte[] payload, byte[] signatureBase64) {
        return verify(payload, signatureBase64, embeddedKey());
    }

    /** Verifies with an explicit key; the tests sign with a generated one. */
    static boolean verify(byte[] payload, byte[] signatureBase64, PublicKey key) {
        if (payload == null || signatureBase64 == null || payload.length == 0 || key == null) {
            return false;
        }
        try {
            byte[] signature = Base64.decode(new String(signatureBase64, StandardCharsets.US_ASCII)
                .trim(), Base64.DEFAULT);
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(key);
            verifier.update(payload);
            return verifier.verify(signature);
        } catch (Exception e) {
            Log.i(TAG, "metadata signature rejected: " + e);
            return false;
        }
    }

    /** The embedded metadata-signing public key, or null if the constant is malformed. */
    static PublicKey embeddedKey() {
        try {
            byte[] keyDer = Base64.decode(PUBLIC_KEY_B64, Base64.DEFAULT);
            return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(keyDer));
        } catch (Exception e) {
            Log.e(TAG, "the embedded metadata key is unreadable: " + e);
            return null;
        }
    }

    /**
     * Parses signed metadata, or returns null when the signature does not verify or a required
     * field is missing, malformed or points anywhere but at this repository. Nothing in the
     * returned Update has been trusted for its size or checksum yet -- that is the download's job.
     */
    static Update parseVerified(byte[] payload, byte[] signatureBase64) {
        return parseVerified(payload, signatureBase64, embeddedKey());
    }

    /** Parses with an explicit key; the tests sign with a generated one. */
    static Update parseVerified(byte[] payload, byte[] signatureBase64, PublicKey key) {
        if (!verify(payload, signatureBase64, key)) {
            Log.i(TAG, "metadata is not signed by the embedded key; ignoring");
            return null;
        }
        JSONObject obj;
        try {
            obj = new JSONObject(new String(payload, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
        if (obj.optInt("schemaVersion", 0) != 1) {
            return null;
        }
        // The document must claim this repository, and the APK it names must be an https asset of
        // it. A signed-but-wrong-repo document is still refused: the signature proves the key, not
        // that the key belongs to a repository we mean to follow.
        if (!REPOSITORY.equals(obj.optString("repository", "").trim())) {
            return null;
        }
        int versionCode = obj.optInt("versionCode", -1);
        if (versionCode < 0) {
            return null;
        }
        String versionName = obj.optString("versionName", "").trim();
        String packageName = obj.optString("packageName", "").trim();
        if (!EXPECTED_PACKAGE.equals(packageName)) {
            return null;
        }

        JSONObject apk = obj.optJSONObject("apk");
        if (apk == null) {
            return null;
        }
        String apkUrl = apk.optString("downloadUrl", "").trim();
        String apkSha = apk.optString("sha256", "").trim().toLowerCase(java.util.Locale.ROOT);
        long apkSize = apk.optLong("sizeBytes", 0L);
        String apkCert = apk.optString("signingCertificateSha256", "")
            .trim().toLowerCase(java.util.Locale.ROOT);
        if (!isAcceptableApkUrl(apkUrl) || !isSha256(apkSha) || apkSize <= 0
                || !isSha256(apkCert)) {
            return null;
        }

        List<String> notes = new ArrayList<>();
        String body = obj.optString("releaseNotes", "");
        if (!body.isEmpty()) {
            for (String line : body.split("\\r?\\n")) {
                String note = line.trim();
                if (!note.isEmpty()) {
                    notes.add(note);
                }
            }
        }

        return new Update(versionCode, versionName, Collections.unmodifiableList(notes),
            packageName, apkUrl, apkSha, apkSize, apkCert);
    }

    /** Only an https URL on this repository is accepted, so metadata cannot repoint the download. */
    static boolean isAcceptableApkUrl(String url) {
        return url != null && url.startsWith(APK_URL_PREFIX);
    }

    /** Exactly 64 hexadecimal characters, the shape of a SHA-256 digest. */
    static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }

    // ---------------------------------------------------------------------------------------
    // Checking

    /**
     * Blocking; call off the UI thread. Returns a verified newer Update, or null when there is no
     * newer version, the metadata is missing or unauthentic, or the network failed. At most one
     * network attempt per application session.
     */
    static Update check(Context ctx) {
        synchronized (UpdateChecker.class) {
            if (sCheckedThisSession) {
                return null;
            }
            sCheckedThisSession = true;
        }
        try {
            byte[] payload = fetch(METADATA_URL, MAX_METADATA_BYTES);
            byte[] signature = fetch(METADATA_SIG_URL, MAX_SIG_BYTES);
            Update update = parseVerified(payload, signature);
            return isNewer(update, installedVersionCode(ctx)) ? update : null;
        } catch (IOException e) {
            Log.i(TAG, "update check skipped: " + e);
            return null;
        } catch (RuntimeException e) {
            Log.i(TAG, "update check skipped: " + e);
            return null;
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
                        throw new IOException(url + " is larger than " + maxBytes + " bytes");
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
