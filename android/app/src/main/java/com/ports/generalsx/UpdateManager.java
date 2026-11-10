package com.ports.generalsx;

import android.content.Context;

/**
 * GeneralsX @refactor Android port 10/11/2026 The community data patch check.
 *
 * This class used to be the signed-manifest engine update system: it read a signed
 * {@code manifest.json} from a MYSOREZ repository's {@code updates} branch, applied remote
 * settings to {@code files/update/remote_config.ini}, and downloaded a newer engine. That whole
 * mechanism -- its repository, its branch, its signing key and the engine it delivered -- was
 * obsolete and has been removed. The application is updated through a real GitHub Release now
 * ({@link UpdateChecker}), and the game data comes straight from the GeneralsOnline CDN
 * ({@link DataPackInstaller}).
 *
 * What is left is the one job the multiplayer screen still asks for: notice whether the CDN has a
 * newer data patch than the one installed. Nothing here is signed and nothing here downloads --
 * the patch itself is installed by {@link DataPackInstaller}, which verifies its own SHA-256.
 */
final class UpdateManager {

    private UpdateManager() {
    }

    static final class Result {
        boolean ok;
        String error;
        boolean offline;            // no network: nothing changed
        String datapackAvailable;   // newer community data on the CDN, not yet installed
        String datapackInstalled;   // community data updated by this check
    }

    /** The newest patch version the CDN reported at the last check, or null. */
    static String datapackLatestSeen(Context ctx) {
        return DataPackInstaller.latestVersionSeen(ctx);
    }

    /** The CDN has a patch version other than the installed one (as of the last check). */
    static boolean datapackNewerAvailable(Context ctx) {
        String installed = DataPackInstaller.installedVersion(ctx);
        String latest = datapackLatestSeen(ctx);
        return installed != null && latest != null && !latest.equals(installed);
    }

    /**
     * An installed patch is out of date when the CDN has another version. Blocking; call off the
     * UI thread.
     */
    static boolean datapackUpdateWanted(Context ctx) {
        String installed = DataPackInstaller.installedVersion(ctx);
        if (installed == null) {
            return false;
        }
        String latest = datapackLatestSeen(ctx);
        return latest != null && !latest.equals(installed);
    }

    static boolean isUnmeteredNetwork(Context ctx) {
        android.net.ConnectivityManager cm =
            (android.net.ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        return cm != null && !cm.isActiveNetworkMetered();
    }

    /**
     * Records the version the CDN offers and, when the caller asked and the network is unmetered,
     * installs it. Blocking; call off the UI thread.
     */
    static Result checkDatapackOnly(Context ctx, boolean install, DataPackInstaller.Progress progress) {
        Result r = new Result();
        checkDatapack(ctx, r, install, progress);
        r.ok = r.error == null;
        return r;
    }

    private static void checkDatapack(Context ctx, Result r, boolean install,
                                      DataPackInstaller.Progress progress) {
        if (DataPackInstaller.installedVersion(ctx) == null) {
            return;
        }
        String latest = DataPackInstaller.latestVersion();
        if (latest == null) {
            return;
        }
        DataPackInstaller.noteLatestVersion(ctx, latest);
        if (!datapackUpdateWanted(ctx)) {
            return;
        }
        if (!install || !SetupActivity.hasStorageAccess(ctx)) {
            r.datapackAvailable = latest;
            return;
        }
        // The card's own check and this one can both get here at once; the second one waits for
        // the first and then finds nothing left to do instead of downloading it again.
        synchronized (DataPackInstaller.INSTALL_LOCK) {
            if (!datapackUpdateWanted(ctx)) {
                return;
            }
            DataPackInstaller.Result result = DataPackInstaller.install(ctx, progress);
            if (result.ok) {
                r.datapackInstalled = result.version;
            } else {
                r.datapackAvailable = latest;
            }
        }
    }

    /**
     * A check that does not install: records the offered version and reports whether it is newer.
     * Blocking; call off the UI thread.
     */
    static Result checkDatapackAvailability(Context ctx) {
        Result r = new Result();
        r.ok = true;
        try {
            if (DataPackInstaller.installedVersion(ctx) == null) {
                return r;
            }
            String latest = DataPackInstaller.latestVersion();
            if (latest == null) {
                return r;
            }
            DataPackInstaller.noteLatestVersion(ctx, latest);
            if (datapackNewerAvailable(ctx)) {
                r.datapackAvailable = latest;
            }
        } catch (RuntimeException e) {
            r.ok = false;
            r.error = e.getClass().getSimpleName();
        }
        return r;
    }
}
