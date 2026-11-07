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

import android.content.Context;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * GeneralsX @feature Android port 07/11/2026 ControlBar BIG importer (Tools).
 *
 * <p>The engine reads control bar layouts from the archive file system: {@code Window\ControlBar.wnd}
 * (the layout itself), the schemes under {@code Data\INI\ControlBarScheme\*} and
 * {@code Data\INI\Default\ControlBarScheme\*} (ControlBarSchemeManager::init), and the image
 * definitions under {@code Data\INI\MappedImages\*} that name the textures a scheme draws with.
 * All of those are ordinary archives entries, so a mod that ships them in a {@code .big} only has
 * to be mounted -- no extraction into the game folder is needed.
 *
 * <p>This importer reads the archive's directory table, keeps only the ControlBar-related entries
 * (and the textures those entries reference), writes them as loose files into a managed mod folder
 * and lets {@link ModPacker} pack them into the folder's own archive. From then on the mod is
 * switched on and ordered exactly like any other in the Mods screen -- it reuses the existing mod
 * loading infrastructure rather than inventing a second one -- and nothing unrelated in the
 * archive is mounted.
 */
final class ControlBarBig {
    private static final int MAX_ENTRIES = 200000;
    private static final int MAX_NAME_BYTES = 4096;
    private static final int BUFFER_SIZE = 64 * 1024;

    /** One archive entry: its path exactly as stored (backslashes) and where its data lives. */
    static final class Entry {
        final String path;
        final long offset;
        final long size;

        Entry(String path, long offset, long size) {
            this.path = path;
            this.offset = offset;
            this.size = size;
        }
    }

    /** What an archive holds that the control bar cares about. */
    static final class Detection {
        final File archive;
        final String archiveName;
        final int totalEntries;
        final List<Entry> layouts = new ArrayList<>();
        final List<Entry> schemes = new ArrayList<>();
        final List<Entry> images = new ArrayList<>();
        final List<Entry> art = new ArrayList<>();

        Detection(File archive, String archiveName, int totalEntries) {
            this.archive = archive;
            this.archiveName = archiveName;
            this.totalEntries = totalEntries;
        }

        List<Entry> selected() {
            List<Entry> out = new ArrayList<>();
            out.addAll(layouts);
            out.addAll(schemes);
            out.addAll(images);
            out.addAll(art);
            return out;
        }

        boolean hasControlBar() {
            return !layouts.isEmpty() || !schemes.isEmpty();
        }
    }

    private ControlBarBig() {}

    // ------------------------------------------------------------------ inspection

    /** Copies the selected document into the cache and reads its directory table. */
    static Detection inspect(Context ctx, Uri uri, String displayName,
                             ModImportService.CancellationSignal cancellation) throws IOException {
        String name = displayName == null || displayName.trim().isEmpty() ? "archive.big" : displayName;
        File temp = new File(ctx.getCacheDir(), "cb-" + UUID.randomUUID() + ".big");
        try {
            copyDocument(ctx, uri, temp, cancellation);
            if (!isBig(temp)) {
                throw new ModImportService.ImportProblem(R.string.cbbig_unsupported);
            }
            List<Entry> entries = readTable(temp, cancellation);
            Detection detection = new Detection(temp, name, entries.size());
            classify(temp, entries, detection, cancellation);
            if (!detection.hasControlBar()) {
                throw new ModImportService.ImportProblem(R.string.cbbig_no_controlbar);
            }
            return detection;
        } catch (IOException | RuntimeException e) {
            temp.delete();
            throw e;
        }
    }

    static void discard(Detection detection) {
        if (detection != null && detection.archive != null) {
            detection.archive.delete();
        }
    }

