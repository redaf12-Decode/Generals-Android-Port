/*
**  Command & Conquer Generals Zero Hour(tm)
**  Copyright 2025 Electronic Arts Inc.
**
**  This program is free software: you can redistribute it and/or modify
**  it under the terms of the GNU General Public License as published by
**  the Free Software Foundation, either version 3 of the License, or
**  (at your option) any later version.
*/

package com.generalsx.zerohour;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * GeneralsX @feature Android port 07/11/2026 Custom map management (Tools -> Map Manager).
 *
 * <p>The engine discovers user maps by scanning {@code <user data>/Maps} on every map-list
 * refresh ({@code MapCache::updateCache} -> {@code loadMapsFromDisk(userMapDir)}), so an imported
 * map shows up at the next map list with no restart. {@code getUserMapDir()} is
 * {@code getPath_UserData() + "Maps"}, and on Android the user-data folder is the plain
 * {@code /storage/emulated/<uid>/Generals/<leaf>} directory SDL3Main.cpp hands the engine -- the
 * same folder {@link SetupActivity#userDataDir} resolves, so the launcher and the game agree.
 *
 * <p>{@code MapCache::hasValidMapLeafLayout} requires the path to end with
 * {@code <name>\<name>.map}, i.e. the folder a map lives in must be named after the map file.
 * Every install therefore lands as {@code Maps/<name>/<name>.map} plus the map's other files.
 *
 * <p>Imported content is untrusted. Everything is copied into {@code Maps/.installing/<uuid>}
 * first and validated there; the path helpers are the importer's ({@link ModImportService}), so
 * ZIP traversal and absolute paths are rejected the same way. A {@link Plan} keeps the validated
 * staging alive so the caller can ask about name conflicts before {@link #commit} installs it.
 */
final class MapManager {
    static final String MAPS_DIR = "Maps";
    static final String MAP_EXTENSION = ".map";

    private static final long MAX_TOTAL_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 20000;
    private static final int MAX_DEPTH = 32;
    private static final int BUFFER_SIZE = 64 * 1024;

    /** One installed map: the folder the game reads, its .map file and its preview picture. */
    static final class Map {
        final File dir;
        final String name;
        final File mapFile;
        final File preview;
        final long bytes;
        final int fileCount;

        Map(File dir, String name, File mapFile, File preview, long bytes, int fileCount) {
            this.dir = dir;
            this.name = name;
            this.mapFile = mapFile;
            this.preview = preview;
            this.bytes = bytes;
            this.fileCount = fileCount;
        }
    }

    /** A validated map waiting in staging: {@code root} is the folder to move to {@code Maps/<name>}. */
    static final class Entry {
        final File root;
        final String name;

        Entry(File root, String name) {
            this.root = root;
            this.name = name;
        }
    }

    /** Validated staging: nothing has been installed yet. */
    static final class Plan {
        final File stage;
        final List<Entry> entries;
        final int fileCount;
        final long bytes;

        Plan(File stage, List<Entry> entries, int fileCount, long bytes) {
            this.stage = stage;
            this.entries = entries;
            this.fileCount = fileCount;
            this.bytes = bytes;
        }
    }

    static final class Result {
        final List<Map> installed;
        final int replaced;
        final int keptBoth;

        Result(List<Map> installed, int replaced, int keptBoth) {
            this.installed = installed;
            this.replaced = replaced;
            this.keptBoth = keptBoth;
        }
    }

    /** Whether an existing map of the same name is replaced or kept beside the new one. */
    enum Policy { REPLACE, KEEP_BOTH }

    private MapManager() {}

    // ------------------------------------------------------------------ locations

    /** {@code <user data>/Maps}: the folder the engine scans for custom maps. */
    static File mapsDir(Context ctx) {
        return new File(SetupActivity.userDataDir(ctx), MAPS_DIR);
    }

    private static File requireMapsDir(Context ctx) throws IOException {
        File dir = mapsDir(ctx);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Could not create the Maps folder: " + dir);
        }
        return dir.getCanonicalFile();
    }

    // ------------------------------------------------------------------ discovery

    /** The installed maps, newest name last, skipping hidden folders and unreadable ones. */
    static List<Map> listMaps(Context ctx) {
        List<Map> result = new ArrayList<>();
        File dir = mapsDir(ctx);
        File[] children = dir.listFiles();
        if (children == null) {
            return result;
        }
        Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File child : children) {
            Map map = describe(child);
            if (map != null) {
                result.add(map);
            }
        }
        return result;
    }

    /** A folder as a map, or null when it is not one (the engine would ignore it too). */
    static Map describe(File dir) {
        if (dir == null || !dir.isDirectory() || dir.getName().startsWith(".")
                || java.nio.file.Files.isSymbolicLink(dir.toPath())) {
            return null;
        }
        File mapFile = new File(dir, dir.getName() + MAP_EXTENSION);
        if (!mapFile.isFile()) {
            mapFile = findIgnoreCase(dir, dir.getName() + MAP_EXTENSION);
        }
        if (mapFile == null || !mapFile.isFile()) {
            return null;
        }
        File preview = findIgnoreCase(dir, dir.getName() + ".tga");
        long bytes = 0;
        int files = 0;
        File[] entries = dir.listFiles();
        if (entries != null) {
            for (File f : entries) {
                if (f.isFile()) {
                    bytes += f.length();
                    files++;
                }
            }
        }
        return new Map(dir, dir.getName(), mapFile, preview, bytes, files);
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

    /**
     * Whether a file is a map the engine can load: a {@code .map} is an {@code EAR\0} RefPack
     * stream (CompressionManager) or, uncompressed, the {@code CkMp} chunk tree at its head.
     */
    static boolean isMapFile(File file) {
        if (file == null || !file.isFile() || file.length() < 16) {
            return false;
        }
        try (InputStream in = new FileInputStream(file)) {
            byte[] head = new byte[4];
            int read = 0;
            while (read < 4) {
                int n = in.read(head, read, 4 - read);
                if (n <= 0) {
                    return false;
                }
                read += n;
            }
            if (head[0] == 'E' && head[1] == 'A' && head[2] == 'R' && head[3] == 0) {
                return true;
            }
            return head[0] == 'C' && head[1] == 'k' && head[2] == 'M' && head[3] == 'p';
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ prepare

    static Plan prepareTree(Context ctx, Uri treeUri, ModImportService.CancellationSignal cancellation)
            throws IOException {
        cancellation.throwIfCancelled();
        File mapsDir = requireMapsDir(ctx);
        ModImportService.cleanupStaleStaging(mapsDir);

        ContentResolver resolver = ctx.getContentResolver();
        String documentId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri rootDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);

        Budget budget = new Budget();
        File stage = newStage(mapsDir);
        try {
            copyTree(resolver, treeUri, documentId, stage, budget, 0, cancellation);
            return plan(stage, budget);
        } catch (IOException | RuntimeException e) {
            ModImportService.deleteRecursively(stage);
            if (cancellation.isCancelled()) {
                throw new ModImportService.ImportCancelledException();
            }
            throw e;
        }
    }

    static Plan prepareZip(Context ctx, Uri uri, ModImportService.CancellationSignal cancellation)
            throws IOException {
        cancellation.throwIfCancelled();
        File mapsDir = requireMapsDir(ctx);
        ModImportService.cleanupStaleStaging(mapsDir);

        Budget budget = new Budget();
        File stage = newStage(mapsDir);
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
                    budget.addFile();
                    copyStream(zip, dest, budget, cancellation);
                }
                zip.closeEntry();
            }
            return plan(stage, budget);
        } catch (IOException | RuntimeException e) {
            ModImportService.deleteRecursively(stage);
            if (cancellation.isCancelled()) {
                throw new ModImportService.ImportCancelledException();
            }
            throw e;
        }
    }

    private static File newStage(File mapsDir) throws IOException {
        File staging = new File(mapsDir, ModImportService.INSTALLING_DIR).getCanonicalFile();
        if (!ModImportService.isInside(mapsDir, staging) || !staging.isDirectory() && !staging.mkdirs()) {
            throw new IOException("Could not create the map staging directory");
        }
        File stage = new File(staging, UUID.randomUUID().toString());
        if (!stage.mkdirs()) {
            throw new IOException("Could not create the map staging directory");
        }
        return stage;
    }

    /**
     * Validates the staged tree and works out which folders are maps. The engine only accepts
     * {@code <name>/<name>.map} (hasValidMapLeafLayout), so a map file that sits alone at the top
     * of the selection is given a folder of its own; anything deeper is refused rather than
     * installed where the game would ignore it.
     */
    private static Plan plan(File stage, Budget budget) throws IOException {
        List<File> mapFiles = new ArrayList<>();
        collectMapFiles(stage, mapFiles, 0);
        if (mapFiles.isEmpty()) {
            throw new ModImportService.ImportProblem(R.string.mapman_invalid_map);
        }

        List<Entry> entries = new ArrayList<>();
        if (mapFiles.size() == 1 && mapFiles.get(0).getParentFile().equals(stage)) {
            File mapFile = mapFiles.get(0);
            if (!isMapFile(mapFile)) {
                throw new ModImportService.ImportProblem(R.string.mapman_invalid_map_file);
            }
            String name = ModImportService.sanitizeInstallName(baseName(mapFile.getName()), false);
            File root = new File(stage, name);
            if (!root.mkdirs()) {
                throw new IOException("Could not prepare the map folder");
            }
            File[] children = stage.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (child.isFile() && !child.renameTo(new File(root, child.getName()))) {
                        throw new IOException("Could not prepare the map folder");
                    }
                }
            }
            entries.add(new Entry(root, name));
        } else {
            for (File mapFile : mapFiles) {
                File parent = mapFile.getParentFile();
                if (parent == null || !parent.getName().equalsIgnoreCase(baseName(mapFile.getName()))) {
                    throw new ModImportService.ImportProblem(R.string.mapman_invalid_map);
                }
                if (!isMapFile(mapFile)) {
                    throw new ModImportService.ImportProblem(R.string.mapman_invalid_map_file);
                }
                String name = ModImportService.sanitizeInstallName(baseName(mapFile.getName()), false);
                entries.add(new Entry(parent, name));
            }
        }
        return new Plan(stage, entries, budget.fileCount, budget.bytes);
    }

    private static void collectMapFiles(File dir, List<File> out, int depth) {
        if (depth > MAX_DEPTH || out.size() > 64) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (java.nio.file.Files.isSymbolicLink(child.toPath())) {
                continue;
            }
            if (child.isDirectory()) {
                collectMapFiles(child, out, depth + 1);
            } else if (child.getName().toLowerCase(Locale.US).endsWith(MAP_EXTENSION)) {
                out.add(child);
            }
        }
    }

    /** The map names already installed that this plan would land on. */
    static List<String> conflicts(Plan plan, Context ctx) {
        List<String> names = new ArrayList<>();
        File mapsDir = mapsDir(ctx);
        for (Entry entry : plan.entries) {
            if (new File(mapsDir, entry.name).exists()) {
                names.add(entry.name);
            }
        }
        return names;
    }

    // ------------------------------------------------------------------ commit

    /** Installs a prepared plan; the staging is removed whatever the outcome. */
    static Result commit(Context ctx, Plan plan, Policy policy) throws IOException {
        File mapsDir = requireMapsDir(ctx);
        List<Map> installed = new ArrayList<>();
        int replaced = 0;
        int keptBoth = 0;
        try {
            for (Entry entry : plan.entries) {
                File target = ModImportService.uniqueTarget(mapsDir, entry.name, false);
                boolean exists = new File(mapsDir, entry.name).exists();
                if (exists && policy == Policy.KEEP_BOTH) {
                    keptBoth++;
                } else if (exists) {
                    swapIn(entry.root, new File(mapsDir, entry.name), plan.stage);
                    replaced++;
                    target = new File(mapsDir, entry.name);
                } else {
                    finishInstall(entry.root, target);
                }
                Map map = describe(target);
                if (map != null) {
                    installed.add(map);
                }
            }
        } finally {
            ModImportService.deleteRecursively(plan.stage);
        }
        return new Result(installed, replaced, keptBoth);
    }

    /** Removes the staging of a plan that was prepared but never committed. */
    static void abort(Plan plan) {
        if (plan != null) {
            ModImportService.deleteRecursively(plan.stage);
        }
    }

    private static void finishInstall(File stage, File target) throws IOException {
        if (target.exists()) {
            throw new IOException("Destination already exists: " + target.getName());
        }
        if (!stage.renameTo(target)) {
            throw new IOException("Could not finish the map installation");
        }
    }

    /** Replaces an existing map without a window in which neither copy exists. */
    private static void swapIn(File stage, File target, File staging) throws IOException {
        File backup = new File(staging, "replaced-" + UUID.randomUUID());
        if (!target.renameTo(backup)) {
            throw new IOException("Could not replace the installed map");
        }
        if (!stage.renameTo(target)) {
            backup.renameTo(target);
            throw new IOException("Could not finish the map installation");
        }
        ModImportService.deleteRecursively(backup);
    }

    /** Deletes a map, but only a direct child of the Maps folder -- never anything else. */
    static boolean delete(Context ctx, Map map) {
        File dir = map.dir;
        File parent = dir.getParentFile();
        File maps = mapsDir(ctx);
        if (parent == null || !parent.getAbsolutePath().equals(maps.getAbsolutePath())
                || java.nio.file.Files.isSymbolicLink(dir.toPath())) {
            return false;
        }
        ModImportService.deleteRecursively(dir);
        return !dir.exists();
    }

    // ------------------------------------------------------------------ export

    /** Copies a map into a folder the player picked, as {@code <name>/<name>.map ...}. */
    static int exportFolder(Context ctx, Map map, Uri treeUri,
                            ModImportService.CancellationSignal cancellation) throws IOException {
        ContentResolver resolver = ctx.getContentResolver();
        String parentId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri parentDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId);
        Uri folder = DocumentsContract.createDocument(resolver, parentDoc,
            DocumentsContract.Document.MIME_TYPE_DIR, map.name);
        if (folder == null) {
            throw new IOException("Could not create the destination folder");
        }
        String folderId = DocumentsContract.getDocumentId(folder);
        Uri folderDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri, folderId);
        Budget budget = new Budget();
        copyToTree(resolver, treeUri, folderDoc, map.dir, budget, cancellation, 0);
        return budget.fileCount;
    }

    private static void copyToTree(ContentResolver resolver, Uri treeUri, Uri parentDoc, File source,
                                   Budget budget, ModImportService.CancellationSignal cancellation,
                                   int depth) throws IOException {
        cancellation.throwIfCancelled();
        if (depth > MAX_DEPTH) {
            throw new IOException("Map folder nesting is too deep");
        }
        File[] children = source.listFiles();
        if (children == null) {
            throw new IOException("Could not read the map folder");
        }
        for (File child : children) {
            cancellation.throwIfCancelled();
            if (java.nio.file.Files.isSymbolicLink(child.toPath())) {
                continue;
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
                Uri file = DocumentsContract.createDocument(resolver, parentDoc, "application/octet-stream",
                    child.getName());
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

    /** Writes the map as a ZIP into a document the player picked (ACTION_CREATE_DOCUMENT). */
    static int exportZip(Context ctx, Map map, Uri destination,
                         ModImportService.CancellationSignal cancellation) throws IOException {
        Budget budget = new Budget();
        try (OutputStream raw = ctx.getContentResolver().openOutputStream(destination, "w");
             ZipOutputStream zip = raw == null ? null
                 : new ZipOutputStream(new BufferedOutputStream(raw, BUFFER_SIZE))) {
            if (zip == null) {
                throw new IOException("Could not write the selected ZIP file");
            }
            zipFile(zip, map.dir, map.name + "/", budget, cancellation, 0);
        }
        return budget.fileCount;
    }

    private static void zipFile(ZipOutputStream zip, File dir, String prefix, Budget budget,
                                ModImportService.CancellationSignal cancellation, int depth)
            throws IOException {
        cancellation.throwIfCancelled();
        if (depth > MAX_DEPTH) {
            throw new IOException("Map folder nesting is too deep");
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            cancellation.throwIfCancelled();
            if (java.nio.file.Files.isSymbolicLink(child.toPath())) {
                continue;
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

    // ------------------------------------------------------------------ copy helpers

    private static void copyTree(ContentResolver resolver, Uri treeUri, String parentDocumentId,
                                 File destDir, Budget budget, int depth,
                                 ModImportService.CancellationSignal cancellation) throws IOException {
        cancellation.throwIfCancelled();
        if (depth > MAX_DEPTH) {
            throw new IOException("Map folder nesting is too deep");
        }
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId);
        String[] projection = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        };
        try (Cursor cursor = resolver.query(children, projection, null, null, null)) {
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
                    copyTree(resolver, treeUri, childId, dest, budget, depth + 1, cancellation);
                } else {
                    budget.addFile();
                    Uri childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId);
                    try (InputStream in = resolver.openInputStream(childUri)) {
                        if (in == null) {
                            throw new IOException("Could not open: " + childName);
                        }
                        copyStream(in, dest, budget, cancellation);
                    }
                }
            }
        }
    }

    private static void copyStream(InputStream in, File dest, Budget budget,
                                   ModImportService.CancellationSignal cancellation) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create the destination directory");
        }
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(dest), BUFFER_SIZE)) {
            copy(in, out, cancellation);
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

    private static String baseName(String fileName) {
        String lower = fileName.toLowerCase(Locale.US);
        return lower.endsWith(MAP_EXTENSION)
            ? fileName.substring(0, fileName.length() - MAP_EXTENSION.length()) : fileName;
    }

    /** Bounded counters, so a hostile archive cannot fill the phone before validation fails. */
    private static final class Budget {
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
                throw new IOException("The selection is larger than the 2 GiB import limit");
            }
        }
    }
}
