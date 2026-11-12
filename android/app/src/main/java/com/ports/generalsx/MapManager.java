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

/**
 * GeneralsX @feature Android port 07/11/2026 Where the game keeps its maps, and what a map is.
 *
 * <p>The engine discovers user maps by scanning {@code <user data>/Maps} on every map-list
 * refresh ({@code MapCache::updateCache} -&gt; {@code loadMapsFromDisk(userMapDir)}), so a map
 * copied in shows up at the next map list with no restart. {@code MapCache::getUserMapDir()} is
 * {@code getPath_UserData() + "Maps"}, and on Android the user-data folder is the plain
 * {@code /storage/emulated/&lt;uid&gt;/Generals/&lt;leaf&gt;} directory SDL3Main.cpp hands the
 * engine -- the same folder {@link SetupActivity#userDataDir} resolves, so the launcher and the
 * game agree on the path without a second guess.
 *
 * <p>Two rules are the whole of "is this a map", both read out of the engine:
 * <ul>
 *   <li>{@code MapCache::hasValidMapLeafLayout} accepts a {@code .map} only at
 *       {@code <name>\<name>.map}, i.e. a map is a folder named after the {@code .map} file
 *       inside it;</li>
 *   <li>a {@code .map} is an {@code EAR\0} RefPack stream ({@code CompressionManager}) or,
 *       uncompressed, the {@code CkMp} chunk tree at its head.</li>
 * </ul>
 * {@link MapPackManager} builds on this class rather than repeating it, so the launcher never
 * guesses a path or a file format.
 */
final class MapManager {
    static final String MAPS_DIR = "Maps";
    static final String MAP_EXTENSION = ".map";
    static final int MAX_DEPTH = 32;
    private static final int BUFFER_SIZE = 64 * 1024;

    /** One map folder the game can load: {@code <name>/<name>.map} plus its supporting files. */
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

    private MapManager() {}

    // ------------------------------------------------------------------ locations

    /** {@code <user data>/Maps}: the folder the engine scans for maps. */
    static File mapsDir(Context ctx) {
        return new File(SetupActivity.userDataDir(ctx), MAPS_DIR);
    }

    static File requireMapsDir(Context ctx) throws IOException {
        File dir = mapsDir(ctx);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Could not create the Maps folder: " + dir);
        }
        return dir.getCanonicalFile();
    }

    // ------------------------------------------------------------------ discovery

    /** The installed maps, by name, skipping hidden folders and anything the engine ignores. */
    static List<Map> listMaps(Context ctx) {
        List<Map> result = new ArrayList<>();
        File[] children = mapsDir(ctx).listFiles();
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
        File mapFile = findIgnoreCase(dir, dir.getName() + MAP_EXTENSION);
        if (mapFile == null) {
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

    /**
     * Every valid map folder under {@code root} -- a folder named after the {@code .map} inside
     * it -- one per map name (case-insensitive), shallowest first. A stray {@code .map} that does
     * not follow the layout is not a map, exactly as the engine treats it.
     */
    static List<File> findMapFolders(File root) {
        List<File> found = new ArrayList<>();
        Set<String> names = new HashSet<>();
        collect(root, found, names, 0);
        found.sort((a, b) -> {
            int depthA = depth(root, a);
            int depthB = depth(root, b);
            return depthA != depthB ? Integer.compare(depthA, depthB)
                : a.getName().compareToIgnoreCase(b.getName());
        });
        return found;
    }

    private static void collect(File dir, List<File> found, Set<String> names, int depth) {
        if (dir == null || depth > MAX_DEPTH || java.nio.file.Files.isSymbolicLink(dir.toPath())) {
            return;
        }
        Map map = describe(dir);
        if (map != null) {
            if (names.add(map.name.toLowerCase(Locale.US))) {
                found.add(dir);
            }
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File child : children) {
            if (child.isDirectory() && !child.getName().startsWith(".")) {
                collect(child, found, names, depth + 1);
            }
        }
    }

    private static int depth(File root, File file) {
        int depth = 0;
        File current = file;
        while (current != null && !current.equals(root)) {
            depth++;
            current = current.getParentFile();
        }
        return depth;
    }

    // ------------------------------------------------------------------ copy

    /** Copies {@code source/...} into {@code target}, preserving the source's own layout. */
    static void copyMap(File source, File target, ModImportService.CancellationSignal cancellation)
            throws IOException {
        if (!target.isDirectory() && !target.mkdirs()) {
            throw new IOException("Could not create the map folder: " + target.getName());
        }
        copyTree(source, target, cancellation, 0);
    }

    private static void copyTree(File source, File target, ModImportService.CancellationSignal cancellation,
                                 int depth) throws IOException {
        cancellation.throwIfCancelled();
        if (depth > MAX_DEPTH) {
            throw new IOException("Map folder nesting is too deep");
        }
        File[] children = source.listFiles();
        if (children == null) {
            throw new IOException("Could not read the map folder: " + source.getName());
        }
        for (File child : children) {
            cancellation.throwIfCancelled();
            if (java.nio.file.Files.isSymbolicLink(child.toPath())) {
                continue;
            }
            File dest = new File(target, child.getName());
            if (child.isDirectory()) {
                if (!dest.isDirectory() && !dest.mkdirs()) {
                    throw new IOException("Could not create the destination folder");
                }
                copyTree(child, dest, cancellation, depth + 1);
            } else {
                try (InputStream in = new BufferedInputStream(new FileInputStream(child), BUFFER_SIZE);
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(dest), BUFFER_SIZE)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        cancellation.throwIfCancelled();
                        out.write(buffer, 0, n);
                    }
                }
            }
        }
    }
}
