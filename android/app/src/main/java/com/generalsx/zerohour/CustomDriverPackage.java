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

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * GeneralsX @feature Android port 04/11/2026 adrenotools Vulkan driver packages.
 *
 * The Setup app lets a player load their own Vulkan driver (a Mesa Turnip build, most of the
 * time) in place of the phone's stock one, through libadrenotools -- see
 * TryLoadCustomVulkanDriver() in Core/Main/SDL3Main.cpp, which reads
 * &lt;internalPath&gt;/custom_driver.cfg (one line: the driver .so's file name) and loads it from
 * &lt;internalPath&gt;/custom_driver/.
 *
 * The package format is the one Winlator, AetherSX2 and PPSSPP all accept: a .zip holding
 * meta.json plus the driver .so and any dependency .so's beside it, possibly nested one folder
 * down. This class owns everything between "the user picked a file" and "custom_driver/ and
 * custom_driver.cfg are in place": it unpacks, validates, and installs.
 *
 * The archive is untrusted input, so every step is bounded and every failure leaves the
 * previous driver untouched -- a bad package must never take the working one with it, and must
 * never take the app down either. No android.* imports, so the pipeline can be exercised by a
 * plain JVM test.
 */
final class CustomDriverPackage {
    private CustomDriverPackage() {
    }

    static final String META_NAME = "meta.json";

    // A real adrenotools package is meta.json plus a few .so files of tens of MB. These caps
    // are not a format limit; they exist so a malformed or hostile archive cannot fill the
    // phone's internal storage before anything has been validated.
    static final int MAX_ENTRIES = 512;
    static final long MAX_TOTAL_BYTES = 512L * 1024L * 1024L;
    static final int MAX_DEPTH = 8;
    static final long MAX_META_BYTES = 64L * 1024L;

    /** Why a package was refused. The caller maps each to a localized message. */
    enum Reason {
        NOT_A_ZIP,
        UNSAFE_PATH,
        NO_META,
        BAD_META,
        MISSING_LIBRARY,
        BAD_LIBRARY,
        MIN_API,
        TOO_LARGE,
        IO
    }

    /** A refusal, carrying the values its message needs (see {@link #args}). */
    static final class ImportException extends Exception {
        private static final long serialVersionUID = 1L;

        final Reason reason;
        final String[] args;

        ImportException(Reason reason, String detail, String... args) {
            super(detail != null ? detail : reason.name());
            this.reason = reason;
            this.args = args != null ? args : new String[0];
        }
    }

    /** The meta.json fields an adrenotools package can carry. */
    static final class Meta {
        final String name;
        final String description;
        final String author;
        final String packageVersion;
        final String vendor;
        final String driverVersion;
        final String libraryName;
        final int minApi;

        Meta(String name, String description, String author, String packageVersion, String vendor,
             String driverVersion, String libraryName, int minApi) {
            this.name = name;
            this.description = description;
            this.author = author;
            this.packageVersion = packageVersion;
            this.vendor = vendor;
            this.driverVersion = driverVersion;
            this.libraryName = libraryName;
            this.minApi = minApi;
        }

        /** What the Graphics card shows for this driver: its name, not its .so's file name. */
        String displayName() {
            if (name.isEmpty()) {
                return libraryName;
            }
            return driverVersion.isEmpty() ? name : name + " " + driverVersion;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("name", name);
                o.put("description", description);
                o.put("author", author);
                o.put("packageVersion", packageVersion);
                o.put("vendor", vendor);
                o.put("driverVersion", driverVersion);
                o.put("libraryName", libraryName);
                o.put("minApi", minApi);
            } catch (org.json.JSONException e) {
                // None of these values can make JSONObject.put throw.
            }
            return o;
        }
    }

    /** A validated package: its metadata, and the folder holding meta.json and the driver. */
    static final class Package {
        final Meta meta;
        final File dir;

        Package(Meta meta, File dir) {
            this.meta = meta;
            this.dir = dir;
        }
    }

    /** Parses meta.json, keeping every field the format defines. */
    static Meta parseMeta(String json) throws ImportException {
        if (json == null || json.trim().isEmpty()) {
            throw new ImportException(Reason.BAD_META, "meta.json is empty");
        }
        try {
            JSONObject o = new JSONObject(json);
            return new Meta(
                o.optString("name", "").trim(),
                o.optString("description", "").trim(),
                o.optString("author", "").trim(),
                o.optString("packageVersion", "").trim(),
                o.optString("vendor", "").trim(),
                o.optString("driverVersion", "").trim(),
                o.optString("libraryName", "").trim(),
                o.optInt("minApi", 0));
        } catch (org.json.JSONException e) {
            throw new ImportException(Reason.BAD_META, e.getMessage());
        }
    }

    /**
     * Unpacks {@code zip} into {@code tmpDir} and validates it, returning the folder the driver
     * was found in. Nothing is installed here; on any refusal {@code tmpDir} is the caller's to
     * remove.
     */
    static Package extract(InputStream zip, File tmpDir, int deviceApiLevel)
            throws ImportException {
        List<File> metaFiles = new ArrayList<>();
        try {
            String rootCanonical = tmpDir.getCanonicalPath();
            byte[] buf = new byte[65536];
            long total = 0;
            int entries = 0;
            boolean any = false;

            try (ZipInputStream in = new ZipInputStream(zip)) {
                ZipEntry entry;
                while ((entry = in.getNextEntry()) != null) {
                    any = true;
                    if (++entries > MAX_ENTRIES) {
                        throw new ImportException(Reason.TOO_LARGE, "too many entries");
                    }
                    String name = entry.getName();
                    if (name == null || name.isEmpty()) {
                        continue;
                    }
                    if (depthOf(name) > MAX_DEPTH) {
                        throw new ImportException(Reason.TOO_LARGE, "entry nested too deeply");
                    }
                    // Zip Slip, explicitly: no absolute entries and no ".." segments. The
                    // canonical-path check below is the authoritative guard; this one makes
                    // the intent legible and keeps the error message specific.
                    if (name.startsWith("/") || name.startsWith("\\") || hasParentSegment(name)) {
                        throw new ImportException(Reason.UNSAFE_PATH, name, name);
                    }

                    File out = new File(tmpDir, name).getCanonicalFile();
                    if (!out.getPath().equals(rootCanonical)
                            && !out.getPath().startsWith(rootCanonical + File.separator)) {
                        throw new ImportException(Reason.UNSAFE_PATH, name, name);
                    }
                    if (entry.isDirectory()) {
                        out.mkdirs();
                        continue;
                    }
                    File parent = out.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                        throw new ImportException(Reason.IO, "could not create " + parent);
                    }
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            total += n;
                            if (total > MAX_TOTAL_BYTES) {
                                throw new ImportException(Reason.TOO_LARGE, "archive is too large");
                            }
                            fos.write(buf, 0, n);
                        }
                    }
                    if (META_NAME.equals(out.getName())) {
                        metaFiles.add(out);
                    }
                }
            }
            // ZipInputStream reports a non-archive by simply having no entries, rather than
            // throwing, so "nothing at all" is what a picked .so / .txt / truncated file looks
            // like from here.
            if (!any) {
                throw new ImportException(Reason.NOT_A_ZIP, "archive has no entries");
            }
        } catch (ImportException e) {
            throw e;
        } catch (Exception e) {
            throw new ImportException(Reason.NOT_A_ZIP, e.getMessage());
        }

        if (metaFiles.isEmpty()) {
            throw new ImportException(Reason.NO_META, "no " + META_NAME + " in the archive");
        }
        if (metaFiles.size() > 1) {
            throw new ImportException(Reason.BAD_META,
                "the archive contains " + metaFiles.size() + " " + META_NAME + " files");
        }

        File metaFile = metaFiles.get(0);
        Meta meta;
        try {
            meta = parseMeta(readText(metaFile, MAX_META_BYTES));
        } catch (IOException e) {
            throw new ImportException(Reason.IO, e.getMessage());
        }

        if (meta.libraryName.isEmpty()) {
            throw new ImportException(Reason.BAD_META, META_NAME + " has no libraryName");
        }
        // The native side does std::string(customDriverDir) + libraryName, so a name with a
        // separator would point outside custom_driver/ (or fail stat() outright) -- the format
        // never uses one.
        if (meta.libraryName.contains("/") || meta.libraryName.contains("\\")
                || meta.libraryName.equals(".") || meta.libraryName.equals("..")) {
            throw new ImportException(Reason.BAD_META,
                "libraryName is not a plain file name: " + meta.libraryName);
        }

        File library = new File(metaFile.getParentFile(), meta.libraryName);
        if (!library.isFile()) {
            throw new ImportException(Reason.MISSING_LIBRARY, meta.libraryName, meta.libraryName);
        }
        if (!isAarch64Elf(library)) {
            throw new ImportException(Reason.BAD_LIBRARY, meta.libraryName, meta.libraryName);
        }
        // A package that names a driver also has to carry it; a stray .so next to meta.json
        // (a dependency, say) is allowed, which is why this only looks for the named one.
        // minApi 0 (or absent) means the package states no floor; a device that reports no API
        // level (0) is treated the same way rather than being refused on a missing value.
        if (meta.minApi > 0 && deviceApiLevel > 0 && meta.minApi > deviceApiLevel) {
            throw new ImportException(Reason.MIN_API, null,
                String.valueOf(meta.minApi), String.valueOf(deviceApiLevel));
        }

        return new Package(meta, metaFile.getParentFile());
    }

    /**
     * Swaps {@code pkg} into {@code destDir} and writes the config the native side reads.
     *
     * The current driver is moved aside first and restored if anything fails, so an import that
     * goes wrong halfway leaves the app with the driver it started with -- not with none.
     */
    static void install(Package pkg, File destDir, File cfgFile, File infoFile, File autoMarker)
            throws ImportException {
        File backupDir = new File(destDir.getParentFile(), destDir.getName() + ".old");
        deleteRecursive(backupDir);
        boolean hadPrevious = destDir.exists();
        if (hadPrevious && !destDir.renameTo(backupDir)) {
            throw new ImportException(Reason.IO, "could not move the current driver aside");
        }

        boolean installed = false;
        try {
            if (!pkg.dir.renameTo(destDir)) {
                throw new ImportException(Reason.IO, "could not move the new driver into place");
            }
            File library = new File(destDir, pkg.meta.libraryName);
            if (!library.isFile()) {
                throw new ImportException(Reason.IO, "the driver library did not survive the move");
            }
            writeAtomically(cfgFile, pkg.meta.libraryName + "\n");
            // Sidecar for the status line only: custom_driver.cfg stays exactly the one-line
            // file TryLoadCustomVulkanDriver() already parses, and nothing on the native side
            // reads this.
            writeAtomically(infoFile, pkg.meta.toJson().toString());
            installed = true;
        } catch (ImportException e) {
            throw e;
        } catch (Exception e) {
            throw new ImportException(Reason.IO, e.getMessage());
        } finally {
            if (installed) {
                deleteRecursive(backupDir);
            } else {
                deleteRecursive(destDir);
                if (hadPrevious) {
                    backupDir.renameTo(destDir);
                }
            }
        }

        // An explicit user choice is no longer the auto-selected default.
        if (autoMarker != null) {
            autoMarker.delete();
        }
    }

    /**
     * Undoes a half-finished install left behind by the process dying between
     * {@link #install}'s two renames -- at that moment the previous driver sits in
     * &lt;dest&gt;.old and nothing is in &lt;dest&gt;.
     *
     * Called before every import and whenever the Graphics card is built, so the state heals
     * itself on the next launch instead of leaving the phone on its stock driver with
     * custom_driver.cfg still naming a driver that is not there. Also clears a stale
     * extraction folder, which a crash mid-unpack would otherwise leave behind.
     */
    static void recoverInterruptedInstall(File destDir) {
        File parent = destDir.getParentFile();
        if (parent == null) {
            return;
        }
        File backupDir = new File(parent, destDir.getName() + ".old");
        if (!destDir.exists() && backupDir.isDirectory()) {
            backupDir.renameTo(destDir);
        }
        deleteRecursive(backupDir);
        deleteRecursive(new File(parent, destDir.getName() + ".tmp"));
    }

    /** Reads a previously written info sidecar, or null when there is none or it is unreadable. */
    static Meta readInfo(File infoFile) {
        if (infoFile == null || !infoFile.isFile()) {
            return null;
        }
        try {
            return parseMeta(readText(infoFile, MAX_META_BYTES));
        } catch (Exception e) {
            return null;
        }
    }

    /** Records the metadata of an auto-selected driver, so the card can name it like a user one. */
    static void writeInfo(File infoFile, Meta meta) throws IOException {
        writeAtomically(infoFile, meta.toJson().toString());
    }

    /**
     * True for a 64-bit little-endian AArch64 ELF object.
     *
     * The only ABI this APK ships (abiFilters arm64-v8a), and the check that turns "meta.json
     * names a file that is there" into "meta.json names a driver that could actually load":
     * an x86 or 32-bit build would otherwise be installed and only fail much later, inside
     * adrenotools, with a bare ENOENT that says nothing about the real cause.
     */
    static boolean isAarch64Elf(File file) {
        try (InputStream in = new FileInputStream(file)) {
            byte[] header = new byte[20];
            int read = 0;
            while (read < header.length) {
                int n = in.read(header, read, header.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            if (read < header.length) {
                return false;
            }
            if (header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F') {
                return false;
            }
            if (header[4] != 2 || header[5] != 1) {  // EI_CLASS = ELFCLASS64, EI_DATA = ELFDATA2LSB
                return false;
            }
            int machine = (header[18] & 0xff) | ((header[19] & 0xff) << 8);  // e_machine
            return machine == 0xB7;  // EM_AARCH64
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean hasParentSegment(String name) {
        for (String segment : name.split("[/\\\\]")) {
            if (segment.equals("..")) {
                return true;
            }
        }
        return false;
    }

    private static int depthOf(String name) {
        int depth = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\') {
                depth++;
            }
        }
        return depth;
    }

    private static String readText(File file, long maxBytes) throws IOException {
        if (file.length() > maxBytes) {
            throw new IOException(file.getName() + " is unexpectedly large");
        }
        byte[] bytes = new byte[(int) file.length()];
        try (InputStream in = new FileInputStream(file)) {
            int read = 0;
            while (read < bytes.length) {
                int n = in.read(bytes, read, bytes.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            return new String(bytes, 0, read, StandardCharsets.UTF_8);
        }
    }

    private static void writeAtomically(File target, String text) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(target)) {
            deleteRecursive(tmp);
            throw new IOException("could not replace " + target.getName());
        }
    }

    static void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }
}
