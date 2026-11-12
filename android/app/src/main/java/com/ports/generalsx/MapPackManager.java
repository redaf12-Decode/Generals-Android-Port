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
import android.content.SharedPreferences;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * GeneralsX @feature Android port 07/11/2026 Map Packs Manager (Tools -&gt; Map Packs).
 *
 * <p>Imported content lives in {@code <user data>/maps-manager/&lt;pack&gt;} and is kept there:
 * an import never touches the game's {@code Maps} folder. Activating a pack <em>copies</em> its
 * map folders into {@code Maps}, where the engine's {@code MapCache} finds them (see
 * {@link MapManager} for the path and the two "is this a map" rules); deactivating removes the
 * copies again. The pack itself is never moved, so it can be activated and deactivated any number
 * of times.
 *
 * <p>Because the same map can sit in more than one pack, ownership is tracked two ways:
 * <ul>
 *   <li>the desired set is the union of the <em>active</em> packs' maps, keyed by map name, so a
 *       map two active packs share is installed once and survives until both are off;</li>
 *   <li>a manifest ({@code maps-manager/.active.txt}) records the {@code Maps} folders this
 *       manager itself created. Only those are ever deleted, so a map the player put in
 *       {@code Maps} by hand is never removed.</li>
 * </ul>
 *
 * <p>The combined unique map count of the active packs is capped at
 * {@link #MAX_ACTIVE_MAPS}. A pack that would push it over is refused whole -- nothing is copied
 * -- with {@link R.string#mappack_limit_message}. Deactivation never checks the cap, so a player
 * who is at the limit can always free room.
 *
 * <p>{@link #sync} reconciles the saved active list with the real {@code Maps} folder: it is run
 * when the screen opens and right before the game starts, so an interrupted import, activation or
 * deactivation heals instead of leaving a half-installed set.
 */
final class MapPackManager {
    static final String PACKS_DIR = "maps-manager";
    static final String METADATA_FILE = "pack.txt";
    static final String MANIFEST_FILE = ".active.txt";
    private static final String PREF_ACTIVE = "mappacks_active";
    static final int MAX_ACTIVE_MAPS = 200;

    private static final long MAX_TOTAL_BYTES = 8L * 1024L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 100000;
    private static final int BUFFER_SIZE = 64 * 1024;

    /** One imported pack: a folder under {@code maps-manager} holding one or more maps. */
    static final class Pack {
        final File dir;
        final String name;
        final String source;
        final boolean active;
        final List<File> mapFolders;

        Pack(File dir, String name, String source, boolean active, List<File> mapFolders) {
            this.dir = dir;
            this.name = name;
            this.source = source;
            this.active = active;
            this.mapFolders = mapFolders;
        }

        int mapCount() {
            return mapFolders.size();
        }

        List<String> mapNames() {
            List<String> names = new ArrayList<>();
            for (File map : mapFolders) {
                names.add(map.getName());
            }
            return names;
        }
    }

    /** A staged import that has been checked and is waiting for its pack folder. */
    static final class Plan {
        final File stage;
        final String name;
        final String source;
        final int mapCount;

        Plan(File stage, String name, String source, int mapCount) {
            this.stage = stage;
            this.name = name;
            this.source = source;
            this.mapCount = mapCount;
        }
    }

    /** The outcome of an activate/deactivate: how many unique maps are installed afterwards. */
    static final class Result {
        final int activeMaps;

        Result(int activeMaps) {
            this.activeMaps = activeMaps;
        }
    }

    private MapPackManager() {}

    // ------------------------------------------------------------------ locations

    /** {@code <user data>/maps-manager}: central storage for every imported pack. */
    static File packsDir(Context ctx) {
        return new File(SetupActivity.userDataDir(ctx), PACKS_DIR);
    }

    private static File requirePacksDir(Context ctx) throws IOException {
        File dir = packsDir(ctx);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Could not create the maps-manager folder: " + dir);
        }
        return dir.getCanonicalFile();
    }

    // ------------------------------------------------------------------ discovery

    /** Every imported pack, newest name last, skipping hidden entries and non-pack folders. */
    static List<Pack> listPacks(Context ctx) {
        List<Pack> result = new ArrayList<>();
        File[] children = packsDir(ctx).listFiles();
        if (children == null) {
            return result;
        }
        Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        Set<String> active = activeNames(ctx);
        for (File child : children) {
            Pack pack = describe(child, active);
            if (pack != null) {
                result.add(pack);
            }
        }
        return result;
    }

    /** A folder as a pack, or null when it holds no map (the manager would show nothing). */
    static Pack describe(File dir, Set<String> activeNames) {
        if (dir == null || !dir.isDirectory() || dir.getName().startsWith(".")
                || java.nio.file.Files.isSymbolicLink(dir.toPath())) {
            return null;
        }
        List<File> maps = MapManager.findMapFolders(dir);
        if (maps.isEmpty()) {
            return null;
        }
        Map<String, String> meta = readMetadata(dir);
        String source = meta.getOrDefault("source", dir.getName());
        return new Pack(dir, dir.getName(), source, activeNames.contains(dir.getName()), maps);
    }

    /** The unique map names across the given packs, case-insensitive as the engine keys them. */
    static Set<String> uniqueMapNames(List<Pack> packs) {
        Set<String> names = new HashSet<>();
        for (Pack pack : packs) {
            for (File map : pack.mapFolders) {
                names.add(map.getName().toLowerCase(Locale.US));
            }
        }
        return names;
    }

    static int activeMapCount(Context ctx) {
        List<Pack> active = new ArrayList<>();
        for (Pack pack : listPacks(ctx)) {
            if (pack.active) {
                active.add(pack);
            }
        }
        return uniqueMapNames(active).size();
    }

    /**
     * The unique map count if the pack called {@code packName} were switched on: the union with the
     * currently active packs, so a map shared between packs is counted once. Used to show, before a
     * pack is activated, how much of the 200-map budget it would use.
     */
    static int activeCountWith(Context ctx, String packName) {
        List<Pack> union = new ArrayList<>();
        for (Pack p : listPacks(ctx)) {
            if (p.active || p.name.equals(packName)) {
                union.add(p);
            }
        }
        return uniqueMapNames(union).size();
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
     * Validates a staged selection and works out the pack's name and map count. The selection may
     * be several maps, one map, or a single loose {@code .map} (given a folder of its own, since
     * the engine only accepts {@code <name>/<name>.map}).
     */
    private static Plan plan(File stage, String source) throws IOException {
        List<File> maps = MapManager.findMapFolders(stage);
        if (maps.isEmpty()) {
            File loose = findLooseMap(stage);
            if (loose == null) {
                throw new ModImportService.ImportProblem(R.string.mappack_no_maps);
            }
            String stem = baseName(loose.getName());
            File root = new File(stage, stem);
            if (!root.mkdirs()) {
                throw new IOException("Could not prepare the map folder");
            }
            if (!loose.renameTo(new File(root, loose.getName()))) {
                throw new IOException("Could not prepare the map folder");
            }
            maps = MapManager.findMapFolders(stage);
            if (maps.isEmpty()) {
                throw new ModImportService.ImportProblem(R.string.mappack_no_maps);
            }
        }
        for (File map : maps) {
            MapManager.Map described = MapManager.describe(map);
            if (described == null || !MapManager.isMapFile(described.mapFile)) {
                throw new ModImportService.ImportProblem(R.string.mappack_invalid_map_file);
            }
        }
        String name = ModImportService.sanitizeInstallName(packNameFor(source), false);
        return new Plan(stage, name, source, maps.size());
    }

    /** Commits a prepared plan into its own pack folder. Returns the installed pack. */
    static Pack commit(Context ctx, Plan plan) throws IOException {
        File packsDir = requirePacksDir(ctx);
        File target = ModImportService.uniqueTarget(packsDir, plan.name, false);
        try {
            if (!plan.stage.renameTo(target)) {
                throw new IOException("Could not finish the map pack import");
            }
            writeMetadata(target, target.getName(), plan.source);
        } catch (IOException | RuntimeException e) {
            ModImportService.deleteRecursively(plan.stage);
            throw e;
        }
        Pack pack = describe(target, activeNames(ctx));
        if (pack == null) {
            throw new IOException("The imported map pack holds no maps");
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
     * Switches a pack on: copies its maps into {@code Maps} after checking the cap, then records
     * the pack as active. Nothing is copied when the cap would be exceeded, and a failure part way
     * through undoes the copies it made, so the pack is never half active.
     */
    static Result activate(Context ctx, Pack pack, ModImportService.CancellationSignal cancellation)
            throws IOException {
        List<Pack> packs = listPacks(ctx);
        Set<String> target = activeNames(ctx);
        target.add(pack.name);
        return apply(ctx, packs, target, true, cancellation);
    }

    /**
     * Switches a pack off: removes the {@code Maps} copies only that pack still needs. The cap is
     * not checked, so deactivation always works.
     */
    static Result deactivate(Context ctx, Pack pack, ModImportService.CancellationSignal cancellation)
            throws IOException {
        List<Pack> packs = listPacks(ctx);
        Set<String> target = activeNames(ctx);
        target.remove(pack.name);
        return apply(ctx, packs, target, false, cancellation);
    }

    /**
     * Reconciles the {@code Maps} folder with the saved active list. Safe to call at any time --
     * it only ever creates maps an active pack provides and removes maps this manager created and
     * no active pack still needs.
     */
    static void sync(Context ctx) {
        try {
            apply(ctx, listPacks(ctx), activeNames(ctx), false,
                new ModImportService.CancellationSignal());
        } catch (IOException e) {
            android.util.Log.w("MapPackManager", "could not synchronise the active map packs", e);
        }
    }

    /**
     * Brings {@code Maps} in line with {@code targetActive}. When {@code enforceLimit} is set the
     * unique map count of the new set is checked first and an over-limit request is refused whole
     * ({@link R.string#mappack_limit_message}).
     */
    private static Result apply(Context ctx, List<Pack> packs, Set<String> targetActive,
                                boolean enforceLimit,
                                ModImportService.CancellationSignal cancellation) throws IOException {
        Map<String, File> desired = new LinkedHashMap<>();
        for (Pack pack : packs) {
            if (targetActive.contains(pack.name)) {
                for (File map : pack.mapFolders) {
                    desired.putIfAbsent(map.getName().toLowerCase(Locale.US), map);
                }
            }
        }
        if (enforceLimit && desired.size() > MAX_ACTIVE_MAPS) {
            throw new ModImportService.ImportProblem(R.string.mappack_limit_message);
        }

        File mapsDir = MapManager.requireMapsDir(ctx);
        Set<String> managed = readManifest(ctx);
        Set<String> keep = new HashSet<>();
        for (Map.Entry<String, File> map : desired.entrySet()) {
            keep.add(new File(mapsDir, map.getValue().getName()).getAbsolutePath());
        }

        List<File> created = new ArrayList<>();
        try {
            for (File source : desired.values()) {
                cancellation.throwIfCancelled();
                File dest = new File(mapsDir, source.getName());
                if (dest.exists()) {
                    continue;   // already installed, by us or by the player: never overwrite
                }
                MapManager.copyMap(source, dest, cancellation);
                created.add(dest);
                managed.add(dest.getAbsolutePath());
            }
        } catch (IOException | RuntimeException e) {
            for (File dir : created) {
                ModImportService.deleteRecursively(dir);
                managed.remove(dir.getAbsolutePath());
            }
            throw cancelled(e, cancellation);
        }

        for (String path : new ArrayList<>(managed)) {
            cancellation.throwIfCancelled();
            if (keep.contains(path)) {
                continue;
            }
            File dir = new File(path);
            if (isInsideMaps(mapsDir, dir)) {
                ModImportService.deleteRecursively(dir);
            }
            managed.remove(path);
        }

        saveActive(ctx, targetActive);
        writeManifest(ctx, managed);
        return new Result(desired.size());
    }

    /** Only a direct child of {@code Maps} may be deleted -- never anything else. */
    private static boolean isInsideMaps(File mapsDir, File dir) {
        File parent = dir.getParentFile();
        return parent != null && parent.getAbsolutePath().equals(mapsDir.getAbsolutePath())
            && !java.nio.file.Files.isSymbolicLink(dir.toPath());
    }

    // ------------------------------------------------------------------ delete

    /** Deletes a pack folder, but only a direct child of {@code maps-manager}. */
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

    // ------------------------------------------------------------------ export

    /**
     * Exports a pack into a folder the player picked, as {@code <pack>/<map>/<map>.map ...}, so
     * the folder or ZIP it produces can be imported again on another device.
     */
    static int exportFolder(Context ctx, Pack pack, Uri treeUri,
                            ModImportService.CancellationSignal cancellation) throws IOException {
        ContentResolver resolver = ctx.getContentResolver();
        String parentId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri parentDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId);
        Uri folder = DocumentsContract.createDocument(resolver, parentDoc,
            DocumentsContract.Document.MIME_TYPE_DIR, pack.name);
        if (folder == null) {
            throw new IOException("Could not create the destination folder");
        }
        Uri folderDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri,
            DocumentsContract.getDocumentId(folder));
        Budget budget = new Budget();
        copyToTree(resolver, treeUri, folderDoc, pack.dir, budget, cancellation, 0);
        return budget.fileCount;
    }

    private static void copyToTree(ContentResolver resolver, Uri treeUri, Uri parentDoc, File source,
                                   Budget budget, ModImportService.CancellationSignal cancellation,
                                   int depth) throws IOException {
        cancellation.throwIfCancelled();
        if (depth > MapManager.MAX_DEPTH) {
            throw new IOException("Map pack nesting is too deep");
        }
        File[] children = source.listFiles();
        if (children == null) {
            throw new IOException("Could not read the map pack folder");
        }
        for (File child : children) {
            cancellation.throwIfCancelled();
            if (java.nio.file.Files.isSymbolicLink(child.toPath())
                    || child.getName().equals(METADATA_FILE)) {
                continue;   // the pack's own bookkeeping is not part of what the player exports
            }
            if (child.isDirectory()) {
                Uri dir = DocumentsContract.createDocument(resolver, parentDoc,
                    DocumentsContract.Document.MIME_TYPE_DIR, child.getName());
                if (dir == null) {
                    throw new IOException("Could not create the destination folder");
                }
                copyToTree(resolver, treeUri,
                    DocumentsContract.buildDocumentUriUsingTree(treeUri,
                        DocumentsContract.getDocumentId(dir)),
                    child, budget, cancellation, depth + 1);
            } else {
                budget.addFile();
                budget.addBytes(child.length());
                Uri file = DocumentsContract.createDocument(resolver, parentDoc,
                    "application/octet-stream", child.getName());
                if (file == null) {
                    throw new IOException("Could not create the destination file");
                }
                try (InputStream in = new BufferedInputStream(new FileInputStream(child), BUFFER_SIZE);
                     OutputStream out = new BufferedOutputStream(
                         resolver.openOutputStream(file, "w"), BUFFER_SIZE)) {
                    if (out == null) {
                        throw new IOException("Could not write the destination file");
                    }
                    copy(in, out, cancellation);
                }
            }
        }
    }

    /** Writes a pack as a ZIP into a document the player picked (ACTION_CREATE_DOCUMENT). */
    static int exportZip(Context ctx, Pack pack, Uri destination,
                         ModImportService.CancellationSignal cancellation) throws IOException {
        Budget budget = new Budget();
        try (OutputStream raw = ctx.getContentResolver().openOutputStream(destination, "w");
             ZipOutputStream zip = raw == null ? null
                 : new ZipOutputStream(new BufferedOutputStream(raw, BUFFER_SIZE))) {
            if (zip == null) {
                throw new IOException("Could not write the selected ZIP file");
            }
            zipFile(zip, pack.dir, "", budget, cancellation, 0);
        }
        return budget.fileCount;
    }

    private static void zipFile(ZipOutputStream zip, File dir, String prefix, Budget budget,
                                ModImportService.CancellationSignal cancellation, int depth)
            throws IOException {
        cancellation.throwIfCancelled();
        if (depth > MapManager.MAX_DEPTH) {
            throw new IOException("Map pack nesting is too deep");
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            cancellation.throwIfCancelled();
            if (java.nio.file.Files.isSymbolicLink(child.toPath())
                    || child.getName().equals(METADATA_FILE)) {
                continue;   // the pack's own bookkeeping is not part of what the player exports
            }
            if (child.isDirectory()) {
                zipFile(zip, child, prefix + child.getName() + "/", budget, cancellation, depth + 1);
            } else {
                budget.addFile();
                budget.addBytes(child.length());
                zip.putNextEntry(new ZipEntry(prefix + child.getName()));
                try (InputStream in = new BufferedInputStream(new FileInputStream(child), BUFFER_SIZE)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        cancellation.throwIfCancelled();
                        zip.write(buffer, 0, n);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static void copy(InputStream in, OutputStream out,
                             ModImportService.CancellationSignal cancellation) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int n;
        while ((n = in.read(buffer)) > 0) {
            cancellation.throwIfCancelled();
            out.write(buffer, 0, n);
        }
    }

    /** Bounded counters, so a hostile archive cannot fill the phone before validation fails. */
    private static final class Budget {
        int fileCount;
        long bytes;

        void addFile() throws IOException {
            if (++fileCount > MAX_ENTRIES) {
                throw new IOException("The pack contains too many files");
            }
        }

        void addBytes(long count) throws IOException {
            bytes += count;
            if (bytes > MAX_TOTAL_BYTES) {
                throw new IOException("The pack is larger than the import limit");
            }
        }
    }

    // ------------------------------------------------------------------ active state

    static Set<String> activeNames(Context ctx) {
        Set<String> names = new HashSet<>();
        SharedPreferences prefs = ctx.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String stored = prefs.getString(PREF_ACTIVE, "");
        for (String line : stored.split("\n")) {
            String name = line.trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    private static void saveActive(Context ctx, Set<String> names) {
        StringBuilder sb = new StringBuilder();
        for (String name : names) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(name);
        }
        ctx.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(PREF_ACTIVE, sb.toString()).apply();
    }

    // ------------------------------------------------------------------ manifest

    private static Set<String> readManifest(Context ctx) {
        Set<String> paths = new HashSet<>();
        File file = new File(packsDir(ctx), MANIFEST_FILE);
        if (!file.isFile()) {
            return paths;
        }
        try {
            for (String line : new String(readFully(file), java.nio.charset.StandardCharsets.UTF_8)
                    .split("\n")) {
                String path = line.trim();
                if (!path.isEmpty()) {
                    paths.add(path);
                }
            }
        } catch (IOException e) {
            android.util.Log.w("MapPackManager", "could not read the active map manifest", e);
        }
        return paths;
    }

    private static void writeManifest(Context ctx, Set<String> paths) {
        File file = new File(packsDir(ctx), MANIFEST_FILE);
        StringBuilder sb = new StringBuilder();
        for (String path : paths) {
            sb.append(path).append('\n');
        }
        try {
            if (sb.length() == 0) {
                file.delete();
                return;
            }
            try (OutputStream out = new FileOutputStream(file)) {
                out.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            android.util.Log.w("MapPackManager", "could not write the active map manifest", e);
        }
    }

    // ------------------------------------------------------------------ metadata

    private static Map<String, String> readMetadata(File packDir) {
        Map<String, String> meta = new HashMap<>();
        File file = new File(packDir, METADATA_FILE);
        if (!file.isFile()) {
            return meta;
        }
        try {
            for (String line : new String(readFully(file), java.nio.charset.StandardCharsets.UTF_8)
                    .split("\n")) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    meta.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }
        } catch (IOException e) {
            android.util.Log.w("MapPackManager", "could not read pack metadata", e);
        }
        return meta;
    }

    private static void writeMetadata(File packDir, String name, String source) throws IOException {
        String body = "name=" + name + "\n"
            + "source=" + source + "\n"
            + "imported=" + System.currentTimeMillis() + "\n";
        try (OutputStream out = new FileOutputStream(new File(packDir, METADATA_FILE))) {
            out.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static File newStage(File packsDir) throws IOException {
        File staging = new File(packsDir, ModImportService.INSTALLING_DIR).getCanonicalFile();
        if (!ModImportService.isInside(packsDir, staging)
                || !staging.isDirectory() && !staging.mkdirs()) {
            throw new IOException("Could not create the map pack staging directory");
        }
        File stage = new File(staging, UUID.randomUUID().toString());
        if (!stage.mkdirs()) {
            throw new IOException("Could not create the map pack staging directory");
        }
        return stage;
    }

    /** A lone {@code .map} at the top of the selection, wrapped into a folder of its own. */
    private static File findLooseMap(File stage) {
        File[] children = stage.listFiles();
        if (children == null) {
            return null;
        }
        File found = null;
        for (File child : children) {
            if (child.isFile()
                    && child.getName().toLowerCase(Locale.US).endsWith(MapManager.MAP_EXTENSION)
                    && MapManager.isMapFile(child)) {
                if (found != null) {
                    return null;
                }
                found = child;
            }
        }
        return found;
    }

    private static String packNameFor(String source) {
        String value = source == null ? "" : source.trim();
        String lower = value.toLowerCase(Locale.US);
        if (lower.endsWith(".zip")) {
            value = value.substring(0, value.length() - 4);
        }
        return value.isEmpty() ? "Map Pack" : value;
    }

    private static String baseName(String fileName) {
        String lower = fileName.toLowerCase(Locale.US);
        return lower.endsWith(MapManager.MAP_EXTENSION)
            ? fileName.substring(0, fileName.length() - MapManager.MAP_EXTENSION.length()) : fileName;
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
        return last == null || last.trim().isEmpty() ? "Map Pack" : last;
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
            throw new IOException("Map pack nesting is too deep");
        }
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId);
        String[] projection = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        };
        try (android.database.Cursor cursor = resolver.query(children, projection, null, null, null)) {
            if (cursor == null) {
                throw new IOException("Could not read the selected map folder");
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
