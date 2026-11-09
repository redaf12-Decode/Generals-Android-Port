// GeneralsX @feature Android port 09/11/2026
//
// Phase 2 of the update system: fetch the APK a release publishes, prove it is the right one, and
// hand it to Android's own package installer for the user to approve.
//
// The shape of this class is set by one rule: a downloaded file is never trusted. It is checked
// against the size, the SHA-256 and the package name/version the remote JSON promised, and then
// against the signing certificate of the copy already installed. Only a file that passes every
// check is offered for installation, and even then the install is Android's decision and the
// user's -- nothing here installs silently, uses root, or bypasses the platform installer.
//
// It reuses the project's existing transport (HttpURLConnection, as DataPackInstaller does) rather
// than adding a download library, and stores the APK in the app's own files directory, which the
// existing FileProvider already exposes read-only, so no storage permission is involved.

package com.generalsx.zerohour;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;

final class ApkInstaller {

    private ApkInstaller() {
    }

    private static final String TAG = "GxApkInstaller";

    // Same store UpdateChecker keeps its notification memory in: both describe "the update the
    // user is dealing with", and a second preferences file would only split that record.
    private static final String PREFS = "gx_app_update";
    private static final String KEY_VERIFIED_CODE = "verified_version_code";
    private static final String KEY_VERIFIED_SHA = "verified_sha256";

    /** Request codes, kept clear of the ones SetupActivity already uses (1001-1004). */
    static final int REQUEST_INSTALL = 1005;
    static final int REQUEST_INSTALL_PERMISSION = 1006;

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int BUFFER_BYTES = 64 * 1024;
    /** Kept free beyond the APK itself so a full disk fails before the download, not during. */
    private static final long HEADROOM_BYTES = 32L * 1024 * 1024;

    // ---------------------------------------------------------------------------------------
    // Failure codes. The caller turns one into a localized message via {@link #message}.

    static final String ERR_NO_APK = "no_apk";
    static final String ERR_URL = "url";
    static final String ERR_OFFLINE = "offline";
    static final String ERR_HTTP = "http";
    static final String ERR_STORAGE = "storage";
    static final String ERR_SIZE = "size";
    static final String ERR_CHECKSUM = "sha";
    static final String ERR_INVALID_APK = "invalid_apk";
    static final String ERR_PACKAGE = "package";
    static final String ERR_VERSION = "version";
    static final String ERR_SIGNATURE = "signature";

    /** The localized text for a failure code, so every screen says the same thing. */
    static String message(Context ctx, String code) {
        if (code == null) {
            code = ERR_INVALID_APK;
        }
        switch (code) {
            case ERR_NO_APK:
                return ctx.getString(R.string.update_error_no_apk);
            case ERR_URL:
                return ctx.getString(R.string.update_error_url);
            case ERR_OFFLINE:
                return ctx.getString(R.string.update_error_offline);
            case ERR_HTTP:
                return ctx.getString(R.string.update_error_server);
            case ERR_STORAGE:
                return ctx.getString(R.string.update_error_storage);
            case ERR_SIZE:
                return ctx.getString(R.string.update_error_size);
            case ERR_CHECKSUM:
                return ctx.getString(R.string.update_error_checksum);
            case ERR_PACKAGE:
                return ctx.getString(R.string.update_error_package);
            case ERR_VERSION:
                return ctx.getString(R.string.update_error_version);
            case ERR_SIGNATURE:
                return ctx.getString(R.string.update_error_signature);
            case ERR_INVALID_APK:
            default:
                return ctx.getString(R.string.update_error_invalid_apk);
        }
    }

    // ---------------------------------------------------------------------------------------
    // State

    /** One download at a time: two would race over the same file. */
    private static final Object LOCK = new Object();
    private static volatile boolean sRunning;
    private static volatile boolean sCancelled;
    private static volatile HttpURLConnection sConnection;
    /**
     * Where progress is shown and who is told about it. Held as statics, not captured by the worker
     * thread, so a download that outlives the screen it started on can be re-bound to the screen
     * that replaces it ({@link #attach}) instead of restarting.
     */
    // StaticFieldLeak: held only while a download is in flight, cleared by detach() in the
    // Activity's onDestroy, and always re-pointed at the live Activity by attach() -- so it never
    // outlives the screen it references.
    @SuppressWarnings("StaticFieldLeak")
    private static volatile Activity sUi;
    private static volatile Callbacks sCb;
    private static volatile long sLastDone;
    private static volatile long sLastTotal;
    /** The update the running download belongs to, so a recreated screen can re-attach to it. */
    private static volatile UpdateChecker.Update sUpdate;
    /** The verified APK an install is being arranged for, between the permission and the intent. */
    private static File sPendingApk;

