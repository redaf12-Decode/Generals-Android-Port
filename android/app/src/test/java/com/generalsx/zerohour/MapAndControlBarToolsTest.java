// GeneralsX @build Android port 07/11/2026
//
// The two Tools screens both parse user-supplied bytes before the game ever sees them, so this
// drives the real code paths (no mocks): MapManager against a real Maps/ folder and real ZIPs,
// ControlBarBig against a real BIG archive built byte for byte the way ModPacker writes one.
//
// What matters here and nowhere else:
//   - the layout the engine accepts (<name>/<name>.map, MapCache::hasValidMapLeafLayout);
//   - that a hostile ZIP cannot escape the staging folder;
//   - that only the ControlBar entries of an archive are selected, and unrelated INIs are not.

package com.generalsx.zerohour;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RunWith(RobolectricTestRunner.class)
public class MapAndControlBarToolsTest {

    private static Context app() {
        return RuntimeEnvironment.getApplication();
    }

    private static File cache(String name) {
        return new File(app().getCacheDir(), name + "-" + System.nanoTime());
    }

    // ------------------------------------------------------------------ map manager

    @Test
    public void describeAndListFindTheMapsTheEngineWould() throws IOException {
        File maps = MapManager.mapsDir(app());
        assertTrue(maps.isDirectory() || maps.mkdirs());

        File good = new File(maps, "TestMap");
        assertTrue(good.mkdirs());
        write(new File(good, "TestMap.map"), mapBytes());
        write(new File(good, "TestMap.tga"), new byte[32]);
        write(new File(good, "readme.txt"), new byte[4]);

        File notAMap = new File(maps, "NotAMap");
        assertTrue(notAMap.mkdirs());
        write(new File(notAMap, "something.map"), mapBytes());

        MapManager.Map described = MapManager.describe(good);
        assertNotNull(described);
        assertEquals("TestMap", described.name);
        assertEquals("TestMap.map", described.mapFile.getName());
        assertNotNull("the preview picture is found", described.preview);
        assertEquals(3, described.fileCount);

        // The folder must be named after the .map inside it; a mismatched pair is not a map.
        assertNull(MapManager.describe(notAMap));

        boolean found = false;
        for (MapManager.Map map : MapManager.listMaps(app())) {
            found |= "TestMap".equals(map.name);
        }
        assertTrue("an installed map is listed", found);
    }

    @Test
    public void onlyACompressedOrChunkedMapFileIsAccepted() throws IOException {
        File ear = cache("ear.map");
        byte[] earBytes = new byte[32];
        earBytes[0] = 'E';
        earBytes[1] = 'A';
        earBytes[2] = 'R';
        write(ear, earBytes);
        assertTrue(MapManager.isMapFile(ear));

        File junk = cache("junk.map");
        write(junk, "not a map at all, honestly".getBytes(StandardCharsets.US_ASCII));
        assertFalse(MapManager.isMapFile(junk));
    }

    @Test
    public void importingAZipInstallsTheMapAndTheGameCanFindIt() throws IOException {
        File zip = cache("import.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            entry(out, "Imported/Imported.map", mapBytes());
            entry(out, "Imported/Imported.tga", new byte[16]);
            entry(out, "Imported/readme.txt", "hello".getBytes(StandardCharsets.US_ASCII));
        }

        ModImportService.CancellationSignal signal = new ModImportService.CancellationSignal();
        MapManager.Plan plan = MapManager.prepareZip(app(), Uri.fromFile(zip), signal);
        assertEquals(1, plan.entries.size());
        assertEquals("Imported", plan.entries.get(0).name);

        MapManager.Result result = MapManager.commit(app(), plan, MapManager.Policy.REPLACE);
        assertEquals(1, result.installed.size());

        File installed = new File(MapManager.mapsDir(app()), "Imported");
        assertTrue(new File(installed, "Imported.map").isFile());
        assertTrue(new File(installed, "readme.txt").isFile());
        File[] staging = new File(installed.getParentFile(),
            ModImportService.INSTALLING_DIR).listFiles();
        assertTrue("the staging folder is empty after a commit",
            staging == null || staging.length == 0);
    }

