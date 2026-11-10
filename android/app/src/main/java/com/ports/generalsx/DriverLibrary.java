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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * GeneralsX @feature Android port 04/11/2026 The launcher's Vulkan driver library.
 *
 * The engine loads a custom Vulkan driver from exactly two places, and this class owns both:
 * {@code <internal>/custom_driver.cfg} (one line: the driver .so's file name) and
 * {@code <internal>/custom_driver/} (the driver .so and its dependencies) -- see
 * {@code TryLoadCustomVulkanDriver()} in Core/Main/SDL3Main.cpp. That contract is deliberately
 * unchanged: the native side still reads those two paths, byte for byte as before.
 *
 * What this class adds is a <em>library</em> on top of that one slot: every imported driver is
 * kept in its own folder under {@code <internal>/drivers/}, and "activating" one copies it into
 * the single slot the engine reads. So a player can import several drivers, see which is active,
 * switch between them, and delete the ones they no longer want -- without any of it changing how
 * the driver reaches the GPU.
 *
 * Three invariants the whole design rests on:
 *
 * <ul>
 * <li><b>A failed import never disturbs the active driver.</b> Extraction and validation happen
 *     before anything on disk is touched (CustomDriverPackage), and storing the new folder does
 *     not touch the slot. Only a fully validated package is ever activated.</li>
 * <li><b>Activation is all-or-nothing.</b> The current slot is moved aside first and restored if
 *     anything fails, so the engine is never left with a half-written driver directory.</li>
 * <li><b>GLES is unaffected.</b> Nothing here is read unless the Vulkan backend is selected --
 *     SetupActivity only builds and consults this when render_backend.cfg says "vulkan".</li>
 * </ul>
 *
 * No {@code android.*} imports, so all of the above is exercised by a plain JVM test rather than
 * only on a phone.
 */
final class DriverLibrary {
    private DriverLibrary() {
    }

    /** One driver per folder here, each holding meta.json + the driver .so (see describe()). */
    static final String DRIVERS_DIR_NAME = "drivers";
    /** The single slot the engine reads. Kept as a copy of whichever library entry is active. */
    static final String ACTIVE_DIR_NAME = "custom_driver";
    static final String CFG_NAME = "custom_driver.cfg";
    /** Info sidecar for the active slot, so the card can name it (see CustomDriverPackage). */
    static final String ACTIVE_INFO_NAME = "custom_driver.info";
    /** Present when the active driver was auto-selected rather than imported by the user. */
    static final String AUTO_MARKER_NAME = "custom_driver.auto";

    /** A driver in the library, as the Graphics card shows it. */
    static final class Entry {
        final File dir;
        final CustomDriverPackage.Meta meta;
        final boolean active;

        Entry(File dir, CustomDriverPackage.Meta meta, boolean active) {
            this.dir = dir;
            this.meta = meta;
            this.active = active;
        }

        String libraryName() {
            return meta.libraryName;
        }

        String displayName() {
            return meta.displayName();
        }

        /** Name, vendor and version as one line, for the card's supporting text. */
        String detail() {
            List<String> parts = new ArrayList<>();
            if (!meta.vendor.isEmpty()) {
                parts.add(meta.vendor);
            }
            if (!meta.driverVersion.isEmpty()) {
                parts.add(meta.driverVersion);
            }
            if (!meta.packageVersion.isEmpty()) {
                parts.add(meta.packageVersion);
            }
            return String.join(" \u00b7 ", parts);
        }
    }

    static File driversDir(File root) {
        return new File(root, DRIVERS_DIR_NAME);
    }

    static File activeDir(File root) {
        return new File(root, ACTIVE_DIR_NAME);
    }

    static File cfgFile(File root) {
        return new File(root, CFG_NAME);
    }

    static File activeInfoFile(File root) {
        return new File(root, ACTIVE_INFO_NAME);
    }

    static File autoMarkerFile(File root) {
        return new File(root, AUTO_MARKER_NAME);
    }

    /** True when the auto-selected driver (not a user import) is the active one. */
    static boolean isAutoActive(File root) {
        return autoMarkerFile(root).isFile();
    }

    /**
     * Every valid driver the library holds, sorted by display name. Folders that fail
     * {@link CustomDriverPackage#describe} (a partial copy, a hand-edited folder) are skipped
     * rather than listed -- naming a driver the native loader cannot open helps nobody.
     */
    static List<Entry> list(File root) {
        List<Entry> out = new ArrayList<>();
        File drivers = driversDir(root);
        File[] children = drivers.listFiles();
        if (children == null) {
            return out;
        }
        String activeName = activeLibraryName(root);
        CustomDriverPackage.Meta activeMeta = activeMeta(root);
        for (File child : children) {
            if (!child.isDirectory()) {
                continue;
            }
            CustomDriverPackage.Package pkg = CustomDriverPackage.describe(child);
            if (pkg == null) {
                continue;
            }
            out.add(new Entry(child, pkg.meta, matchesActive(pkg.meta, activeName, activeMeta)));
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                int byName = a.displayName().compareToIgnoreCase(b.displayName());
                return byName != 0 ? byName : a.libraryName().compareTo(b.libraryName());
            }
        });
        return out;
    }

    /** How many valid drivers are stored. Used to gate the auto-selection to an empty library. */
    static int count(File root) {
        return list(root).size();
    }

    /** The .so file name custom_driver.cfg currently names, or null when there is no config. */
    static String activeLibraryName(File root) {
        File cfg = cfgFile(root);
        if (!cfg.isFile()) {
            return null;
        }
        String line = readFirstLine(cfg);
        return line == null || line.isEmpty() ? null : line;
    }

    /**
     * The active driver: the library entry whose .so custom_driver.cfg names. Falls back to the
     * active slot itself when the config names a driver the library does not hold (an install
     * from before the library existed), so the card can still name what is running.
     */
    static Entry getActive(File root) {
        for (Entry e : list(root)) {
            if (e.active) {
                return e;
            }
        }
        String name = activeLibraryName(root);
        if (name == null) {
            return null;
        }
        CustomDriverPackage.Package pkg = CustomDriverPackage.describe(activeDir(root));
        if (pkg != null && pkg.meta.libraryName.equals(name)) {
            return new Entry(activeDir(root), pkg.meta, true);
        }
        return null;
    }

    /** The metadata of the driver currently in the active slot, or null when unreadable. */
    private static CustomDriverPackage.Meta activeMeta(File root) {
        CustomDriverPackage.Package pkg = CustomDriverPackage.describe(activeDir(root));
        return pkg != null ? pkg.meta : null;
    }

    /**
     * Whether a library entry is the one in the active slot. The .so name is the contract the
     * engine reads, so it must match; but two Turnip builds can ship the same soname (a release
     * and its debug build both provide {@code libvulkan_freedreno.so}), so when the slot's own
     * meta.json can be read its name and version have to match too -- otherwise switching between
     * such builds would leave both rows marked active.
     */
    private static boolean matchesActive(CustomDriverPackage.Meta candidate, String activeName,
                                         CustomDriverPackage.Meta activeMeta) {
        if (activeName == null || !activeName.equals(candidate.libraryName)) {
            return false;
        }
        if (activeMeta == null) {
            return true;  // slot unreadable: fall back to the .so name alone
        }
        return activeMeta.name.equals(candidate.name)
            && activeMeta.driverVersion.equals(candidate.driverVersion)
            && activeMeta.vendor.equals(candidate.vendor);
    }

    /**
     * Stores {@code pkg} in the library and makes it the active driver.
     *
     * The package has already been extracted and validated by CustomDriverPackage, so nothing
     * here can fail in a way that touches the currently active driver: the folder is moved into
     * the library first (a rename), and only then is the slot swapped by {@link #activate}, which
     * restores the previous driver if it cannot finish.
     */
    static Entry importDriver(File root, CustomDriverPackage.Package pkg)
            throws CustomDriverPackage.ImportException {
        File drivers = driversDir(root);
        if (!drivers.isDirectory() && !drivers.mkdirs()) {
            throw new CustomDriverPackage.ImportException(
                CustomDriverPackage.Reason.IO, "could not create the driver library folder");
        }
        // The same driver imported twice keeps one entry rather than two identical rows: the
        // stored copy is reused and simply made active again. Distinct builds are told apart by
        // name, version and vendor, so two Turnip releases that share a soname stay separate.
        File existing = findExisting(drivers, pkg.meta);
        if (existing != null) {
            if (!activate(root, existing, pkg.meta, false)) {
                throw new CustomDriverPackage.ImportException(
                    CustomDriverPackage.Reason.IO, "could not make the imported driver active");
            }
            return new Entry(existing, pkg.meta, true);
        }

        File dest = assignFolder(drivers, pkg.meta);
        deleteRecursive(dest);
        if (!pkg.dir.renameTo(dest)) {
            throw new CustomDriverPackage.ImportException(
                CustomDriverPackage.Reason.IO, "could not move the driver into the library");
        }
        try {
            CustomDriverPackage.writeDirInfo(dest, pkg.meta);
        } catch (IOException e) {
            // Only the card's wording depends on this; the driver itself is in place.
        }
        if (!activate(root, new Entry(dest, pkg.meta, false))) {
            deleteRecursive(dest);
            throw new CustomDriverPackage.ImportException(
                CustomDriverPackage.Reason.IO, "could not make the imported driver active");
        }
        return new Entry(dest, pkg.meta, true);
    }

    /** A stored entry describing the same driver as {@code meta}, or null when none does. */
    private static File findExisting(File drivers, CustomDriverPackage.Meta meta) {
        File[] children = drivers.listFiles();
        if (children == null) {
            return null;
        }
        for (File child : children) {
            if (!child.isDirectory()) {
                continue;
            }
            CustomDriverPackage.Package pkg = CustomDriverPackage.describe(child);
            if (pkg != null && sameDriver(pkg.meta, meta)) {
                return child;
            }
        }
        return null;
    }

    private static boolean sameDriver(CustomDriverPackage.Meta a, CustomDriverPackage.Meta b) {
        return a.libraryName.equals(b.libraryName)
            && a.name.equals(b.name)
            && a.driverVersion.equals(b.driverVersion)
            && a.vendor.equals(b.vendor);
    }

    /** Makes a stored library entry the active driver. Returns false, changing nothing, on failure. */
    static boolean activate(File root, Entry entry) {
        return activate(root, entry.dir, entry.meta, false);
    }

    /**
     * Copies {@code sourceDir} into the active slot and points custom_driver.cfg at it.
     *
     * A copy, not a rename: the library folder must survive so it can be re-activated later. The
     * slot is moved to {@code custom_driver.old} first and restored if anything fails, so a
     * failure here leaves the engine with the driver it started with -- never with a truncated
     * one. The config is written last, so it never names a driver that is not fully in place.
     */
    static boolean activate(File root, File sourceDir, CustomDriverPackage.Meta meta, boolean auto) {
        File slot = activeDir(root);
        File backup = new File(root, ACTIVE_DIR_NAME + ".old");
        deleteRecursive(backup);
        boolean hadPrevious = slot.exists();
        if (hadPrevious && !slot.renameTo(backup)) {
            return false;
        }

        boolean ok = false;
        try {
            if (!copyTree(sourceDir, slot)) {
                throw new IOException("could not copy the driver into the active slot");
            }
            if (!new File(slot, meta.libraryName).isFile()) {
                throw new IOException("the driver library did not survive the copy");
            }
            writeAtomically(cfgFile(root), meta.libraryName + "\n");
            writeAtomically(activeInfoFile(root), meta.toJson().toString());
            if (auto) {
                autoMarkerFile(root).createNewFile();
            } else {
                autoMarkerFile(root).delete();
            }
            ok = true;
        } catch (Exception e) {
            // Fall through to the finally block, which puts the previous driver back.
        } finally {
            if (ok) {
                deleteRecursive(backup);
            } else {
                deleteRecursive(slot);
                if (hadPrevious) {
                    backup.renameTo(slot);
                }
            }
        }
        return ok;
    }

    /**
     * Removes a driver from the library. When it was the active one the slot is reset first, so
     * custom_driver.cfg never outlives the driver it names.
     */
    static boolean delete(File root, Entry entry) {
        if (entry == null || entry.dir == null || !entry.dir.isDirectory()) {
            return false;
        }
        if (entry.active) {
            resetActiveSlot(root);
        }
        deleteRecursive(entry.dir);
        return !entry.dir.exists();
    }

    /**
     * Returns to the built-in / recommended driver without removing anything from the library:
     * {@code defaultPkg} (the bundled driver, when this build has one) becomes active with the
     * auto marker set, otherwise the phone's own stock driver is used. Imported drivers stay
     * listed and can be re-activated.
     */
    static boolean resetToDefault(File root, CustomDriverPackage.Package defaultPkg) {
        if (defaultPkg == null) {
            resetActiveSlot(root);
            return true;
        }
        File stored = ensureStored(root, defaultPkg);
        if (stored == null) {
            return false;
        }
        return activate(root, stored, defaultPkg.meta, true);
    }

    /**
     * Adopts the active driver into the library the first time the library is seen empty, so a
     * driver installed before the library existed (custom_driver.cfg + custom_driver/ and nothing
     * else) becomes listed and re-selectable instead of being stranded in the slot.
     *
     * It does not change the active choice: the slot and config are left exactly as they are, and
     * the adopted copy is only a source for a later activation. Idempotent -- once the library
     * holds anything this does nothing, so it is safe to call on every card build.
     */
    static void adoptActiveSlot(File root) {
        if (count(root) > 0) {
            return;
        }
        if (activeLibraryName(root) == null) {
            return;
        }
        File slot = activeDir(root);
        CustomDriverPackage.Package pkg = CustomDriverPackage.describe(slot);
        if (pkg == null) {
            return;
        }
        File drivers = driversDir(root);
        if (!drivers.isDirectory() && !drivers.mkdirs()) {
            return;
        }
        File dest = assignFolder(drivers, pkg.meta);
        if (!copyTree(slot, dest)) {
            deleteRecursive(dest);
            return;
        }
        try {
            CustomDriverPackage.writeDirInfo(dest, pkg.meta);
        } catch (IOException e) {
            // Non-fatal: the folder's own meta.json still describes it.
        }
    }

    /**
     * Brings the active slot and config back in step after an interrupted run: a slot left as
     * {@code custom_driver.old} (a process death between the two renames of an activation) is
     * restored, and stale {@code .old}/{@code .tmp} folders are cleared. Called before every
     * import and whenever the Graphics card is built, so the state heals itself on the next
     * launch instead of stranding the phone on its stock driver.
     */
    static void cleanupInterrupted(File root) {
        File slot = activeDir(root);
        File backup = new File(root, ACTIVE_DIR_NAME + ".old");
        if (!slot.exists() && backup.isDirectory()) {
            backup.renameTo(slot);
        }
        deleteRecursive(backup);
        deleteRecursive(new File(root, ACTIVE_DIR_NAME + ".tmp"));
        deleteRecursive(new File(root, ACTIVE_INFO_NAME + ".tmp"));
        deleteRecursive(new File(root, CFG_NAME + ".tmp"));
        File drivers = driversDir(root);
        File[] children = drivers.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.getName().endsWith(".old") || child.getName().endsWith(".tmp")) {
                    deleteRecursive(child);
                }
            }
        }
    }

    /**
     * Rebuilds the active slot from the config when it is missing or does not match what the
     * config names -- the case a library install leaves behind after an update that changed how
     * the slot is written. Returns true when the slot and config agree afterwards.
     */
    static boolean reconcile(File root) {
        String name = activeLibraryName(root);
        if (name == null) {
            return true;
        }
        File slot = activeDir(root);
        CustomDriverPackage.Package inSlot = CustomDriverPackage.describe(slot);
        if (inSlot != null && inSlot.meta.libraryName.equals(name)) {
            return true;  // already consistent
        }
        CustomDriverPackage.Meta slotMeta = activeMeta(root);
        for (Entry e : list(root)) {
            if (matchesActive(e.meta, name, slotMeta)) {
                return activate(root, e.dir, e.meta, isAutoActive(root));
            }
        }
        return false;
    }

    /** Writes the config/slot for the stock driver (no custom driver active). */
    static void resetActiveSlot(File root) {
        cfgFile(root).delete();
        activeInfoFile(root).delete();
        autoMarkerFile(root).delete();
        deleteRecursive(activeDir(root));
    }

    /**
     * Ensures {@code pkg} is present in the library, returning its folder. Used for the bundled
     * driver, which arrives as an asset tree rather than an imported .zip: if a matching library
     * entry already exists it is reused, so a reset does not accumulate copies.
     */
    static File ensureStored(File root, CustomDriverPackage.Package pkg) {
        File drivers = driversDir(root);
        if (!drivers.isDirectory() && !drivers.mkdirs()) {
            return null;
        }
        // Matched on the whole description, not just the soname, so a user driver that happens to
        // ship the same .so name is not mistaken for the bundled one.
        File existing = findExisting(drivers, pkg.meta);
        if (existing != null) {
            return existing;
        }
        File dest = assignFolder(drivers, pkg.meta);
        deleteRecursive(dest);
        if (pkg.dir.renameTo(dest)) {
            try {
                CustomDriverPackage.writeDirInfo(dest, pkg.meta);
            } catch (IOException e) {
                // Non-fatal: the folder's own meta.json still describes it.
            }
            return dest;
        }
        // The caller's package folder could not be moved (it may be a live asset staging
        // folder); copy instead so the asset tree is left where it was.
        if (copyTree(pkg.dir, dest)) {
            try {
                CustomDriverPackage.writeDirInfo(dest, pkg.meta);
            } catch (IOException e) {
                // Non-fatal, as above.
            }
            return dest;
        }
        deleteRecursive(dest);
        return null;
    }

    /** A unique, readable folder name for a stored driver: {@code <slug>-<libraryName>}. */
    private static File assignFolder(File drivers, CustomDriverPackage.Meta meta) {
        String slug = slug(meta.displayName().isEmpty() ? meta.libraryName : meta.displayName());
        if (slug.isEmpty()) {
            slug = "driver";
        }
        String base = slug + "-" + slug(meta.libraryName);
        File candidate = new File(drivers, base);
        int n = 2;
        while (candidate.exists()) {
            candidate = new File(drivers, base + "-" + n);
            n++;
        }
        return candidate;
    }

    private static String slug(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                sb.append(Character.toLowerCase(c));
            } else if (c == ' ' || c == '-' || c == '_' || c == '.') {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '-') {
                    sb.append('-');
                }
            }
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    // -------------------------------------------------------------- file helpers

    private static boolean copyTree(File source, File dest) {
        if (source == null || !source.isDirectory()) {
            return false;
        }
        if (!dest.isDirectory() && !dest.mkdirs()) {
            return false;
        }
        File[] children = source.listFiles();
        if (children == null) {
            return false;
        }
        for (File child : children) {
            File target = new File(dest, child.getName());
            if (child.isDirectory()) {
                if (!copyTree(child, target)) {
                    return false;
                }
            } else {
                try (InputStream in = new FileInputStream(child);
                     OutputStream out = new FileOutputStream(target)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                } catch (IOException e) {
                    return false;
                }
            }
        }
        return true;
    }

    private static String readFirstLine(File f) {
        try (java.io.BufferedReader r =
                 new java.io.BufferedReader(new java.io.FileReader(f))) {
            String line = r.readLine();
            return line != null ? line.trim() : null;
        } catch (IOException e) {
            return null;
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
        CustomDriverPackage.deleteRecursive(file);
    }
}