    private static void copyDocument(Context ctx, Uri uri, File dest,
                                     ModImportService.CancellationSignal cancellation) throws IOException {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(dest)) {
            if (in == null) {
                throw new IOException("Could not open the selected BIG archive");
            }
            byte[] buffer = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buffer)) > 0) {
                cancellation.throwIfCancelled();
                out.write(buffer, 0, n);
            }
        }
    }

    private static boolean isBig(File file) {
        return file.isFile() && file.length() >= 16;
    }

    /** The archive's file table: BIGF/BIG4 magic, then per file offset, size and a NUL path. */
    private static List<Entry> readTable(File file, ModImportService.CancellationSignal cancellation)
            throws IOException {
        List<Entry> entries = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new FileInputStream(file), BUFFER_SIZE))) {
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (magic[0] != 'B' || magic[1] != 'I' || magic[2] != 'G'
                    || (magic[3] != 'F' && magic[3] != '4')) {
                throw new ModImportService.ImportProblem(R.string.cbbig_unsupported);
            }
            in.readInt(); // declared archive size, little-endian; not trusted
            int count = in.readInt();
            int headerSize = in.readInt();
            if (count < 0 || count > MAX_ENTRIES || headerSize < 16 || headerSize > file.length()) {
                throw new ModImportService.ImportProblem(R.string.cbbig_unsupported);
            }
            long tableBytes = 16;
            for (int i = 0; i < count; i++) {
                if ((i & 255) == 0) {
                    cancellation.throwIfCancelled();
                }
                if (tableBytes + 9 > headerSize) {
                    throw new ModImportService.ImportProblem(R.string.cbbig_unsupported);
                }
                long offset = Integer.toUnsignedLong(in.readInt());
                long size = Integer.toUnsignedLong(in.readInt());
                tableBytes += 8;
                StringBuilder path = new StringBuilder();
                int c;
                do {
                    c = in.read();
                    if (c < 0) {
                        throw new ModImportService.ImportProblem(R.string.cbbig_unsupported);
                    }
                    tableBytes++;
                    if (path.length() > MAX_NAME_BYTES || tableBytes > headerSize) {
                        throw new ModImportService.ImportProblem(R.string.cbbig_unsupported);
                    }
                    if (c != 0) {
                        path.append((char) c);
                    }
                } while (c != 0);
                if (offset > file.length() || size > file.length()
                        || offset + size < offset || offset + size > file.length()) {
                    throw new ModImportService.ImportProblem(R.string.cbbig_unsupported);
                }
                entries.add(new Entry(path.toString().replace('/', '\\'), offset, size));
            }
        }
        return entries;
    }

    // ------------------------------------------------------------------ detection

    /**
     * Keeps the entries the control bar actually needs, following the chain the engine uses:
     * a scheme ({@code ControlBarScheme}) names the mapped images it draws with, and a MappedImages
     * INI defines a mapped image and the texture behind it ({@code Image.cpp}). So the import
     * carries the layout, the schemes, the image definitions those schemes need and their
     * textures -- never the rest of the archive.
     */
    private static void classify(File archive, List<Entry> entries, Detection detection,
                                 ModImportService.CancellationSignal cancellation) throws IOException {
        List<Entry> mappedImageInis = new ArrayList<>();
        for (Entry entry : entries) {
            String path = lower(entry.path);
            if (isControlBarLayout(path)) {
                detection.layouts.add(entry);
            } else if (isScheme(path)) {
                detection.schemes.add(entry);
            } else if (isMappedImages(path)) {
                mappedImageInis.add(entry);
            }
        }

        Set<String> wantedImages = new LinkedHashSet<>();
        Set<String> wantedTextures = new LinkedHashSet<>();
        for (Entry entry : detection.schemes) {
            collectNames(readEntry(archive, entry, cancellation), wantedImages, wantedTextures);
        }
        for (Entry entry : mappedImageInis) {
            for (Map.Entry<String, Set<String>> image
                    : parseMappedImages(readEntry(archive, entry, cancellation)).entrySet()) {
                if (wantedImages.contains(image.getKey())) {
                    detection.images.add(entry);
                    wantedTextures.addAll(image.getValue());
                    break;
                }
            }
        }

        if (wantedImages.isEmpty() && wantedTextures.isEmpty()) {
            // A scheme that names nothing resolvable still needs its art: take the control-bar art
            // folder only (never the whole archive's models, maps or sounds).
            for (Entry entry : entries) {
                String path = lower(entry.path);
                if (path.startsWith("art\\") && isTexture(path)) {
                    detection.art.add(entry);
                }
            }
            return;
        }
        for (Entry entry : entries) {
            String path = lower(entry.path);
            if (isTexture(path) && wantedTextures.contains(stem(baseName(path)))) {
                detection.art.add(entry);
            }
        }
    }

    /** Case-folded for the path rules: an archive's own case is never assumed. */
    private static String lower(String value) {
        return value.toLowerCase(Locale.US);
    }

    private static boolean isControlBarLayout(String path) {
        return (path.startsWith("window\\") || path.startsWith("generalsonlinegamedata\\"))
            && path.endsWith(".wnd") && baseName(path).startsWith("controlbar");
    }

    private static boolean isScheme(String path) {
        return path.endsWith(".ini")
            && (path.startsWith("data\\ini\\controlbarscheme\\")
                || path.startsWith("data\\ini\\default\\controlbarscheme\\"));
    }

    private static boolean isMappedImages(String path) {
        return path.endsWith(".ini") && path.startsWith("data\\ini\\mappedimages\\");
    }

    private static boolean isTexture(String path) {
        return path.endsWith(".tga") || path.endsWith(".dds") || path.endsWith(".jpg")
            || path.endsWith(".png");
    }

    /**
     * The names a scheme refers to: a bare token ({@code RightHUDImage = CBRightHUD}) is a mapped
     * image, and a {@code Texture =} value is a texture. Coordinates, colours and durations are
     * numbers and are skipped; a name no MappedImages INI defines is simply never used.
     */
    private static void collectNames(String text, Set<String> images, Set<String> textures) {
        for (String[] pair : assignments(text)) {
            String key = pair[0];
            String value = pair[1];
            if (key.equals("texture")) {
                textures.add(stem(value));
            } else if (isBareName(value)) {
                images.add(value);
            }
        }
    }

    /**
     * The mapped images an INI defines, each with the textures behind it: a {@code MappedImage
     * Name} line opens a block and a {@code Texture = file} inside it names that image's picture.
     */
    private static Map<String, Set<String>> parseMappedImages(String text) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        Set<String> current = null;
        for (String rawLine : text.split("\n")) {
            String line = strip(rawLine);
            if (line.isEmpty()) {
                continue;
            }
            if (line.toLowerCase(Locale.US).startsWith("mappedimage ")) {
                String name = line.substring("mappedimage ".length()).trim().toLowerCase(Locale.US);
                current = name.isEmpty() ? null : out.computeIfAbsent(name, k -> new LinkedHashSet<>());
            } else if (current != null) {
                String[] pair = assignment(line);
                if (pair != null && pair[0].equals("texture")) {
                    current.add(stem(pair[1]));
                }
            }
        }
        return out;
    }

    private static List<String[]> assignments(String text) {
        List<String[]> out = new ArrayList<>();
        for (String rawLine : text.split("\n")) {
            String[] pair = assignment(strip(rawLine));
            if (pair != null) {
                out.add(pair);
            }
        }
        return out;
    }

    /** One {@code Key = Value} line as {@code [key, value]} in lower case, or null. */
    private static String[] assignment(String line) {
        if (line.isEmpty() || line.startsWith(";") || line.startsWith("//")) {
            return null;
        }
        int eq = line.indexOf('=');
        if (eq <= 0) {
            return null;
        }
        String key = line.substring(0, eq).trim().toLowerCase(Locale.US);
        String value = line.substring(eq + 1).trim();
        int cut = value.indexOf(';');
        if (cut >= 0) {
            value = value.substring(0, cut).trim();
        }
        if (key.isEmpty() || value.isEmpty()) {
            return null;
        }
        return new String[] { key, value.toLowerCase(Locale.US) };
    }

    private static String strip(String rawLine) {
        String line = rawLine.trim();
        if (line.startsWith(";") || line.startsWith("//")) {
            return "";
        }
        return line;
    }

    /** A token that names something: no spaces, no path separators, not a number or a colour. */
    private static boolean isBareName(String value) {
        if (value.indexOf(' ') >= 0 || value.indexOf('\\') >= 0 || value.indexOf('/') >= 0
                || value.indexOf('.') >= 0 || value.indexOf('"') >= 0) {
            return false;
        }
        if (value.startsWith("0x") || value.startsWith("$")) {
            return false;
        }
        char first = value.charAt(0);
        return !Character.isDigit(first);
    }

    private static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** The text of one archive entry, bounded so a hostile table cannot exhaust memory. */
    private static String readEntry(File archive, Entry entry,
                                    ModImportService.CancellationSignal cancellation) throws IOException {
        if (entry.size > 4L * 1024L * 1024L) {
            return "";
        }
        cancellation.throwIfCancelled();
        byte[] bytes = new byte[(int) entry.size];
        try (RandomAccessFile in = new RandomAccessFile(archive, "r")) {
            in.seek(entry.offset);
            in.readFully(bytes);
        }
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    // ------------------------------------------------------------------ import

    static final class Imported {
        final File modDir;
        final int fileCount;

        Imported(File modDir, int fileCount) {
            this.modDir = modDir;
            this.fileCount = fileCount;
        }
    }

    /**
     * Writes the detected entries into a new managed mod folder, packed by ModPacker, so the
     * engine mounts exactly those files through the same path as every other mod.
     */
    static Imported importAsMod(Context ctx, Detection detection,
                                ModImportService.CancellationSignal cancellation) throws IOException {
        File modsRoot = ModManager.modsRoot(ctx);
        if (modsRoot == null) {
            throw new ModImportService.ImportProblem(R.string.mods_no_game_message);
        }
        if (!modsRoot.isDirectory() && !modsRoot.mkdirs()) {
            throw new IOException("Could not create the mods directory");
        }
        File staging = new File(modsRoot, ModImportService.INSTALLING_DIR);
        if (!staging.isDirectory() && !staging.mkdirs()) {
            throw new IOException("Could not create the mod staging directory");
        }
        File stage = new File(staging, UUID.randomUUID().toString());
        if (!stage.mkdirs()) {
            throw new IOException("Could not create the mod staging directory");
        }

        String base = detection.archiveName.toLowerCase(Locale.US).endsWith(".big")
            ? detection.archiveName.substring(0, detection.archiveName.length() - 4)
            : detection.archiveName;
        String name = ModImportService.sanitizeInstallName("ControlBar " + base, false);

        int written = 0;
        try {
            try (RandomAccessFile in = new RandomAccessFile(detection.archive, "r")) {
                for (Entry entry : detection.selected()) {
                    cancellation.throwIfCancelled();
                    File dest = ModImportService.resolveSafeRelative(stage, entry.path);
                    File parent = dest.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("Could not create a mod folder");
                    }
                    extract(in, entry, dest, cancellation);
                    written++;
                }
            }
            ModPacker.ensurePacked(stage);
            File target = ModImportService.uniqueTarget(modsRoot, name, false);
            cancellation.throwIfCancelled();
            if (!stage.renameTo(target)) {
                throw new IOException("Could not finish the ControlBar import");
            }
            return new Imported(target, written);
        } catch (IOException | RuntimeException e) {
            ModImportService.deleteRecursively(stage);
            if (cancellation.isCancelled()) {
                throw new ModImportService.ImportCancelledException();
            }
            throw e;
        }
    }

    private static void extract(RandomAccessFile in, Entry entry, File dest,
                                ModImportService.CancellationSignal cancellation) throws IOException {
        try (FileOutputStream out = new FileOutputStream(dest)) {
            in.seek(entry.offset);
            byte[] buffer = new byte[BUFFER_SIZE];
            long remaining = entry.size;
            while (remaining > 0) {
                cancellation.throwIfCancelled();
                int want = (int) Math.min(buffer.length, remaining);
                int n = in.read(buffer, 0, want);
                if (n <= 0) {
                    throw new IOException("The archive is truncated");
                }
                out.write(buffer, 0, n);
                remaining -= n;
            }
        }
    }

    private static String baseName(String path) {
        int cut = path.lastIndexOf('\\');
        return cut >= 0 ? path.substring(cut + 1) : path;
    }
}