    interface Callbacks {
        void onProgress(long bytes, long total);
        void onVerifying();
        void onReady(File apk, UpdateChecker.Update update);
        void onFailed(String code);
    }

    // ---------------------------------------------------------------------------------------
    // Files

    /** The app-private directory the APK is downloaded into; the FileProvider exposes it read-only. */
    static File updateDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "update");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "could not create " + dir);
        }
        return dir;
    }

    /** The working file for a given remote version. Named by version, so an old partial never
     *  collides with a new release. */
    static File apkFile(Context ctx, UpdateChecker.Update update) {
        return new File(updateDir(ctx), "GeneralsXZH-" + update.versionCode + ".apk");
    }

    /**
     * Deletes obsolete APKs, keeping only the newest one. The newest is either the verified APK
     * (so the Install button survives to the next launch) or the partial of the version being
     * downloaded (so an interrupted download can resume). Older leftovers are removed. Called at
     * launch, never while a download is running (that would delete the file it is filling), and it
     * reads only local state, so it needs no network and no knowledge of the current release.
     */
    static void cleanup(Context ctx) {
        File[] files = updateDir(ctx).listFiles();
        if (files == null) {
            return;
        }
        File keep = null;
        int keepCode = -1;
        for (File f : files) {
            int code = versionCodeOf(f);
            if (code > keepCode) {
                keepCode = code;
                keep = f;
            }
        }
        for (File f : files) {
            if (f != keep && !f.delete()) {
                Log.w(TAG, "could not delete " + f);
            }
        }
        // If the file the verified record points at is gone (superseded by a newer partial), drop
        // the record too, so nothing later believes a file that is not there was verified.
        int verified = verifiedVersionCode(ctx);
        if (verified > 0 && (keep == null || versionCodeOf(keep) != verified)) {
            clearVerified(ctx);
        }
    }

    /** The version code encoded in an "GeneralsXZH-<code>.apk" name, or -1 for anything else. */
    private static int versionCodeOf(File f) {
        String name = f.getName();
        if (!name.startsWith("GeneralsXZH-") || !name.endsWith(".apk")) {
            return -1;
        }
        try {
            return Integer.parseInt(name.substring("GeneralsXZH-".length(), name.length() - 4));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Download + verify

    /**
     * Downloads (or resumes), verifies and reports. Runs entirely off the main thread; every
     * callback is delivered on the UI thread. A download already in flight makes this a no-op,
     * which is the "no duplicate simultaneous downloads" guarantee.
     */
    static void start(Activity activity, UpdateChecker.Update update, Callbacks cb) {
        if (!update.hasApk()) {
            post(() -> cb.onFailed(ERR_NO_APK));
            return;
        }
        if (!UpdateChecker.isAcceptableApkUrl(update.apkUrl)) {
            post(() -> cb.onFailed(ERR_URL));
            return;
        }
        synchronized (LOCK) {
            if (sRunning) {
                return;
            }
            sRunning = true;
            sCancelled = false;
            sLastDone = 0;
            sLastTotal = 0;
            sUpdate = update;
        }
        sUi = activity;
        sCb = cb;
        new Thread(() -> run(update), "gx-apk-download").start();
    }

    /**
     * Re-binds a running download to a new screen (after a configuration change or a recreation),
     * replaying the last progress so the fresh progress dialog is not blank. Does nothing when no
     * download is running.
     */
    static void attach(Activity activity, Callbacks cb) {
        if (!sRunning) {
            return;
        }
        sUi = activity;
        sCb = cb;
        long done = sLastDone;
        long total = sLastTotal;
        if (done > 0) {
            post(() -> cb.onProgress(done, total));
        }
    }

    /** Detaches a screen that is going away, so a running download stops touching its UI. */
    static void detach(Activity activity) {
        if (sUi == activity) {
            sUi = null;
            sCb = null;
        }
    }

    /** True while a download is in flight, so a recreated screen knows to show the dialog. */
    static boolean isRunning() {
        return sRunning;
    }

    /** The update a running download belongs to, or null when none is running. */
    static UpdateChecker.Update runningUpdate() {
        return sRunning ? sUpdate : null;
    }

    /** Asks the running download to stop. The partial file is left for a later resume. */
    static void cancel() {
        sCancelled = true;
        HttpURLConnection conn = sConnection;
        if (conn != null) {
            conn.disconnect();
        }
    }

    private static void run(UpdateChecker.Update update) {
        Activity ui = sUi;
        File apk = apkFile(ui, update);
        try {
            if (isVerified(ui, update) && apk.isFile()
                    && (update.apkSizeBytes <= 0 || apk.length() == update.apkSizeBytes)) {
                // A verified APK from an earlier session: no reason to download it again.
                post(() -> cb().onReady(apk, update));
                return;
            }

            download(update, apk);
            if (sCancelled) {
                return;
            }

            post(() -> cb().onVerifying());
            String code = verify(ui, update, apk);
            if (code != null) {
                // A file that failed a check is never kept: it would be re-verified (and
                // re-failed) forever, and a corrupted partial must not become a resume base.
                deleteQuietly(apk);
                clearVerified(ui);
                post(() -> cb().onFailed(code));
                return;
            }
            markVerified(ui, update);
            post(() -> cb().onReady(apk, update));
        } catch (Cancelled ignored) {
            // Leave the partial in place so the next attempt resumes it.
        } catch (StorageException e) {
            Log.i(TAG, "not enough room: " + e);
            post(() -> cb().onFailed(ERR_STORAGE));
        } catch (IOException e) {
            Log.i(TAG, "download failed: " + e);
            post(() -> cb().onFailed(ERR_OFFLINE));
        } catch (RuntimeException e) {
            Log.i(TAG, "download failed: " + e);
            deleteQuietly(apk);
            post(() -> cb().onFailed(ERR_INVALID_APK));
        } finally {
            synchronized (LOCK) {
                sRunning = false;
            }
        }
    }

    private static void download(UpdateChecker.Update update, File dest) throws IOException {
        long existing = dest.isFile() ? dest.length() : 0;
        // Only resume a partial whose size is believable: at or under the promised total, and with
        // a total to resume towards. Otherwise start clean.
        boolean canResume = update.apkSizeBytes > 0 && existing > 0 && existing < update.apkSizeBytes;

        long free = dest.getParentFile() != null ? dest.getParentFile().getUsableSpace() : Long.MAX_VALUE;
        long needed = update.apkSizeBytes > 0 ? update.apkSizeBytes - (canResume ? existing : 0) : 0;
        if (needed > 0 && free < needed + HEADROOM_BYTES) {
            throw new StorageException();
        }

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(update.apkUrl).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "GeneralsX-Android");
            if (canResume) {
                conn.setRequestProperty("Range", "bytes=" + existing + "-");
            }

            int status = conn.getResponseCode();
            boolean resuming = canResume && status == HttpURLConnection.HTTP_PARTIAL;
            if (status != HttpURLConnection.HTTP_OK && !resuming) {
                throw new IOException("HTTP " + status);
            }
            sConnection = conn;

            long total = resuming ? update.apkSizeBytes : conn.getContentLengthLong();
            if (total <= 0) {
                total = update.apkSizeBytes;
            }
            long done = resuming ? existing : 0;
            if (!resuming) {
                deleteQuietly(dest);
            }

            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 FileOutputStream out = new FileOutputStream(dest, resuming)) {
                byte[] buffer = new byte[BUFFER_BYTES];
                long lastPercent = -1;
                int read;
                while ((read = in.read(buffer)) > 0) {
                    if (sCancelled) {
                        throw new Cancelled();
                    }
                    out.write(buffer, 0, read);
                    done += read;
                    sLastDone = done;
                    sLastTotal = total;
                    long percent = total > 0 ? done * 100 / total : -1;
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        final long d = done;
                        final long t = total;
                        post(() -> cb().onProgress(d, t));
                    }
                }
                out.flush();
            }
        } finally {
            sConnection = null;
            if (conn != null) {
                conn.disconnect();
            }
        }
        if (sCancelled) {
            throw new Cancelled();
        }
    }

    /**
     * Every check from section 5, in order, and null only when all pass. Order matters for the
     * messages: a wrong size is reported before a wrong checksum, and the cheap identity reads
     * come after the checksum so a file that is not even ours is rejected before it is parsed.
     */
    static String verify(Context ctx, UpdateChecker.Update update, File apk) {
        if (!apk.isFile() || apk.length() == 0) {
            return ERR_INVALID_APK;
        }
        // D. Size, when the release states one.
        if (update.apkSizeBytes > 0 && apk.length() != update.apkSizeBytes) {
            return ERR_SIZE;
        }
        // A. Streaming SHA-256 against the promised digest.
        String actual;
        try {
            actual = sha256(apk);
        } catch (IOException e) {
            return ERR_INVALID_APK;
        }
        if (!update.apkSha256.isEmpty() && !update.apkSha256.equalsIgnoreCase(actual)) {
            Log.i(TAG, "checksum mismatch: expected " + update.apkSha256 + " got " + actual);
            return ERR_CHECKSUM;
        }
        // B. Package identity and version, read from the APK itself.
        PackageManager pm = ctx.getPackageManager();
        PackageInfo archive;
        try {
            archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                PackageManager.GET_SIGNING_CERTIFICATES);
        } catch (RuntimeException e) {
            return ERR_INVALID_APK;
        }
        if (archive == null || archive.packageName == null) {
            return ERR_INVALID_APK;
        }
        if (!archive.packageName.equals(ctx.getPackageName())) {
            return ERR_PACKAGE;
        }
        if (!update.packageName.isEmpty() && !archive.packageName.equals(update.packageName)) {
            return ERR_PACKAGE;
        }
        long code = archive.getLongVersionCode();
        if (code != update.versionCode) {
            return ERR_VERSION;
        }
        if (code <= UpdateChecker.installedVersionCode(ctx)) {
            return ERR_VERSION;
        }
        // C. Signed by a certificate this app already trusts (rotation-aware).
        if (!signaturesMatch(ctx, archive)) {
            return ERR_SIGNATURE;
        }
        return null;
    }

    /**
     * The APK's signers must all be certificates the installed app trusts. Comparing against the
     * installed app's signing history (not just its current signer) is what makes a rotated key
     * acceptable: an update signed by the app's new key still verifies against the history that
     * records the rotation. An APK signed by anything outside that history is refused.
     */
    private static boolean signaturesMatch(Context ctx, PackageInfo archive) {
        try {
            SigningInfo apkSigning = archive.signingInfo;
            SigningInfo installed = ctx.getPackageManager()
                .getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo;
            if (apkSigning == null || installed == null) {
                return false;
            }
            Signature[] apkSigners = apkSigning.getApkContentsSigners();
            Signature[] history = installed.getSigningCertificateHistory();
            if (apkSigners == null || apkSigners.length == 0 || history == null) {
                return false;
            }
            Set<String> trusted = new HashSet<>();
            for (Signature s : history) {
                trusted.add(digest(s));
            }
            for (Signature s : apkSigners) {
                if (!trusted.contains(digest(s))) {
                    return false;
                }
            }
            return true;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
    }

    private static String digest(Signature signature) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return toHex(md.digest(signature.toByteArray()));
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    /** Streams the whole file; the buffer keeps a 60 MB APK off the heap. */
    static String sha256(File file) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_BYTES];
            int read;
            while ((read = in.read(buffer)) > 0) {
                md.update(buffer, 0, read);
            }
            return toHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    static String toHex(byte[] bytes) {
        char[] hex = "0123456789abcdef".toCharArray();
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(hex[(b >> 4) & 0xF]).append(hex[b & 0xF]);
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------------------------------
    // Verified-file memory

    /** The version code of the APK already verified and kept on disk, or 0. */
    static int verifiedVersionCode(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_VERIFIED_CODE, 0);
    }

    static boolean isVerified(Context ctx, UpdateChecker.Update update) {
        if (update.apkSha256.isEmpty()) {
            return false;
        }
        android.content.SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return p.getInt(KEY_VERIFIED_CODE, 0) == update.versionCode
            && update.apkSha256.equalsIgnoreCase(p.getString(KEY_VERIFIED_SHA, ""));
    }

    private static void markVerified(Context ctx, UpdateChecker.Update update) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_VERIFIED_CODE, update.versionCode)
            .putString(KEY_VERIFIED_SHA, update.apkSha256)
            .apply();
    }

    private static void clearVerified(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_VERIFIED_CODE)
            .remove(KEY_VERIFIED_SHA)
            .apply();
    }

    // ---------------------------------------------------------------------------------------
    // Installation

    /** True when Android will let this app ask to install a package. The API exists from 26 and
     *  minSdk here is 28, so there is no version branch to make. */
    static boolean canInstall(Activity activity) {
        return activity.getPackageManager().canRequestPackageInstalls();
    }

    /**
     * Starts the install for an already-verified APK. When the platform forbids it, shows why and
     * opens the per-app "install unknown apps" screen; the recheck happens in
     * {@link #onInstallPermissionResult} when the user comes back.
     */
    static void requestInstall(Activity activity, File apk) {
        if (canInstall(activity)) {
            launchInstaller(activity, apk);
            return;
        }
        sPendingApk = apk;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_permission_title)
            .setMessage(R.string.update_permission_message)
            .setPositiveButton(R.string.update_permission_open_settings, (d, w) -> {
                Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                intent.setData(Uri.parse("package:" + activity.getPackageName()));
                activity.startActivityForResult(intent, REQUEST_INSTALL_PERMISSION);
            })
            .setNegativeButton(R.string.update_dialog_later, null)
            .show();
    }

    /** Called from SetupActivity.onActivityResult when the settings screen returns. */
    static void onInstallPermissionResult(Activity activity, int requestCode) {
        if (requestCode != REQUEST_INSTALL_PERMISSION) {
            return;
        }
        File apk = sPendingApk;
        sPendingApk = null;
        if (apk == null || !apk.isFile()) {
            return;
        }
        if (canInstall(activity)) {
            launchInstaller(activity, apk);
        } else {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_permission_title)
                .setMessage(R.string.update_error_permission)
                .setPositiveButton(android.R.string.ok, null)
                .show();
        }
    }

    /** Hands the APK to Android's package installer through a FileProvider content URI. */
    private static void launchInstaller(Activity activity, File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(activity,
                activity.getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivityForResult(intent, REQUEST_INSTALL);
        } catch (RuntimeException e) {
            // IllegalArgumentException if the file is outside the FileProvider roots, or
            // ActivityNotFoundException if no installer exists. Neither is fatal.
            Log.i(TAG, "could not start the installer: " + e);
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_error_title)
                .setMessage(R.string.update_error_install_failed)
                .setPositiveButton(android.R.string.ok, null)
                .show();
        }
    }

    /**
     * Called when the package installer returns. It only reports; it never records the update as
     * installed -- whether the install really happened is answered by the next version check,
     * which reads the version straight from PackageManager.
     */
    static void onInstallResult(Activity activity, int resultCode) {
        int message = resultCode == Activity.RESULT_OK
            ? R.string.update_install_opened : R.string.update_install_cancelled;
        android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_LONG).show();
    }

    // ---------------------------------------------------------------------------------------
    // Helpers

    private static Callbacks cb() {
        Callbacks c = sCb;
        return c != null ? c : NOOP;
    }

    /** Delivers on the UI thread of whatever screen is currently bound. */
    private static void post(Runnable r) {
        Activity ui = sUi;
        if (ui == null) {
            return;
        }
        ui.runOnUiThread(() -> {
            Activity now = sUi;
            if (now != null && !now.isFinishing() && !now.isDestroyed()) {
                r.run();
            }
        });
    }

    private static final Callbacks NOOP = new Callbacks() {
        @Override public void onProgress(long bytes, long total) { }
        @Override public void onVerifying() { }
        @Override public void onReady(File apk, UpdateChecker.Update update) { }
        @Override public void onFailed(String code) { }
    };

    private static void deleteQuietly(File file) {
        if (file != null && file.isFile() && !file.delete()) {
            Log.w(TAG, "could not delete " + file);
        }
    }

    /** Thrown to unwind a cancelled download without an error message. */
    private static final class Cancelled extends IOException {
    }

    /** Thrown when the device has no room for the APK. */
    private static final class StorageException extends IOException {
    }
}
