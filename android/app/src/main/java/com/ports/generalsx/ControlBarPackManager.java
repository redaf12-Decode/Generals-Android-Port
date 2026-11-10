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

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * GeneralsX @feature Android port 07/11/2026 ControlBar Pack Manager (Tools -&gt; ControlBar BIG
 * Importer).
 *
 * <p>An imported package is kept whole and never moved, in
 * {@code <user data>/Controlsbar-Manager/<pack>/} -- one folder per package, holding its original
 * {@code .big} archive(s). Importing copies the archives in and stops there: nothing is extracted
 * until a package is <em>activated</em>, and no archive is ever placed in the game folder.
 *
 * <p><b>Activation</b> unpacks each archive's ControlBar files (see {@link ControlBarBig} for the
 * chain the engine reads) straight into the game folder -- the folder {@code SDL3Main.cpp}
 * {@code chdir()}s into, {@link SetupActivity#getSavedGamePath} -- where {@code FileSystem}'s
 * local-file lookup finds them before any archive. The internal paths are preserved; the only
 * change is a leading language folder ({@code Data\<token>\...}), rewritten to the language the
 * game actually reads ({@link #gameLanguage}). Nothing outside the selected files is written.
 *
 * <p><b>Safety</b> -- only one package is active at a time, and every file this manager writes is
 * recorded in a manifest ({@code Controlsbar-Manager/.installed.txt}) before it is written. A file
 * that already existed is first moved into a backup ({@code Controlsbar-Manager/.backup/}) and
 * restored on deactivation, so the game's own files are never lost. Deactivation deletes only the
 * files in the manifest -- never a broad delete of the game's data -- and removes only the folders
 * it created and left empty. {@link #sync} reconciles the manifest with the game folder, so an
 * interrupted activation heals instead of leaving a half-installed control bar.
 */
final class ControlBarPackManager {
    static final String MANAGER_DIR = "Controlsbar-Manager";
    static final String METADATA_FILE = "pack.txt";
    private static final String BACKUP_DIR = ".backup";
    private static final String MANIFEST_FILE = ".installed.txt";
    private static final String STAGING_DIR = ModImportService.INSTALLING_DIR;
    private static final String PREF_ACTIVE = "controlbar_pack_active";
    private static final String PREF_GAME = "controlbar_pack_game";

    private static final long MAX_TOTAL_BYTES = 8L * 1024L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 100000;
    private static final int BUFFER_SIZE = 64 * 1024;

    /**
     * The engine's language auto-detection order ({@code tryAutoDetectLanguage}, registry.cpp):
     * the first of these archives present in the game folder names the language, else English.
     */
    private static final String[][] LANGUAGE_BIGS = {
        { "BrazilianZH.big", "brazilian" },
        { "EnglishZH.big", "english" },
        { "GermanZH.big", "german" },
        { "FrenchZH.big", "french" },
        { "SpanishZH.big", "spanish" },
        { "ChineseZH.big", "chinese" },
        { "KoreanZH.big", "korean" },
        { "PolishZH.big", "polish" },
    };

    /** One imported package: a folder under {@code Controlsbar-Manager} holding .big archives. */
    static final class Pack {
        final File dir;
        final String name;
        final String source;
        final boolean active;
        final List<File> archives;

        Pack(File dir, String name, String source, boolean active, List<File> archives) {
            this.dir = dir;
            this.name = name;
            this.source = source;
            this.active = active;
            this.archives = archives;
        }

        int archiveCount() {
            return archives.size();
        }

        List<String> archiveNames() {
            List<String> names = new ArrayList<>();
            for (File archive : archives) {
                names.add(archive.getName());
            }
            return names;
        }
    }

    /** A staged import that has been checked and is waiting for its package folder. */
    static final class Plan {
        final File stage;
        final String name;
        final String source;
        final int archiveCount;

        Plan(File stage, String name, String source, int archiveCount) {
            this.stage = stage;
            this.name = name;
            this.source = source;
            this.archiveCount = archiveCount;
        }
    }

    /** What an activation installed. */
    static final class Result {
        final String packName;
        final int fileCount;
        final int archiveCount;

        Result(String packName, int fileCount, int archiveCount) {
            this.packName = packName;
            this.fileCount = fileCount;
            this.archiveCount = archiveCount;
        }
    }

    private ControlBarPackManager() {}

    // ------------------------------------------------------------------ locations

    /** {@code <user data>/Controlsbar-Manager}: central storage for every imported package. */
    static File packsDir(Context ctx) {
        return new File(SetupActivity.userDataDir(ctx), MANAGER_DIR);
    }

    private static File requirePacksDir(Context ctx) throws IOException {
        File dir = packsDir(ctx);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Could not create the Controlsbar-Manager folder: " + dir);
        }
        return dir.getCanonicalFile();
    }

    private static File backupDir(Context ctx) {
        return new File(packsDir(ctx), BACKUP_DIR);
    }

    private static File manifestFile(Context ctx) {
        return new File(packsDir(ctx), MANIFEST_FILE);
    }

    /**
     * The language the engine resolves {@code Data\<language>\...} against: the language of the
     * first {@code *ZH.big} in the game folder, else English (registry.cpp's auto-detection, which
     * is what answers on Android, where no registry and no {@code CNC_ZH_LANGUAGE} exist).
     */
    static String gameLanguage(Context ctx) {
        File game = ModManager.gameRoot(ctx);
        if (game != null && game.isDirectory()) {
            for (String[] candidate : LANGUAGE_BIGS) {
                if (findIgnoreCase(game, candidate[0]) != null) {
                    return candidate[1];
                }
            }
        }
        return "english";
    }

    private static File findIgnoreCase(File dir, String name) {
        File exact = new File(dir, name);
        if (exact.isFile()) {
            return exact;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return null;
        }
        for (File child : children) {
            if (child.isFile() && child.getName().equalsIgnoreCase(name)) {
                return child;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ discovery

    /** Every imported package, by name, skipping hidden entries and non-package folders. */
    static List<Pack> listPacks(Context ctx) {
        List<Pack> result = new ArrayList<>();
        File[] children = packsDir(ctx).listFiles();
        if (children == null) {
            return result;
        }
        Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        String active = activeName(ctx);
        for (File child : children) {
            Pack pack = describe(child, active);
            if (pack != null) {
                result.add(pack);
            }
        }
        return result;
    }

    /** A folder as a package, or null when it holds no .big archive (nothing to activate). */
    static Pack describe(File dir, String activeName) {
        if (dir == null || !dir.isDirectory() || dir.getName().startsWith(".")
                || java.nio.file.Files.isSymbolicLink(dir.toPath())) {
            return null;
        }
        List<File> archives = listArchives(dir);
        if (archives.isEmpty()) {
            return null;
        }
        Map<String, String> meta = readMetadata(dir);
        String source = meta.getOrDefault("source", dir.getName());
        return new Pack(dir, dir.getName(), source, dir.getName().equals(activeName), archives);
    }

    /** The {@code .big} archives in a package folder, recursively, by name. */
    static List<File> listArchives(File dir) {
        List<File> archives = new ArrayList<>();
        collectArchives(dir, archives, 0);
        archives.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return archives;
    }

    private static void collectArchives(File dir, List<File> out, int depth) {
        if (dir == null || depth > MapManager.MAX_DEPTH
                || java.nio.file.Files.isSymbolicLink(dir.toPath())) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectArchives(child, out, depth + 1);
            } else if (child.getName().toLowerCase(Locale.US).endsWith(".big")) {
                out.add(child);
            }
        }
    }

    // ------------------------------------------------------------------ import

    static Plan prepareTree(Context ctx, Uri treeUri, ModImportService.CancellationSignal cancellation)
            throws IOException {
        cancellation.throwIfCancelled();
        File packsDir = requirePacksDir(ctx);
        ModImportService.cleanupStaleStaging(packsDir);

        ContentResolver resolver = ctx.getContentResolver();
        String documentId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri rootDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
        String source = displayName(resolver, rootDoc, treeUri);

        Stats stats = new Stats();
        File stage = newStage(packsDir);
        try {
            copyTree(resolver, treeUri, documentId, stage, stats, 0, cancellation);
            return plan(stage, source);
        } catch (IOException | RuntimeException e) {
            ModImportService.deleteRecursively(stage);
            throw cancelled(e, cancellation);
        }
    }

    static Plan prepareZip(Context ctx, Uri uri, String displayName,
                           ModImportService.CancellationSignal cancellation) throws IOException {
        cancellation.throwIfCancelled();
        File packsDir = requirePacksDir(ctx);
        ModImportService.cleanupStaleStaging(packsDir);

        String source = displayName == null || displayName.trim().isEmpty()
            ? "archive.zip" : displayName;
        Stats stats = new Stats();
        File stage = newStage(packsDir);
        try (InputStream raw = ctx.getContentResolver().openInputStream(uri);
             ZipInputStream zip = raw == null ? null
                 : new ZipInputStream(new BufferedInputStream(raw, BUFFER_SIZE))) {
            if (zip == null) {
                throw new IOException("Could not open the selected ZIP archive");
            }
            Set<String> seen = new HashSet<>();
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                cancellation.throwIfCancelled();
                String name = ModImportService.normalizeZipPath(entry.getName());
                if (name.isEmpty()) {
                    zip.closeEntry();
                    continue;
                }
                File dest = ModImportService.resolveSafeRelative(stage, name);
                if (!seen.add(dest.getCanonicalPath())) {
                    throw new IOException("ZIP contains a duplicate path: " + name);
                }
                if (entry.isDirectory()) {
                    if (!dest.isDirectory() && !dest.mkdirs()) {
                        throw new IOException("Could not create directory: " + name);
                    }
                } else {
                    stats.addFile();
                    copyStream(zip, dest, stats, cancellation);
                }
                zip.closeEntry();
            }
            return plan(stage, source);
        } catch (IOException | RuntimeException e) {
            ModImportService.deleteRecursively(stage);
            throw cancelled(e, cancellation);
        }
    }

    /**
     * Validates a staged selection: it must hold at least one readable BIG archive, and at least
     * one of them must actually carry ControlBar files. The archives are read (their tables, not
     * their payloads) and left exactly as they are -- nothing is extracted at import time.
     */
    private static Plan plan(File stage, String source) throws IOException {
        List<File> archives = listArchives(stage);
        if (archives.isEmpty()) {
            throw new ModImportService.ImportProblem(R.string.cbpack_no_big);
        }
        boolean anyControlBar = false;
        boolean anyValid = false;
        for (File archive : archives) {
            if (!ControlBarBig.isBig(archive)) {
                continue;   // an unreadable archive is kept but is not what makes the pack usable
            }
            anyValid = true;
            try {
                ControlBarBig.detect(archive);
                anyControlBar = true;
            } catch (ModImportService.ImportProblem ignored) {
                // readable, but no control bar files: not by itself an error, the pack may hold
                // the control bar in another archive.
            }
        }
        if (!anyValid) {
            throw new ModImportService.ImportProblem(R.string.cbpack_invalid_big);
        }
        if (!anyControlBar) {
            throw new ModImportService.ImportProblem(R.string.cbpack_no_controlbar);
        }
        String name = ModImportService.sanitizeInstallName(packNameFor(source), false);
        return new Plan(stage, name, source, archives.size());
    }

    /** Commits a prepared plan into its own package folder. Returns the installed package. */
    static Pack commit(Context ctx, Plan plan) throws IOException {
        File packsDir = requirePacksDir(ctx);
        File target = ModImportService.uniqueTarget(packsDir, plan.name, false);
        try {
            if (!plan.stage.renameTo(target)) {
                throw new IOException("Could not finish the ControlBar package import");
            }
            writeMetadata(target, target.getName(), plan.source);
        } catch (IOException | RuntimeException e) {
            ModImportService.deleteRecursively(plan.stage);
            throw e;
        }
        Pack pack = describe(target, activeName(ctx));
        if (pack == null) {
            throw new IOException("The imported ControlBar package holds no archives");
        }
        return pack;
    }

    static void abort(Plan plan) {
        if (plan != null) {
            ModImportService.deleteRecursively(plan.stage);
        }
    }

    // ------------------------------------------------------------------ activation

    /**
     * Switches a package on: unpacks its archives' ControlBar files into the game folder and
     * records every file written. Any package already active is deactivated first, and if this
     * activation fails the previous one is put back, so the game is never left in a mixed state.
     */
    static Result activate(Context ctx, Pack pack, ModImportService.CancellationSignal cancellation)
            throws IOException {
        String previous = activeName(ctx);
        if (previous != null && !previous.equals(pack.name)) {
            Pack old = findPack(ctx, previous);
            if (old != null) {
                uninstall(ctx, old, cancellation);
            }
        }
        try {
            return install(ctx, pack, cancellation);
        } catch (IOException | RuntimeException e) {
            // Restore whatever was active before, so a failed switch leaves the old bar in place.
            if (previous != null && !previous.equals(pack.name)) {
                Pack old = findPack(ctx, previous);
                if (old != null) {
                    try {
                        install(ctx, old, new ModImportService.CancellationSignal());
                    } catch (IOException restore) {
                        android.util.Log.w("ControlBarPackManager",
                            "could not restore the previous control bar " + previous, restore);
                    }
                }
            }
            throw cancelled(e, cancellation);
        }
    }

    /** Switches a package off: removes exactly the files it installed and restores any backups. */
    static void deactivate(Context ctx, Pack pack,
                           ModImportService.CancellationSignal cancellation) throws IOException {
        uninstall(ctx, pack, cancellation);
    }

    /**
     * Reconciles the game folder with the saved active package. Safe to call at any time -- it
     * only ever writes files the active package provides and removes files this manager installed.
     */
    static void sync(Context ctx) {
        try {
            String active = activeName(ctx);
            if (active == null) {
                if (!manifest(ctx).isEmpty()) {
                    uninstall(ctx, null, new ModImportService.CancellationSignal());
                }
                return;
            }
            Pack pack = findPack(ctx, active);
            if (pack == null) {
                // The package folder is gone: take the installed files back out so the game does
                // not keep a control bar nothing can switch off.
                uninstall(ctx, null, new ModImportService.CancellationSignal());
                return;
            }
            Map<String, String> manifest = manifest(ctx);
            if (!manifest.keySet().equals(desiredPaths(ctx, pack).keySet())
                    || !allInstalled(ctx, manifest)) {
                install(ctx, pack, new ModImportService.CancellationSignal());
            }
        } catch (IOException e) {
            android.util.Log.w("ControlBarPackManager", "could not synchronise the control bar", e);
        }
    }

    /** Whether every file the manifest records is still in the game folder. */
    private static boolean allInstalled(Context ctx, Map<String, String> manifest) {
        File game = ModManager.gameRoot(ctx);
        if (game == null) {
            return manifest.isEmpty();
        }
        try {
            File canonicalGame = game.getCanonicalFile();
            for (String key : manifest.keySet()) {
                File dest = ModImportService.resolveSafeRelative(canonicalGame, key);
                if (!dest.isFile()) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static Pack findPack(Context ctx, String name) {
        for (Pack pack : listPacks(ctx)) {
            if (pack.name.equals(name)) {
                return pack;
            }
        }
        return null;
    }

    /**
     * The files a package would install: install path (game-folder relative, {@code /} separated)
     * to the archive entry that provides it. Two entries landing on the same path are a conflict
     * and refuse the activation whole, rather than one silently winning.
     */
    private static Map<String, Installed> desiredPaths(Context ctx, Pack pack) throws IOException {
        String language = gameLanguage(ctx);
        Map<String, Installed> out = new LinkedHashMap<>();
        for (File archive : pack.archives) {
            ControlBarBig.Detection detection;
            try {
                detection = ControlBarBig.detect(archive);
            } catch (ModImportService.ImportProblem e) {
                // A readable archive with no control bar, or an unreadable one, is skipped: the
                // package's other archives may still carry the bar.
                continue;
            }
            for (ControlBarBig.Entry entry : detection.selected()) {
                String installPath = ControlBarBig.installPath(entry.path, language);
                String key = installPath.replace('\\', '/');
                Installed existing = out.get(key);
                if (existing != null) {
                    if (existing.entry.size != entry.size) {
                        throw new ModImportService.ImportProblem(R.string.cbpack_conflict);
                    }
                    continue;   // the same file in two archives of one package: install once
                }
                out.put(key, new Installed(archive, entry, installPath));
            }
        }
        if (out.isEmpty()) {
            throw new ModImportService.ImportProblem(R.string.cbpack_no_controlbar);
        }
        return out;
    }

    private static final class Installed {
        final File archive;
        final ControlBarBig.Entry entry;
        final String installPath;

        Installed(File archive, ControlBarBig.Entry entry, String installPath) {
            this.archive = archive;
            this.entry = entry;
            this.installPath = installPath;
        }
    }

    /**
     * Installs a package: backs up any file it would overwrite, extracts its files into the game
     * folder, then writes the manifest. A failure part way through undoes the files it wrote and
     * restores their backups, so nothing is left half installed.
     */
    private static Result install(Context ctx, Pack pack,
                                  ModImportService.CancellationSignal cancellation) throws IOException {
        File game = ModManager.gameRoot(ctx);
        if (game == null || (!game.isDirectory() && !game.mkdirs())) {
            throw new ModImportService.ImportProblem(R.string.mods_no_game_message);
        }
        File canonicalGame = game.getCanonicalFile();
        File backupDir = backupDir(ctx);
        if (!backupDir.isDirectory() && !backupDir.mkdirs()) {
            throw new IOException("Could not create the ControlBar backup folder");
        }

        Map<String, Installed> desired = desiredPaths(ctx, pack);
        Map<String, String> manifest = manifest(ctx);

        // Files already installed by this manager are replaced in place; anything else that is in
        // the way is backed up first, never overwritten blindly.
        Map<String, String> nextManifest = new LinkedHashMap<>();
        List<File> written = new ArrayList<>();
        // Destinations whose original file was moved into the backup folder this run, so a failure
        // can put them back rather than lose them.
        List<File[]> freshBackups = new ArrayList<>();
        try {
            for (Map.Entry<String, Installed> item : desired.entrySet()) {
                cancellation.throwIfCancelled();
                String key = item.getKey();
                Installed installed = item.getValue();
                File dest = ModImportService.resolveSafeRelative(canonicalGame, installed.installPath);
                String backupName = manifest.get(key);
                if (dest.isFile() && backupName == null) {
                    backupName = UUID.randomUUID().toString() + ".bak";
                    File backup = new File(backupDir, backupName);
                    if (!dest.renameTo(backup)) {
                        throw new IOException("Could not back up " + installed.installPath);
                    }
                    freshBackups.add(new File[] { backup, dest });
                }
                ControlBarBig.extract(installed.archive, installed.entry, dest, cancellation);
                written.add(dest);
                nextManifest.put(key, backupName == null ? "" : backupName);
            }
        } catch (IOException | RuntimeException e) {
            for (File file : written) {
                file.delete();
            }
            for (File[] backup : freshBackups) {
                backup[0].renameTo(backup[1]);   // put the game's own file back
            }
            throw cancelled(e, cancellation);
        }

        // Remove files the previous activation installed that this one does not (a switch), then
        // record the new set.
        for (Map.Entry<String, String> old : manifest.entrySet()) {
            if (!nextManifest.containsKey(old.getKey())) {
                removeInstalled(ctx, old.getKey(), old.getValue());
                pruneEmptyParents(canonicalGame, old.getKey());
            }
        }
        writeManifest(ctx, nextManifest);
        saveActive(ctx, pack.name);
        return new Result(pack.name, nextManifest.size(), pack.archiveCount());
    }

    /** Removes everything the active package installed and restores its backups. */
    private static void uninstall(Context ctx, Pack pack,
                                  ModImportService.CancellationSignal cancellation) throws IOException {
        File game = ModManager.gameRoot(ctx);
        Map<String, String> manifest = manifest(ctx);
        for (Map.Entry<String, String> entry : manifest.entrySet()) {
            cancellation.throwIfCancelled();
            removeInstalled(ctx, entry.getKey(), entry.getValue());
            if (game != null) {
                pruneEmptyParents(game.getCanonicalFile(), entry.getKey());
            }
        }
        writeManifest(ctx, new LinkedHashMap<>());
        saveActive(ctx, null);
    }

    /** Deletes one installed file and puts its backup (if any) back. */
    private static void removeInstalled(Context ctx, String key, String backupName) {
        File game = ModManager.gameRoot(ctx);
        if (game == null) {
            return;
        }
        try {
            File dest = ModImportService.resolveSafeRelative(game.getCanonicalFile(), key);
            if (dest.isFile()) {
                dest.delete();
            }
            if (backupName != null && !backupName.isEmpty()) {
                File backup = new File(backupDir(ctx), backupName);
                if (backup.isFile() && !backup.renameTo(dest)) {
                    android.util.Log.w("ControlBarPackManager", "could not restore " + key);
                }
            }
        } catch (IOException e) {
            android.util.Log.w("ControlBarPackManager", "could not remove " + key, e);
        }
    }

    /**
     * Removes the folders an installed file lived in, from the deepest up to -- but never
     * including -- the game folder itself. A folder that still holds anything (the game's own
     * files, another mod's) stops the walk, so nothing unrelated is ever deleted and no part of the
     * game's tree is scanned.
     */
    private static void pruneEmptyParents(File gameRoot, String key) {
        File dir = new File(gameRoot, key.replace('/', File.separatorChar)).getParentFile();
        while (dir != null && !dir.equals(gameRoot)) {
            File[] children = dir.listFiles();
            if (children == null || children.length > 0) {
                return;
            }
            File parent = dir.getParentFile();
            if (!dir.delete()) {
                return;
            }
            dir = parent;
        }
    }

    // ------------------------------------------------------------------ manifest

    /** Installed path ({@code /} separated, game-folder relative) to backup name ({@code ""}). */
    private static Map<String, String> manifest(Context ctx) {
        Map<String, String> out = new LinkedHashMap<>();
        File file = manifestFile(ctx);
        if (!file.isFile()) {
            return out;
        }
        try {
            for (String line : new String(readFully(file), StandardCharsets.UTF_8).split("\n")) {
                if (line.isEmpty()) {
                    continue;
                }
                int tab = line.indexOf('\t');
                if (tab < 0) {
                    out.put(line, "");
                } else {
                    out.put(line.substring(0, tab), line.substring(tab + 1));
                }
            }
        } catch (IOException e) {
            android.util.Log.w("ControlBarPackManager", "could not read the install manifest", e);
        }
        return out;
    }

    private static void writeManifest(Context ctx, Map<String, String> manifest) throws IOException {
        File file = manifestFile(ctx);
        if (manifest.isEmpty()) {
            file.delete();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : manifest.entrySet()) {
            sb.append(entry.getKey()).append('\t').append(entry.getValue()).append('\n');
        }
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (OutputStream out = new FileOutputStream(temp)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!temp.renameTo(file)) {
            file.delete();
            if (!temp.renameTo(file)) {
                temp.delete();
                throw new IOException("Could not write the install manifest");
            }
        }
    }

    // ------------------------------------------------------------------ active state

    static String activeName(Context ctx) {
        String name = ctx.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_ACTIVE, "");
        return name == null || name.isEmpty() ? null : name;
    }

    private static void saveActive(Context ctx, String name) {
        ctx.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(PREF_ACTIVE, name == null ? "" : name).apply();
    }

    // ------------------------------------------------------------------ delete

    /** Deletes a package folder, but only a direct child of {@code Controlsbar-Manager}. */
    static boolean delete(Context ctx, Pack pack) {
        File dir = pack.dir;
        File parent = dir.getParentFile();
        File packs = packsDir(ctx);
        if (parent == null || !parent.getAbsolutePath().equals(packs.getAbsolutePath())
                || java.nio.file.Files.isSymbolicLink(dir.toPath())) {
            return false;
        }
        ModImportService.deleteRecursively(dir);
        return !dir.exists();
    }

    // ------------------------------------------------------------------ metadata

    private static Map<String, String> readMetadata(File packDir) {
        Map<String, String> meta = new java.util.HashMap<>();
        File file = new File(packDir, METADATA_FILE);
        if (!file.isFile()) {
            return meta;
        }
        try {
            for (String line : new String(readFully(file), StandardCharsets.UTF_8).split("\n")) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    meta.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }
        } catch (IOException e) {
            android.util.Log.w("ControlBarPackManager", "could not read package metadata", e);
        }
        return meta;
    }

    private static void writeMetadata(File packDir, String name, String source) throws IOException {
        String body = "name=" + name + "\n"
            + "source=" + source + "\n"
            + "imported=" + System.currentTimeMillis() + "\n";
        try (OutputStream out = new FileOutputStream(new File(packDir, METADATA_FILE))) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static File newStage(File packsDir) throws IOException {
        File staging = new File(packsDir, STAGING_DIR).getCanonicalFile();
        if (!ModImportService.isInside(packsDir, staging)
                || !staging.isDirectory() && !staging.mkdirs()) {
            throw new IOException("Could not create the ControlBar staging directory");
        }
        File stage = new File(staging, UUID.randomUUID().toString());
        if (!stage.mkdirs()) {
            throw new IOException("Could not create the ControlBar staging directory");
        }
        return stage;
    }

    private static String packNameFor(String source) {
        String value = source == null ? "" : source.trim();
        if (value.toLowerCase(Locale.US).endsWith(".zip")) {
            value = value.substring(0, value.length() - 4);
        }
        return value.isEmpty() ? "ControlBar" : value;
    }

    private static String displayName(ContentResolver resolver, Uri rootDoc, Uri treeUri) {
        try (android.database.Cursor cursor = resolver.query(rootDoc,
                new String[] { DocumentsContract.Document.COLUMN_DISPLAY_NAME }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) {
                    return name;
                }
            }
        } catch (RuntimeException ignored) {
        }
        String last = treeUri.getLastPathSegment();
        return last == null || last.trim().isEmpty() ? "ControlBar" : last;
    }

    private static IOException cancelled(Exception e, ModImportService.CancellationSignal cancellation) {
        if (cancellation.isCancelled()) {
            return new ModImportService.ImportCancelledException();
        }
        return e instanceof IOException ? (IOException) e : new IOException(e);
    }

    private static byte[] readFully(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static void copyTree(ContentResolver resolver, Uri treeUri, String parentDocumentId,
                                 File destDir, Stats stats, int depth,
                                 ModImportService.CancellationSignal cancellation) throws IOException {
        cancellation.throwIfCancelled();
        if (depth > MapManager.MAX_DEPTH) {
            throw new IOException("ControlBar package nesting is too deep");
        }
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId);
        String[] projection = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        };
        try (android.database.Cursor cursor = resolver.query(children, projection, null, null, null)) {
            if (cursor == null) {
                throw new IOException("Could not read the selected ControlBar folder");
            }
            int idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
            while (cursor.moveToNext()) {
                cancellation.throwIfCancelled();
                String childId = cursor.getString(idCol);
                String childName = ModImportService.validateSegment(cursor.getString(nameCol));
                String mime = cursor.getString(mimeCol);
                File dest = ModImportService.resolveSafeRelative(destDir, childName);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if (!dest.isDirectory() && !dest.mkdirs()) {
                        throw new IOException("Could not create directory: " + childName);
                    }
                    copyTree(resolver, treeUri, childId, dest, stats, depth + 1, cancellation);
                } else {
                    stats.addFile();
                    Uri childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId);
                    try (InputStream in = resolver.openInputStream(childUri)) {
                        if (in == null) {
                            throw new IOException("Could not open: " + childName);
                        }
                        copyStream(in, dest, stats, cancellation);
                    }
                }
            }
        }
    }

    private static void copyStream(InputStream in, File dest, Stats stats,
                                   ModImportService.CancellationSignal cancellation) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create the destination directory");
        }
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(dest), BUFFER_SIZE)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buffer)) > 0) {
                cancellation.throwIfCancelled();
                stats.addBytes(n);
                out.write(buffer, 0, n);
            }
        }
    }

    /** Bounded counters, so a hostile selection cannot fill the phone before validation fails. */
    private static final class Stats {
        int fileCount;
        long bytes;

        void addFile() throws IOException {
            if (++fileCount > MAX_ENTRIES) {
                throw new IOException("The selection contains too many files");
            }
        }

        void addBytes(long count) throws IOException {
            bytes += count;
            if (bytes > MAX_TOTAL_BYTES) {
                throw new IOException("The selection is larger than the 8 GiB import limit");
            }
        }
    }
}
