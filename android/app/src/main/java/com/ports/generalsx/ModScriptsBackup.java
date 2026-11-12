/*
**  Command & Conquer Generals Zero Hour(tm)
**  Copyright 2025 Electronic Arts Inc.
**
**  This program is free software: you can redistribute it and/or modify
**  it under the terms of the GNU General Public License as published by
**  the Free Software Foundation, either version 3 of the License, or
**  (at your option) any later version.
*/

package com.ports.generalsx;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * GeneralsX @feature Android port 07/11/2026 Optional scripts-folder backup for mods.
 *
 * <p>Some total conversions ship their own {@code Data\Scripts} content and break when the game
 * folder's loose {@code Data\Scripts} directory (the computer opponents' scripts,
 * {@code SkirmishScripts.scb} -- see SetupActivity.hasSkirmishScripts) is still there beside it.
 * With the option switched on, a mod being switched on <em>renames</em> the game folder's
 * {@code Data\Scripts} to {@code Data\scriptsbackup} instead of deleting or overwriting anything,
 * and switching the last mod off renames it back. Nothing is ever deleted and an existing
 * {@code scriptsbackup} is never overwritten.
 *
 * <p>The rename is a plain {@link File#renameTo} inside the same {@code data} directory, so it
 * carries the directory and every file in it across without copying. Because the rename is the
 * whole operation, the only state that has to survive a crash is <em>which</em> folder was moved
 * aside and what it was called; that is kept in a small marker file next to the launcher's other
 * markers ({@link #STATE_FILE}), and {@link #sync} re-derives the right end state from it on the
 * next launch. A rename that fails, and a folder pair that a player has left in a conflicting
 * state by hand, both leave the filesystem untouched and are reported to the caller -- and neither
 * blocks the mod: moving the folder is a convenience, never a precondition for switching a mod on.
 *
 * <p>Ownership follows the mods: the folder is put aside only while the game that owns it has at
 * least one mod switched on ({@link ModManager#anyEnabled}). Switching the option off, or the
 * last mod of that game, restores it. The selected game's folder is the one acted on, and the
 * marker lets a folder left aside by the other game's last session be repaired as well.
 */
final class ModScriptsBackup {
    /** The game folder's loose scripts directory, and the name it is moved aside to. */
    static final String SCRIPTS_DIR = "scripts";
    static final String BACKUP_DIR = "scriptsbackup";

    /**
     * The two spellings of the game's {@code data} directory the port meets. Retail is
     * {@code Data} (Windows), the launcher's own language-pack folder is lower-case {@code data},
     * and the engine matches either; the retail spelling is preferred when both are present.
     */
    private static final String DATA_DIR = "data";
    private static final String DATA_DIR_CAPITAL = "Data";

    private static final String PREF_ENABLED = "mods_scripts_backup";
    private static final String STATE_FILE = "mods_scripts_backup_state";
    private static final String TAG = "ModScriptsBackup";

    /** What one reconcile pass did, so the caller can report it and react to a failure. */
    static final class Result {
        final boolean renamed;
        final boolean restored;
        final boolean conflict;
        final boolean failed;

        private Result(boolean renamed, boolean restored, boolean conflict, boolean failed) {
            this.renamed = renamed;
            this.restored = restored;
            this.conflict = conflict;
            this.failed = failed;
        }

        static final Result NONE = new Result(false, false, false, false);
        static final Result RENAMED = new Result(true, false, false, false);
        static final Result RESTORED = new Result(false, true, false, false);
        static final Result CONFLICT = new Result(false, false, true, false);
        static final Result FAILED = new Result(false, false, false, true);
    }

    /**
     * GeneralsX @feature Android port 12/11/2026 The recovery state of the selected game's scripts
     * folder, derived from the filesystem rather than from a remembered intent, so recovery after a
     * crash inspects what is actually on disk instead of blindly repeating the last operation.
     */
    enum State {
        /** {@code Data\Scripts} is in place and there is no backup: the steady state with no mods. */
        ORIGINAL_PRESENT,
        /** {@code Data\scriptsbackup} is in place and there is no scripts folder: the steady state with a mod on. */
        BACKUP_PRESENT,
        /** The scripts folder is in place but this game has a mod switched on, so it should be aside. */
        MODS_ENABLED,
        /** A marker records a moved folder that is not back yet (a crash mid-restore, or a folder since removed). */
        RESTORE_PENDING,
        /** Both folders exist: never merged or overwritten, only reported. */
        CONFLICT,
        /** The scripts folder could not be moved (a permission or filesystem error). */
        ERROR
    }

    private ModScriptsBackup() {}

    /**
     * GeneralsX @feature Android port 12/11/2026 The state of the selected game's scripts folder,
     * read from the filesystem. This is the value the recovery decision is made from, so a crash at
     * any point in a rename leaves a state that the next launch can classify and finish.
     */
    static State inspectState(Context ctx) {
        File data = dataDir(ctx);
        if (data == null) {
            return State.ORIGINAL_PRESENT;  // no game folder chosen yet: nothing to reconcile
        }
        boolean scriptsThere = scriptsDir(ctx) != null;
        boolean backupThere = backupDir(ctx) != null;
        if (scriptsThere && backupThere) {
            return State.CONFLICT;
        }
        if (backupThere) {
            return State.BACKUP_PRESENT;
        }
        if (scriptsThere) {
            if (!ModManager.anyEnabled(ctx)) {
                return State.ORIGINAL_PRESENT;
            }
            // The mods want the folder aside: this is a state to reconcile. Report ERROR now when
            // the data directory plainly cannot be written, so the caller can warn before trying.
            return data.canWrite() ? State.MODS_ENABLED : State.ERROR;
        }
        // Neither folder is there. A marker that still records this game means a restore never
        // finished (or the folder was removed by hand): a state to reconcile, not a fresh install.
        return recordedGamePath(ctx) != null ? State.RESTORE_PENDING : State.ORIGINAL_PRESENT;
    }

    /**
     * GeneralsX @feature Android port 12/11/2026 The backup folder that is a valid stand-in for the
     * given game folder's scripts, or null. It is valid only when a {@code scriptsbackup} directory
     * exists under that folder's data directory, holds {@code SkirmishScripts.scb}, and the game
     * that owns the folder has at least one mod switched on -- which is the only reason the folder
     * is set aside. A backup left by the other game, or with the mods off, is not a stand-in, so a
     * stale or unrelated backup never masks a genuinely missing scripts folder.
     */
    static File activeBackupWithSkirmish(Context ctx, File game) {
        if (game == null || !game.isDirectory() || !ModManager.anyEnabledForPath(ctx, game.getAbsolutePath())) {
            return null;
        }
        File data = findDataDir(game);
        File backup = data == null ? null : findChild(data, BACKUP_DIR);
        if (backup == null || !backup.isDirectory()) {
            return null;
        }
        return SetupActivity.hasSkirmishScripts(backup) ? backup : null;
    }

    // ------------------------------------------------------------------ option

    /** The Mods screen switch, kept across restarts like every other launcher preference. */
    static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(PREF_ENABLED, false);
    }

    static void setEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(PREF_ENABLED, enabled).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- the pair

    /** The game folder's {@code data} directory, or null while no game folder is chosen. */
    static File dataDir(Context ctx) {
        File game = ModManager.gameRoot(ctx);
        if (game == null || !game.isDirectory()) {
            return null;
        }
        return findDataDir(game);
    }

    /**
     * The game folder's {@code data} directory. A retail install spells it {@code Data} (the game
     * was made on Windows), while the launcher itself installs its language packs into a lower-case
     * {@code data} beside it, and the engine matches either spelling. So the two can be present at
     * once, and the one to act on is the one that actually holds the scripts: taking the exact
     * lower-case match blindly renamed the launcher's language folder and left the real
     * {@code Data/Scripts} untouched, which is why the folder was never disabled.
     *
     * <p>When neither spelling holds a scripts folder yet (nothing to move, or a fresh install),
     * the retail {@code Data} spelling is preferred over the launcher's lower-case {@code data},
     * because that is where the game's own {@code Scripts} live; the lower-case folder is the
     * launcher's own and holds only language packs.
     */
    private static File findDataDir(File game) {
        File[] children = game.listFiles();
        if (children == null) {
            return null;
        }
        File first = null;
        File retail = null;
        File withScripts = null;
        File withBackup = null;
        for (File child : children) {
            if (!child.isDirectory() || !child.getName().equalsIgnoreCase(DATA_DIR)) {
                continue;
            }
            if (first == null) {
                first = child;
            }
            if (retail == null && child.getName().equals(DATA_DIR_CAPITAL)) {
                retail = child;
            }
            if (withScripts == null && findChild(child, SCRIPTS_DIR) != null) {
                withScripts = child;
            }
            if (withBackup == null && findChild(child, BACKUP_DIR) != null) {
                withBackup = child;
            }
        }
        if (withScripts != null) {
            return withScripts;
        }
        if (withBackup != null) {
            return withBackup;
        }
        return retail != null ? retail : first;
    }

    /** {@code <game>/Data\Scripts} as a directory on disk (any letter case), or null. */
    static File scriptsDir(Context ctx) {
        File data = dataDir(ctx);
        File found = data == null ? null : findChild(data, SCRIPTS_DIR);
        return found != null && found.isDirectory() ? found : null;
    }

    /** {@code <game>/Data\scriptsbackup} as a directory on disk (any letter case), or null. */
    static File backupDir(Context ctx) {
        File data = dataDir(ctx);
        File found = data == null ? null : findChild(data, BACKUP_DIR);
        return found != null && found.isDirectory() ? found : null;
    }

    /** The folder the backup will be renamed back to: the recorded name, else "scripts". */
    private static String restoreName(Context ctx, File data) {
        String recorded = recordedName(ctx);
        if (recorded != null && !recorded.isEmpty()) {
            return recorded;
        }
        return SCRIPTS_DIR;
    }

    // ------------------------------------------------------------------- sync

    /**
     * Brings the selected game's folder in line with its switched-on mods. Safe to call at any
     * time and the single entry point for both the Mods screen and the app start, so an interrupted
     * operation heals instead of leaving a folder half moved.
     *
     * <p>The option follows the mods rather than being set by hand: any mod switched on turns it on
     * (and moves the folder aside), and only the <em>last</em> mod switched off turns it off again
     * (and brings the folder back). Several mods on therefore keep it on until the final one goes
     * off, which is what a player sees on the Mods screen.
     */
    static Result sync(Context ctx) {
        boolean anyOn = ModManager.anyEnabled(ctx);
        setEnabled(ctx, anyOn);
        return reconcile(ctx, SetupActivity.getSavedGamePath(ctx), anyOn);
    }

    /**
     * Forces the folder aside now, for the moment a mod is switched on -- before the switch is
     * saved, so a failure can stop the activation rather than leave it half done. The option is
     * switched on with it, because it follows the mods.
     */
    static Result apply(Context ctx) {
        setEnabled(ctx, true);
        return reconcile(ctx, SetupActivity.getSavedGamePath(ctx), true);
    }

    /**
     * Forces the folder back now, for the moment the last mod is switched off -- before the switch
     * is saved, so the two never disagree. The option is switched off with it.
     */
    static Result restore(Context ctx) {
        setEnabled(ctx, false);
        return reconcile(ctx, SetupActivity.getSavedGamePath(ctx), false);
    }

    /**
     * Repairs a folder left aside by an earlier session whose game no longer wants it (the option
     * was switched off, or its mods were all switched off, before the process died), and brings the
     * selected game's folder in line. Runs at startup, before the game can read either folder.
     */
    static void recover(Context ctx) {
        String selected = SetupActivity.getSavedGamePath(ctx);
        String recorded = recordedGamePath(ctx);
        if (recorded != null && !recorded.equals(selected)) {
            // The folder belongs to the other game: restore it unless that game still has mods on.
            if (!ModManager.anyEnabledForPath(ctx, recorded)) {
                reconcile(ctx, recorded, false);
            }
        }
        sync(ctx);
    }

    /**
     * The one filesystem step: with {@code wantBackup} set, {@code Data\Scripts} becomes
     * {@code Data\scriptsbackup} if it is there and no backup is; otherwise
     * {@code Data\scriptsbackup} becomes the scripts folder again if the backup is there and no
     * scripts folder is. Both present, or a rename that throws, changes nothing.
     */
    static Result reconcile(Context ctx, String gamePath, boolean wantBackup) {
        File data = gamePath == null || gamePath.trim().isEmpty()
            ? null : findDataDir(new File(gamePath));
        if (data == null || !data.isDirectory()) {
            // No game folder (or no data directory): there is nothing to move, and a stale marker
            // from a folder that has since gone must not keep pointing at it.
            clearState(ctx);
            return Result.NONE;
        }
        File scripts = findChild(data, SCRIPTS_DIR);
        File backup = findChild(data, BACKUP_DIR);
        boolean scriptsThere = scripts != null && scripts.isDirectory();
        boolean backupThere = backup != null && backup.isDirectory();

        if (wantBackup) {
            if (scriptsThere && backupThere) {
                // A player left both behind (an older backup, or a scripts folder re-created while
                // one was in place). Never overwrite the backup and never delete the scripts.
                android.util.Log.w(TAG, "both " + scripts + " and " + backup + " exist; leaving them alone");
                return Result.CONFLICT;
            }
            if (scriptsThere) {
                if (!rename(scripts, new File(data, BACKUP_DIR))) {
                    return Result.FAILED;
                }
                writeState(ctx, gamePath, scripts.getName());
                return Result.RENAMED;
            }
            if (backupThere) {
                // Already aside (a restart with the mods still on): remember it, do not re-rename.
                writeState(ctx, gamePath, restoreName(ctx, data));
                return Result.NONE;
            }
            // Neither folder is there (a player removed both, or the game data was moved): a marker
            // pointing at this folder is stale.
            if (gamePath.equals(recordedGamePath(ctx))) {
                clearState(ctx);
            }
            return Result.NONE;
        }

        if (backupThere && scriptsThere) {
            android.util.Log.w(TAG, "both " + scripts + " and " + backup + " exist; cannot restore");
            return Result.CONFLICT;
        }
        if (backupThere) {
            String name = restoreName(ctx, data);
            if (!rename(backup, new File(data, name))) {
                return Result.FAILED;
            }
            clearState(ctx);
            return Result.RESTORED;
        }
        // Nothing to restore. A marker pointing at this folder is stale.
        if (gamePath.equals(recordedGamePath(ctx))) {
            clearState(ctx);
        }
        return Result.NONE;
    }

    /** The name the scripts folder had before it was moved aside, or null when unknown. */
    static String recordedName(Context ctx) {
        String[] state = readState(ctx);
        return state == null ? null : state[1];
    }

    /** The game folder a marker points at, or null. */
    static String recordedGamePath(Context ctx) {
        String[] state = readState(ctx);
        return state == null ? null : state[0];
    }

    // ---------------------------------------------------------------- plumbing

    /** A direct child by name, exact match first, then any letter case; null when absent. */
    private static File findChild(File parent, String name) {
        File exact = new File(parent, name);
        if (exact.exists()) {
            return exact;
        }
        File[] children = parent.listFiles();
        if (children == null) {
            return null;
        }
        for (File child : children) {
            if (child.getName().equalsIgnoreCase(name)) {
                return child;
            }
        }
        return null;
    }

    /**
     * A same-directory rename that never overwrites. {@link java.nio.file.Files#move} is tried
     * first because it reports why a move failed (a read-only or unsupported filesystem) instead
     * of the silent {@code false} {@link File#renameTo} returns, and it is the call that behaves
     * on the FUSE-backed shared storage Android exposes at {@code /storage/emulated/0}; a plain
     * {@code renameTo} remains the fallback for anything NIO refuses.
     */
    private static boolean rename(File from, File to) {
        if (to.exists()) {
            return false;
        }
        try {
            java.nio.file.Files.move(from.toPath(), to.toPath());
            return true;
        } catch (IOException | SecurityException e) {
            android.util.Log.w(TAG, "Files.move " + from + " -> " + to + " failed: " + e);
        }
        try {
            if (from.renameTo(to)) {
                return true;
            }
            android.util.Log.w(TAG, "could not rename " + from + " to " + to);
            return false;
        } catch (SecurityException e) {
            android.util.Log.w(TAG, "could not rename " + from + " to " + to, e);
            return false;
        }
    }

    private static File stateFile(Context ctx) {
        return new File(ctx.getFilesDir(), STATE_FILE);
    }

    /**
     * GeneralsX @feature Android port 12/11/2026 Writes the recovery marker atomically: a temp file
     * in the same directory is written and then renamed over the marker, so a crash while writing
     * never leaves a half-written marker that recovery would misread. The rename is the only step
     * that can be interrupted, and a same-directory rename is atomic on every filesystem this port
     * runs on, so the marker is either the old value or the new one.
     */
    private static void writeState(Context ctx, String gamePath, String originalName) {
        File file = stateFile(ctx);
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp, false),
                StandardCharsets.UTF_8)) {
            w.write(gamePath);
            w.write('\n');
            w.write(originalName == null ? SCRIPTS_DIR : originalName);
            w.write('\n');
        } catch (IOException e) {
            android.util.Log.w(TAG, "could not write the scripts backup marker", e);
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(file)) {
            // Fall back to an in-place write rather than lose the marker; the window is tiny.
            try (Writer w = new OutputStreamWriter(new FileOutputStream(file, false),
                    StandardCharsets.UTF_8)) {
                w.write(gamePath);
                w.write('\n');
                w.write(originalName == null ? SCRIPTS_DIR : originalName);
                w.write('\n');
            } catch (IOException e) {
                android.util.Log.w(TAG, "could not write the scripts backup marker", e);
            } finally {
                tmp.delete();
            }
        }
    }

    /** The marker as {game folder, original scripts name}, or null when there is none. */
    private static String[] readState(Context ctx) {
        File file = stateFile(ctx);
        if (!file.isFile()) {
            return null;
        }
        char[] buf = new char[4096];
        try (Reader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            int n = 0, got;
            while (n < buf.length && (got = r.read(buf, n, buf.length - n)) > 0) {
                n += got;
            }
            String text = new String(buf, 0, n).replace("\r", "");
            String[] lines = text.split("\n", 2);
            if (lines.length < 2 || lines[0].trim().isEmpty()) {
                return null;
            }
            return new String[] { lines[0].trim(), lines[1].trim() };
        } catch (IOException e) {
            android.util.Log.w(TAG, "could not read the scripts backup marker", e);
            return null;
        }
    }

    private static void clearState(Context ctx) {
        stateFile(ctx).delete();
    }

    /** The backup folder's absolute path for the chosen game folder, for a message; "" if none. */
    static String backupPathOf(Context ctx) {
        File data = dataDir(ctx);
        return data == null ? "" : new File(data, BACKUP_DIR).getAbsolutePath();
    }
}