    @Test
    public void aZipThatTriesToEscapeTheStagingFolderIsRefused() throws IOException {
        File zip = cache("traversal.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            entry(out, "../../evil/evil.map", mapBytes());
        }
        ModImportService.CancellationSignal signal = new ModImportService.CancellationSignal();
        assertThrows(IOException.class,
            () -> MapManager.prepareZip(app(), Uri.fromFile(zip), signal));
    }

    @Test
    public void aZipWithoutAnyMapFileIsRefusedWithAMessageThePlayerCanActOn() throws IOException {
        File zip = cache("empty.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            entry(out, "notes.txt", "nothing to see".getBytes(StandardCharsets.US_ASCII));
        }
        ModImportService.CancellationSignal signal = new ModImportService.CancellationSignal();
        ModImportService.ImportProblem problem = assertThrows(ModImportService.ImportProblem.class,
            () -> MapManager.prepareZip(app(), Uri.fromFile(zip), signal));
        assertEquals(R.string.mapman_invalid_map, problem.messageRes);
    }

    @Test
    public void exportingAMapAsZipKeepsItsFolderAndFiles() throws IOException {
        File maps = MapManager.mapsDir(app());
        File mapDir = new File(maps, "ExportMe");
        assertTrue(mapDir.mkdirs() || mapDir.isDirectory());
        write(new File(mapDir, "ExportMe.map"), mapBytes());
        write(new File(mapDir, "ExportMe.tga"), new byte[8]);
        MapManager.Map map = MapManager.describe(mapDir);
        assertNotNull(map);

        File destination = cache("export.zip");
        ModImportService.CancellationSignal signal = new ModImportService.CancellationSignal();
        int count = MapManager.exportZip(app(), map, Uri.fromFile(destination), signal);
        assertEquals(2, count);

        List<String> names = new ArrayList<>();
        try (java.util.zip.ZipInputStream in =
                 new java.util.zip.ZipInputStream(new java.io.FileInputStream(destination))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        assertTrue(names.toString(), names.contains("ExportMe/ExportMe.map"));
        assertTrue(names.toString(), names.contains("ExportMe/ExportMe.tga"));
    }

    @Test
    public void deleteOnlyRemovesAMapThatLivesInTheMapsFolder() {
        File maps = MapManager.mapsDir(app());
        File mapDir = new File(maps, "DeleteMe");
        assertTrue(mapDir.mkdirs() || mapDir.isDirectory());
        write(new File(mapDir, "DeleteMe.map"), mapBytes());
        MapManager.Map map = MapManager.describe(mapDir);
        assertNotNull(map);

        assertTrue(MapManager.delete(app(), map));
        assertFalse(mapDir.exists());

        // A folder elsewhere in user data is not a map the launcher may delete.
        File elsewhere = new File(SetupActivity.userDataDir(app()), "NotInMaps");
        assertTrue(elsewhere.mkdirs() || elsewhere.isDirectory());
        write(new File(elsewhere, "NotInMaps.map"), mapBytes());
        MapManager.Map outside = new MapManager.Map(elsewhere, "NotInMaps",
            new File(elsewhere, "NotInMaps.map"), null, 0, 1);
        assertFalse(MapManager.delete(app(), outside));
        assertTrue(elsewhere.exists());
    }

    // ------------------------------------------------------------------ control bar big

    @Test
    public void onlyTheControlBarEntriesOfAnArchiveAreSelected() throws IOException {
        File big = cache("controlbar.big");
        byte[] controlBarWnd = "WINDOW\n  ControlBarParent\nEND\n".getBytes(StandardCharsets.US_ASCII);
        // A scheme names the mapped images it draws with; a MappedImages INI defines an image and
        // the texture behind it. Only the image definitions the scheme names are pulled in.
        byte[] scheme = ("ControlBarScheme Test\n  Side = America\n"
            + "  RightHUDImage = MyImage\nEnd\n").getBytes(StandardCharsets.US_ASCII);
        byte[] images = "MappedImage MyImage\n  Texture = MyIcon.tga\nEnd\n"
            .getBytes(StandardCharsets.US_ASCII);
        byte[] otherImages = "MappedImage OtherImage\n  Texture = Other.tga\nEnd\n"
            .getBytes(StandardCharsets.US_ASCII);
        byte[] barArt = new byte[64];
        byte[] iconArt = new byte[64];
        byte[] unrelated = "Object Foo\nEnd\n".getBytes(StandardCharsets.US_ASCII);
        byte[] unrelatedArt = new byte[64];

        writeBig(big, new String[] {
            "Window\\ControlBar.wnd",
            "Data\\INI\\ControlBarScheme\\Test.ini",
            "Data\\INI\\MappedImages\\Test.ini",
            "Data\\INI\\MappedImages\\Other.ini",
            "Art\\Textures\\MyBar.tga",
            "Art\\Textures\\MyIcon.tga",
            "Data\\INI\\Object\\Foo.ini",
            "Art\\Textures\\Other.tga",
        }, new byte[][] {
            controlBarWnd, scheme, images, otherImages, barArt, iconArt, unrelated, unrelatedArt,
        });

        ModImportService.CancellationSignal signal = new ModImportService.CancellationSignal();
        ControlBarBig.Detection detection =
            ControlBarBig.inspect(app(), Uri.fromFile(big), "controlbar.big", signal);
        try {
            assertEquals(1, detection.layouts.size());
            assertEquals(1, detection.schemes.size());
            assertEquals("only the image definition the scheme names is taken", 1,
                detection.images.size());
            assertTrue(detection.hasControlBar());

            List<String> art = new ArrayList<>();
            for (ControlBarBig.Entry entry : detection.art) {
                art.add(entry.path.toLowerCase(Locale.US));
            }
            assertTrue(art.toString(), art.contains("art\\textures\\myicon.tga"));
            assertFalse("art the scheme never names is not pulled in",
                art.toString().contains("other.tga"));
            assertFalse(art.toString(), art.toString().contains("mybar.tga"));

            for (ControlBarBig.Entry entry : detection.selected()) {
                assertFalse("an unrelated INI must never be selected",
                    entry.path.toLowerCase(Locale.US).contains("object\\foo.ini"));
            }
        } finally {
            ControlBarBig.discard(detection);
        }
    }

    @Test
    public void importingWritesAModFolderTheModsScreenSees() throws IOException {
        File big = cache("import-controlbar.big");
        byte[] controlBarWnd = "WINDOW\n  ControlBarParent\nEND\n".getBytes(StandardCharsets.US_ASCII);
        byte[] scheme = "ControlBarScheme Test\n  RightHUDImage = MyImage\nEnd\n"
            .getBytes(StandardCharsets.US_ASCII);
        byte[] images = "MappedImage MyImage\n  Texture = MyIcon.tga\nEnd\n"
            .getBytes(StandardCharsets.US_ASCII);
        byte[] iconArt = new byte[64];
        writeBig(big, new String[] {
            "Window\\ControlBar.wnd",
            "Data\\INI\\ControlBarScheme\\Test.ini",
            "Data\\INI\\MappedImages\\Test.ini",
            "Art\\Textures\\MyIcon.tga",
        }, new byte[][] { controlBarWnd, scheme, images, iconArt });

        // The Mods screen needs a game folder; point it at a scratch one.
        File game = new File(app().getFilesDir(), "import-game");
        assertTrue(game.mkdirs() || game.isDirectory());
        android.content.SharedPreferences prefs = app().getSharedPreferences(
            SetupActivity.PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(SetupActivity.PREF_GAME_PATH, game.getAbsolutePath()).commit();

        ModImportService.CancellationSignal signal = new ModImportService.CancellationSignal();
        ControlBarBig.Detection detection =
            ControlBarBig.inspect(app(), Uri.fromFile(big), "import-controlbar.big", signal);
        ControlBarBig.Imported imported = ControlBarBig.importAsMod(app(), detection, signal);
        ControlBarBig.discard(detection);

        assertTrue(imported.modDir.isDirectory());
        assertEquals(4, imported.fileCount);
        assertTrue(new File(imported.modDir, "Window/ControlBar.wnd").isFile());
        assertTrue(new File(imported.modDir, "Data/INI/ControlBarScheme/Test.ini").isFile());
        assertTrue("the loose files are packed into the mod's own archive",
            new File(imported.modDir, ModPacker.PACKED_NAME).isFile());
        assertFalse("the staging folder is cleaned up",
            new File(imported.modDir.getParentFile(), ModImportService.INSTALLING_DIR).exists()
                && new File(imported.modDir.getParentFile(), ModImportService.INSTALLING_DIR)
                    .listFiles().length > 0);

        boolean listed = false;
        for (ModManager.Mod mod : ModManager.listMods(app())) {
            listed |= mod.dir.equals(imported.modDir);
        }
        assertTrue("the Mods screen lists the imported control bar", listed);
    }

    @Test
    public void anArchiveWithoutControlBarFilesIsRefusedWithItsOwnMessage() throws IOException {
        File big = cache("plain.big");
        writeBig(big, new String[] { "Data\\INI\\Object\\Foo.ini" },
            new byte[][] { "Object Foo\nEnd\n".getBytes(StandardCharsets.US_ASCII) });

        ModImportService.CancellationSignal signal = new ModImportService.CancellationSignal();
        ModImportService.ImportProblem problem = assertThrows(ModImportService.ImportProblem.class,
            () -> ControlBarBig.inspect(app(), Uri.fromFile(big), "plain.big", signal));
        assertEquals(R.string.cbbig_no_controlbar, problem.messageRes);
    }

    @Test
    public void somethingThatIsNotAnArchiveIsRefused() throws IOException {
        File notBig = cache("not.big");
        write(notBig, "PK\u0003\u0004 this is a zip, not a BIG".getBytes(StandardCharsets.US_ASCII));

        ModImportService.CancellationSignal signal = new ModImportService.CancellationSignal();
        ModImportService.ImportProblem problem = assertThrows(ModImportService.ImportProblem.class,
            () -> ControlBarBig.inspect(app(), Uri.fromFile(notBig), "not.big", signal));
        assertEquals(R.string.cbbig_unsupported, problem.messageRes);
    }

    // ------------------------------------------------------------------ helpers

    private static void write(File file, byte[] bytes) {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void entry(ZipOutputStream out, String name, byte[] bytes) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(bytes);
        out.closeEntry();
    }

    /** A file the map loader accepts: the uncompressed {@code CkMp} chunk tree at its head. */
    private static byte[] mapBytes() {
        byte[] bytes = new byte[64];
        bytes[0] = 'C';
        bytes[1] = 'k';
        bytes[2] = 'M';
        bytes[3] = 'p';
        return bytes;
    }

    /**
     * A BIG archive written the way {@code StdBIGFileSystem::openArchiveFile} and ModPacker write
     * one: "BIGF", little-endian size, big-endian count and header size, then per entry a
     * big-endian offset and size and a NUL-terminated path, then the data.
     */
    private static void writeBig(File out, String[] paths, byte[][] data) throws IOException {
        int header = 16;
        byte[][] encoded = new byte[paths.length][];
        for (int i = 0; i < paths.length; i++) {
            encoded[i] = paths[i].getBytes(StandardCharsets.ISO_8859_1);
            header += 8 + encoded[i].length + 1;
        }
        long total = header;
        for (byte[] bytes : data) {
            total += bytes.length;
        }
        try (FileOutputStream o = new FileOutputStream(out)) {
            o.write(new byte[] { 'B', 'I', 'G', 'F' });
            writeLE(o, (int) total);
            writeBE(o, paths.length);
            writeBE(o, header);
            long offset = header;
            for (int i = 0; i < paths.length; i++) {
                writeBE(o, (int) offset);
                writeBE(o, data[i].length);
                o.write(encoded[i]);
                o.write(0);
                offset += data[i].length;
            }
            for (byte[] bytes : data) {
                o.write(bytes);
            }
        }
    }

    private static void writeBE(FileOutputStream o, int v) throws IOException {
        o.write(v >>> 24);
        o.write(v >>> 16);
        o.write(v >>> 8);
        o.write(v);
    }

    private static void writeLE(FileOutputStream o, int v) throws IOException {
        o.write(v);
        o.write(v >>> 8);
        o.write(v >>> 16);
        o.write(v >>> 24);
    }
}
