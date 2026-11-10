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

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * GeneralsX @feature Android port 07/11/2026 The ControlBar side of a BIG archive: its file table,
 * which of those files the control bar actually needs, and how to read one entry out.
 *
 * <p>This is the reader {@link ControlBarPackManager} uses; it invents no second archive format.
 * The table is BIGF/BIG4 ("BIGF" or "BIG4", a little-endian declared size, a big-endian file count
 * and header size, then per file a big-endian offset and size and a NUL-terminated path) -- the
 * same layout {@code StdBIGFileSystem::openArchiveFile} and {@code ModPacker} read and write, and
 * the bounds are the ones {@code ModHealthCheck.checkBigTable} already applies.
 *
 * <p><b>What the engine reads for the control bar</b> (read out of the engine, not guessed):
 * <ul>
 *   <li>the layout, {@code Window\ControlBar.wnd} -- {@code winCreateFromScript}, through
 *       {@code FileSystem::openFile}, which asks the local file system (the game folder, the
 *       engine's working directory) before the archives;</li>
 *   <li>the schemes, {@code Data\INI\Default\ControlBarScheme\*} then
 *       {@code Data\INI\ControlBarScheme\*} -- {@code ControlBarSchemeManager::init};</li>
 *   <li>the mapped-image definitions, {@code Data\INI\MappedImages\*} -- {@code Image.cpp};</li>
 *   <li>the textures those name, under {@code Data\<language>\Art\Textures\} then
 *       {@code Art\Textures\} -- {@code W3DFileSystem}, which is why a language directory may
 *       appear in an archive's paths.</li>
 * </ul>
 *
 * <p>An archive is a ControlBar archive when it carries a layout or a scheme; a bare
 * MappedImages/texture archive is refused as ambiguous (it could be a unit-skin pack), which is
 * what "do not assume every BIG archive contains ControlBar assets" asks for.
 */
final class ControlBarBig {
    private static final int MAX_ENTRIES = 200000;
    private static final int MAX_NAME_BYTES = 4096;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long MAX_TEXT_BYTES = 4L * 1024L * 1024L;

    /** The language folders the engine can look in: {@code Data\<token>\Art\Textures\}. */
    private static final Set<String> LANGUAGE_DIRS = new LinkedHashSet<>(java.util.Arrays.asList(
        "english", "german", "french", "spanish", "italian", "chinese", "korean", "polish",
        "brazilian", "russian", "ukrainian", "interslavic", "arabic", "persian", "japanese"));

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

    // ------------------------------------------------------------------ reading

    /** Whether a file starts with a readable BIGF/BIG4 table (no payload is read). */
    static boolean isBig(File archive) {
        return ModHealthCheck.checkBigTable(archive) >= 0;
    }

    /**
     * The archive's file table, with the bounds {@code ModHealthCheck.checkBigTable} uses: entry
     * count, name length and every offset+size inside the file. Throws
     * {@link ModImportService.ImportProblem} with {@link R.string#cbbig_unsupported} otherwise.
     */
    static List<Entry> readEntries(File archive) throws IOException {
        List<Entry> entries = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new FileInputStream(archive), BUFFER_SIZE))) {
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (magic[0] != 'B' || magic[1] != 'I' || magic[2] != 'G'
                    || (magic[3] != 'F' && magic[3] != '4')) {
                throw new ModImportService.ImportProblem(R.string.cbbig_unsupported);
            }
            in.readInt(); // declared archive size, little-endian; not trusted
            int count = in.readInt();
            int headerSize = in.readInt();
            long length = archive.length();
            if (count < 0 || count > MAX_ENTRIES || headerSize < 16 || headerSize > length) {
                throw new ModImportService.ImportProblem(R.string.cbbig_unsupported);
            }
            long tableBytes = 16;
            for (int i = 0; i < count; i++) {
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
                if (offset > length || size > length
                        || offset + size < offset || offset + size > length) {
                    throw new ModImportService.ImportProblem(R.string.cbbig_unsupported);
                }
                entries.add(new Entry(path.toString().replace('/', '\\'), offset, size));
            }
        }
        return entries;
    }

    /**
     * Reads the archive and keeps the entries the control bar needs, following the chain the
     * engine uses: a scheme names the mapped images it draws with, a MappedImages INI defines an
     * image and the texture behind it ({@code Image.cpp}). Everything else in the archive is left
     * alone.
     */
    static Detection detect(File archive) throws IOException {
        if (!isBig(archive)) {
            throw new ModImportService.ImportProblem(R.string.cbbig_unsupported);
        }
        List<Entry> entries = readEntries(archive);
        Detection detection = new Detection(archive, archive.getName(), entries.size());
        classify(archive, entries, detection);
        if (!detection.hasControlBar()) {
            throw new ModImportService.ImportProblem(R.string.cbbig_no_controlbar);
        }
        return detection;
    }

    private static void classify(File archive, List<Entry> entries, Detection detection)
            throws IOException {
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
        Set<String> schemeTextures = new LinkedHashSet<>();
        for (Entry entry : detection.schemes) {
            collectSchemeNames(readEntry(archive, entry), wantedImages, schemeTextures);
        }

        // Which MappedImages INIs to keep. With a scheme in the archive, only the definitions of
        // images the scheme actually names -- an unrelated image definition shipped alongside (a
        // unit skin's) is not a control bar file. With no scheme (a layout-only retexture), every
        // definition is kept, because the images it redefines are the game's own scheme's.
        Set<String> wantedTextures = new LinkedHashSet<>(schemeTextures);
        for (Entry entry : mappedImageInis) {
            Map<String, Set<String>> defined = parseMappedImages(readEntry(archive, entry));
            boolean keep = detection.schemes.isEmpty();
            for (Map.Entry<String, Set<String>> image : defined.entrySet()) {
                if (detection.schemes.isEmpty() || wantedImages.contains(image.getKey())) {
                    keep = true;
                    wantedTextures.addAll(image.getValue());
                }
            }
            if (keep) {
                detection.images.add(entry);
            }
        }

        for (Entry entry : entries) {
            String path = lower(entry.path);
            if (isTexture(path) && isArtPath(path) && matchesAnyStem(path, wantedTextures)) {
                detection.art.add(entry);
            }
        }
        if (detection.art.isEmpty()) {
            // A scheme that names only the game's own images (or an archive with no resolvable
            // texture name) still needs its art: take the archive's own art folders, and nothing
            // else -- never its models, maps or sounds.
            for (Entry entry : entries) {
                String path = lower(entry.path);
                if (isTexture(path) && isArtPath(path)) {
                    detection.art.add(entry);
                }
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

    /** The engine's texture folders: {@code Art\...} and {@code Data\<language>\Art\...}. */
    private static boolean isArtPath(String path) {
        return path.startsWith("art\\") || isLocalizedArtPath(path);
    }

    private static boolean isLocalizedArtPath(String path) {
        if (!path.startsWith("data\\")) {
            return false;
        }
        int cut = path.indexOf('\\', 5);
        return cut > 5 && LANGUAGE_DIRS.contains(path.substring(5, cut))
            && path.startsWith("art\\", cut + 1);
    }

    private static boolean isTexture(String path) {
        return path.endsWith(".tga") || path.endsWith(".dds") || path.endsWith(".jpg")
            || path.endsWith(".png");
    }

    /** A texture whose stem is one of the wanted names, or that name with the engine's suffix. */
    private static boolean matchesAnyStem(String path, Set<String> wanted) {
        String stem = stem(baseName(path));
        if (wanted.contains(stem)) {
            return true;
        }
        int cut = stem.indexOf('_');
        return cut > 0 && wanted.contains(stem.substring(0, cut));
    }

    /**
     * The names a scheme refers to: a bare token ({@code RightHUDImage = CBRightHUD}) is a mapped
     * image, and a {@code Texture =} value is a texture. Coordinates, colours and durations are
     * numbers and are skipped; a name no MappedImages INI defines is simply never used.
     */
    private static void collectSchemeNames(String text, Set<String> images, Set<String> textures) {
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
        return !Character.isDigit(value.charAt(0));
    }

    private static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** The text of one archive entry, bounded so a hostile table cannot exhaust memory. */
    private static String readEntry(File archive, Entry entry) throws IOException {
        if (entry.size > MAX_TEXT_BYTES) {
            return "";
        }
        byte[] bytes = new byte[(int) entry.size];
        try (RandomAccessFile in = new RandomAccessFile(archive, "r")) {
            in.seek(entry.offset);
            in.readFully(bytes);
        }
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    // ------------------------------------------------------------------ extraction

    /** Writes one entry out, preserving its bytes exactly. */
    static void extract(File archive, Entry entry, File dest,
                        ModImportService.CancellationSignal cancellation) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create the destination folder");
        }
        try (RandomAccessFile in = new RandomAccessFile(archive, "r");
             FileOutputStream out = new FileOutputStream(dest)) {
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

    // ------------------------------------------------------------------ paths

    /**
     * Where an archive entry is installed in the game folder. The internal path is preserved,
     * except that a leading language folder -- {@code Data\<token>\...} -- is rewritten to the
     * language the game actually reads, {@code Data\<gameLanguage>\...}, when the archive's token
     * differs. That is the one place a path may change, and only for the folders the engine
     * resolves per language ({@code W3DFileSystem}); every other entry keeps its own path.
     */
    static String installPath(String entryPath, String gameLanguage) {
        if (gameLanguage == null || gameLanguage.isEmpty()) {
            return entryPath;
        }
        String[] parts = entryPath.replace('/', '\\').split("\\\\", -1);
        if (parts.length > 2 && parts[0].equalsIgnoreCase("data")
                && LANGUAGE_DIRS.contains(parts[1].toLowerCase(Locale.US))
                && !parts[1].equalsIgnoreCase(gameLanguage)) {
            parts[1] = gameLanguage;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) {
                    sb.append('\\');
                }
                sb.append(parts[i]);
            }
            return sb.toString();
        }
        return entryPath;
    }

    /** Whether a folder name is one the engine resolves per language ({@code Data\<name>\}). */
    static boolean isLanguageFolder(String name) {
        return name != null && LANGUAGE_DIRS.contains(name.toLowerCase(Locale.US));
    }

    private static String baseName(String path) {
        int cut = path.lastIndexOf('\\');
        return cut >= 0 ? path.substring(cut + 1) : path;
    }
}
