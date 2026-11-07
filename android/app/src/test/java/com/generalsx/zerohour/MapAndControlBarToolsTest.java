// GeneralsX @build Android port 07/11/2026
//
// The two Tools screens both parse user-supplied bytes before the game ever sees them, so this
// drives the real code paths (no mocks): MapPackManager against a real maps-manager folder and a
// real Maps folder, ControlBarPackManager against a real Controlsbar-Manager folder, a real game
// folder and a real BIG archive built byte for byte the way ModPacker writes one.
//
// What matters here and nowhere else:
//   - the layout the engine accepts (<name>/<name>.map, MapCache::hasValidMapLeafLayout);
//   - that importing a pack never installs anything until the pack is activated;
//   - that a map shared by two active packs is installed once and removed only when both are off;
//   - that a map the manager did not install is never deleted;
//   - that the 200-map limit counts unique maps and refuses with the exact Arabic message;
//   - that only the ControlBar entries of an archive are selected, and unrelated INIs are not;
//   - that a ControlBar package installs nothing until activated, unpacks into the game folder,
//     removes exactly its own files, and never deletes a file the game shipped itself.

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

    // ------------------------------------------------------------------ map packs

    private File newPack(String name, String... mapNames) {
        File dir = new File(MapPackManager.packsDir(app()), name);
        assertTrue(dir.mkdirs() || dir.isDirectory());
        for (String map : mapNames) {
            File mapDir = new File(dir, map);
            assertTrue(mapDir.mkdirs() || mapDir.isDirectory());
            write(new File(mapDir, map + ".map"), mapBytes());
            write(new File(mapDir, map + ".tga"), new byte[16]);
        }
        return dir;
    }

    private MapPackManager.Pack pack(String name) {
        for (MapPackManager.Pack p : MapPackManager.listPacks(app())) {
            if (p.name.equals(name)) {
                return p;
            }
        }
        throw new AssertionError("pack not found: " + name);
    }

    private File installed(String map) {
        return new File(MapManager.mapsDir(app()), map);
    }

    @Test
    public void aPackIsStoredInMapsManagerAndItsMapsAreNotInstalledUntilActivated() throws IOException {
        newPack("Desert Maps", "MapA", "MapB");
        assertEquals(MapPackManager.PACKS_DIR,
            new File(SetupActivity.userDataDir(app()), MapPackManager.PACKS_DIR).getName());

        // Importing a pack never reaches the game: the manager shows the pack, not its maps.
        MapPackManager.sync(app());
        assertEquals("no map is installed before the pack is switched on",
            false, installed("MapA").exists());
        for (MapManager.Map map : MapManager.listMaps(app())) {
            assertFalse("a packed map is not listed as an installed map",
                map.name.equals("MapA") || map.name.equals("MapB"));
        }

        ModImportService.CancellationSignal signal = new ModImportService.CancellationSignal();
        MapPackManager.activate(app(), pack("Desert Maps"), signal);
        assertTrue(installed("MapA").isDirectory());
        assertTrue(installed("MapB").isDirectory());
        assertEquals(2, MapPackManager.activeMapCount(app()));
    }

    @Test
    public void importingAZipCreatesItsOwnPackAndKeepsTheMapLayout() throws IOException {
        File zip = cache("pack.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            entry(out, "Multiplayer Maps/Map_04/Map_04.map", mapBytes());
            entry(out, "Multiplayer Maps/Map_05/Map_05.map", mapBytes());
            entry(out, "Multiplayer Maps/Map_05/Map_05.tga", new byte[8]);
        }
        ModImportService.CancellationSignal signal = new ModImportService.CancellationSignal();
        MapPackManager.Plan plan = MapPackManager.prepareZip(app(), Uri.fromFile(zip),
            "Multiplayer Maps.zip", signal);
        assertEquals(2, plan.mapCount);
        MapPackManager.Pack imported = MapPackManager.commit(app(), plan);

        assertEquals("Multiplayer Maps", imported.name);
        assertEquals(2, imported.mapCount());
        assertTrue(new File(imported.dir, "Multiplayer Maps/Map_04/Map_04.map").isFile());
        assertTrue(new File(imported.dir, "Multiplayer Maps/Map_05/Map_05.map").isFile());
        assertTrue("the pack metadata is written",
            new File(imported.dir, MapPackManager.METADATA_FILE).isFile());
        assertTrue("the pack is stored under maps-manager",
            imported.dir.getParentFile().getName().equals(MapPackManager.PACKS_DIR));
    }

    @Test
    public void aSharedMapIsKeptUntilEveryPackThatProvidesItIsDeactivated() throws IOException {
        newPack("Pack One", "Shared", "OnlyOne");
        newPack("Pack Two", "Shared", "OnlyTwo");
        ModImportService.CancellationSignal signal = new ModImportService.CancellationSignal();

        MapPackManager.activate(app(), pack("Pack One"), signal);
        MapPackManager.activate(app(), pack("Pack Two"), signal);
        assertEquals("the shared map is counted once", 3, MapPackManager.activeMapCount(app()));

        MapPackManager.deactivate(app(), pack("Pack One"), signal);
        assertTrue("the shared map survives while another pack still needs it",
            installed("Shared").isDirectory());
        assertFalse("a map only that pack provided is removed", installed("OnlyOne").exists());
        assertTrue(installed("OnlyTwo").isDirectory());

        MapPackManager.deactivate(app(), pack("Pack Two"), signal);
        assertFalse("the shared map goes once its last provider is off",
            installed("Shared").exists());
        assertEquals(0, MapPackManager.activeMapCount(app()));
    }

    @Test
    public void aMapThePlayerInstalledByHandIsNeverRemoved() throws IOException {
        File hand = installed("HandMade");
        assertTrue(hand.mkdirs() || hand.isDirectory());
        write(new File(hand, "HandMade.map"), mapBytes());

        newPack("Temp", "TempMap");
        ModImportService.CancellationSignal signal = new ModImportService.CancellationSignal();
        MapPackManager.activate(app(), pack("Temp"), signal);
        MapPackManager.deactivate(app(), pack("Temp"), signal);

        assertTrue("a map the manager did not install is left alone", hand.isDirectory());
        assertFalse("the map the manager did install is removed", installed("TempMap").exists());
    }

    @Test
    public void theTwoHundredMapLimitIsCountedOnUniqueMaps() throws IOException {
        String[] many = new String[200];
        for (int i = 0; i < 200; i++) {
            many[i] = String.format(Locale.US, "Map%03d", i);
        }
        newPack("Big", many);
        newPack("Overflow", "Map000", "Extra");

        ModImportService.CancellationSignal signal = new ModImportService.CancellationSignal();
        MapPackManager.activate(app(), pack("Big"), signal);
        assertEquals("200 unique maps is the allowed maximum", 200,
            MapPackManager.activeMapCount(app()));
        assertEquals("a pack sharing maps does not simply add its own count", 201,
            MapPackManager.activeCountWith(app(), "Overflow"));
    }

    @Test
    public void activatingPastTheLimitIsRefusedWithTheExactArabicMessageAndNothingIsCopied()
            throws IOException {
        String[] many = new String[200];
        for (int i = 0; i < 200; i++) {
            many[i] = String.format(Locale.US, "Limit%03d", i);
        }
        newPack("Full", many);
        newPack("Over", "Limit000", "Extra");

        ModImportService.CancellationSignal signal = new ModImportService.CancellationSignal();
        MapPackManager.activate(app(), pack("Full"), signal);
        assertEquals(200, MapPackManager.activeMapCount(app()));

        ModImportService.ImportProblem problem = assertThrows(ModImportService.ImportProblem.class,
            () -> MapPackManager.activate(app(), pack("Over"), signal));
        assertEquals(R.string.mappack_limit_message, problem.messageRes);

        String message = app().getString(problem.messageRes);
        assertEquals("تم تخطي الحاجز 200 خريطة، يرجى إلغاء تفعيل الحزمة التي لا تنوي اللعب بها"
            + " وإعادة المحاولة.", message);

        assertFalse("a refused activation copies nothing", installed("Extra").exists());
        assertEquals("the refused pack is not left active", 200,
            MapPackManager.activeMapCount(app()));

        // Deactivating always works, and frees room for the pack that did not fit.
        MapPackManager.deactivate(app(), pack("Full"), signal);
        MapPackManager.activate(app(), pack("Over"), signal);
        assertTrue(installed("Extra").isDirectory());
        assertTrue(installed("Limit000").isDirectory());
        assertEquals("Over provides Limit000 and Extra", 2, MapPackManager.activeMapCount(app()));
    }

    @Test
    public void theActiveSetIsRebuiltAfterARestartAndLooseFilesAreNotTouched() throws IOException {
        newPack("Persist", "Kept");
        ModImportService.CancellationSignal signal = new ModImportService.CancellationSignal();
        MapPackManager.activate(app(), pack("Persist"), signal);
        assertTrue(installed("Kept").isDirectory());

        // Simulate the game's Maps folder losing a map between sessions.
        ModImportService.deleteRecursively(installed("Kept"));
        assertFalse(installed("Kept").exists());

        File unrelated = new File(MapManager.mapsDir(app()), "MapCache.ini");
        write(unrelated, "auto-generated".getBytes(StandardCharsets.US_ASCII));

        MapPackManager.sync(app());
        assertTrue("the saved active list restores the missing map", installed("Kept").isDirectory());
        assertTrue("an unrelated file in Maps is left alone", unrelated.isFile());
        assertEquals(1, MapPackManager.activeMapCount(app()));
    }

    @Test
    public void deletingAPackKeepsTheOtherPacksAndRemovesItsInstalledMaps() throws IOException {
        newPack("Keep", "KeepMap");
        newPack("Drop", "DropMap");
        ModImportService.CancellationSignal signal = new ModImportService.CancellationSignal();
        MapPackManager.activate(app(), pack("Drop"), signal);
        assertTrue(installed("DropMap").isDirectory());

        assertTrue(MapPackManager.delete(app(), pack("Drop")));
        MapPackManager.sync(app());
        assertFalse(installed("DropMap").exists());
        assertTrue("the other pack is untouched", pack("Keep").dir.isDirectory());
    }

    @Test
    public void exportingAPackAsZipKeepsItsFolderLayoutAndCanBeImportedAgain() throws IOException {
        newPack("Export Me", "Outer", "Inner");
        MapPackManager.Pack pack = pack("Export Me");
        assertEquals(2, pack.mapCount());

        File destination = cache("export.zip");
        ModImportService.CancellationSignal signal = new ModImportService.CancellationSignal();
        int count = MapPackManager.exportZip(app(), pack, Uri.fromFile(destination), signal);
        assertTrue("the pack's files are written", count >= 4);

        // The ZIP it writes is a map pack again: prepareZip reads back the same two maps.
        MapPackManager.Plan plan = MapPackManager.prepareZip(app(), Uri.fromFile(destination),
            "Export Me.zip", signal);
        assertEquals(2, plan.mapCount);
        MapPackManager.Pack again = MapPackManager.commit(app(), plan);
        assertEquals(2, again.mapCount());
        assertTrue(new File(again.dir, "Outer/Outer.map").isFile());
        assertTrue(new File(again.dir, "Inner/Inner.map").isFile());
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

        ControlBarBig.Detection detection = ControlBarBig.detect(big);
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
    }

    @Test
    public void anArchiveWithoutControlBarFilesIsRefusedWithItsOwnMessage() throws IOException {
        File big = cache("plain.big");
        writeBig(big, new String[] { "Data\\INI\\Object\\Foo.ini" },
            new byte[][] { "Object Foo\nEnd\n".getBytes(StandardCharsets.US_ASCII) });

        ModImportService.ImportProblem problem = assertThrows(ModImportService.ImportProblem.class,
            () -> ControlBarBig.detect(big));
        assertEquals(R.string.cbbig_no_controlbar, problem.messageRes);
    }

    @Test
    public void somethingThatIsNotAnArchiveIsRefused() throws IOException {
        File notBig = cache("not.big");
        write(notBig, "PK\u0003\u0004 this is a zip, not a BIG".getBytes(StandardCharsets.US_ASCII));

        ModImportService.ImportProblem problem = assertThrows(ModImportService.ImportProblem.class,
            () -> ControlBarBig.detect(notBig));
        assertEquals(R.string.cbbig_unsupported, problem.messageRes);
    }

    // ------------------------------------------------------------------ control bar packs

    /** Points the launcher at a scratch game folder, the way Home does. */
    private File gameFolder(String name) {
        File game = new File(app().getFilesDir(), name + "-" + System.nanoTime());
        assertTrue(game.mkdirs() || game.isDirectory());
        app().getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(SetupActivity.PREF_GAME_PATH, game.getAbsolutePath()).commit();
        return game;
    }

    /** A ControlBar package on disk: a real .big archive with a layout, a scheme and a texture. */
    private File newControlBarPack(String name, String... extraEntries) throws IOException {
        return writeControlBarPack(name, "Test.ini", extraEntries);
    }

    /** As {@link #newControlBarPack}, but the scheme file can be named so two packs differ. */
    private File writeControlBarPack(String name, String schemeFile, String... extraEntries)
            throws IOException {
        File dir = new File(ControlBarPackManager.packsDir(app()), name);
        assertTrue(dir.mkdirs() || dir.isDirectory());
        List<String> paths = new ArrayList<>();
        List<byte[]> data = new ArrayList<>();
        paths.add("Window\\ControlBar.wnd");
        data.add("WINDOW\n  ControlBarParent\nEND\n".getBytes(StandardCharsets.US_ASCII));
        paths.add("Data\\INI\\ControlBarScheme\\" + schemeFile);
        data.add("ControlBarScheme Test\n  Side = America\n  RightHUDImage = MyImage\nEnd\n"
            .getBytes(StandardCharsets.US_ASCII));
        paths.add("Data\\INI\\MappedImages\\Test.ini");
        data.add("MappedImage MyImage\n  Texture = MyIcon.tga\nEnd\n"
            .getBytes(StandardCharsets.US_ASCII));
        paths.add("Art\\Textures\\MyIcon.tga");
        data.add(new byte[64]);
        for (int i = 0; i + 1 < extraEntries.length; i += 2) {
            paths.add(extraEntries[i]);
            data.add(extraEntries[i + 1].getBytes(StandardCharsets.US_ASCII));
        }
        writeBig(new File(dir, name + ".big"), paths.toArray(new String[0]),
            data.toArray(new byte[0][]));
        return dir;
    }

    private ControlBarPackManager.Pack cbPack(String name) {
        for (ControlBarPackManager.Pack pack : ControlBarPackManager.listPacks(app())) {
            if (pack.name.equals(name)) {
                return pack;
            }
        }
        throw new AssertionError("control bar pack not found: " + name);
    }

    @Test
    public void aControlBarPackageInstallsNothingUntilActivated() throws IOException {
        File game = gameFolder("cb-game");
        newControlBarPack("Pro Bar");
        ControlBarPackManager.sync(app());
        assertFalse("nothing is unpacked at import time",
            new File(game, "Window/ControlBar.wnd").exists());
        assertFalse("and nothing is written into the game folder by sync alone",
            new File(game, "Data/INI/ControlBarScheme/Test.ini").exists());

        ModImportService.CancellationSignal signal = new ModImportService.CancellationSignal();
        ControlBarPackManager.Result result = ControlBarPackManager.activate(app(), cbPack("Pro Bar"), signal);
        assertEquals(4, result.fileCount);
        assertTrue(new File(game, "Window/ControlBar.wnd").isFile());
        assertTrue(new File(game, "Data/INI/ControlBarScheme/Test.ini").isFile());
        assertTrue(new File(game, "Data/INI/MappedImages/Test.ini").isFile());
        assertTrue(new File(game, "Art/Textures/MyIcon.tga").isFile());
        assertEquals("Pro Bar", ControlBarPackManager.activeName(app()));
    }

    @Test
    public void activatingAnotherPackageRemovesTheFirstOnesFiles() throws IOException {
        File game = gameFolder("cb-switch");
        newControlBarPack("First");
        // The second package ships its own scheme file, so the first's scheme is uniquely its own.
        writeControlBarPack("Second", "Second.ini");

        ModImportService.CancellationSignal signal = new ModImportService.CancellationSignal();
        ControlBarPackManager.activate(app(), cbPack("First"), signal);
        assertTrue(new File(game, "Data/INI/ControlBarScheme/Test.ini").isFile());

        ControlBarPackManager.activate(app(), cbPack("Second"), signal);
        assertEquals("Second", ControlBarPackManager.activeName(app()));
        assertTrue("the new package's own scheme is in place",
            new File(game, "Data/INI/ControlBarScheme/Second.ini").isFile());
        assertFalse("only one control bar is installed at a time",
            new File(game, "Data/INI/ControlBarScheme/Test.ini").exists());
    }

    @Test
    public void deactivatingRemovesOnlyTheFilesTheManagerInstalled() throws IOException {
        File game = gameFolder("cb-deactivate");
        // A file the game itself ships, at a path the package also uses.
        File shipped = new File(game, "Art/Textures/MyIcon.tga");
        assertTrue(shipped.getParentFile().mkdirs() || shipped.getParentFile().isDirectory());
        write(shipped, "the game's own texture".getBytes(StandardCharsets.US_ASCII));
        File unrelated = new File(game, "Art/Textures/GameOwn.tga");
        write(unrelated, "untouched".getBytes(StandardCharsets.US_ASCII));

        newControlBarPack("Temp");
        ModImportService.CancellationSignal signal = new ModImportService.CancellationSignal();
        ControlBarPackManager.activate(app(), cbPack("Temp"), signal);
        assertTrue("the package's file replaced the shipped one",
            new String(readAll(shipped), StandardCharsets.ISO_8859_1).indexOf("the game's own") < 0);

        ControlBarPackManager.deactivate(app(), cbPack("Temp"), signal);
        assertNull("no package is active afterwards", ControlBarPackManager.activeName(app()));
        assertTrue("a file the game shipped is restored, not lost", shipped.isFile());
        assertEquals("the game's own texture",
            new String(readAll(shipped), StandardCharsets.ISO_8859_1));
        assertTrue("an unrelated game file is never touched", unrelated.isFile());
        assertFalse("the package's own scheme file is removed",
            new File(game, "Window/ControlBar.wnd").exists());
    }

    @Test
    public void aPackThatOnlyHoldsControlBarFilesIsImportedFromAZip() throws IOException {
        File zip = cache("controlbar-pack.zip");
        File big = cache("packed.big");
        writeBig(big, new String[] {
            "Window\\ControlBar.wnd",
            "Data\\INI\\ControlBarScheme\\Test.ini",
        }, new byte[][] {
            "WINDOW\nEND\n".getBytes(StandardCharsets.US_ASCII),
            "ControlBarScheme Test\nEnd\n".getBytes(StandardCharsets.US_ASCII),
        });
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            entry(out, "Control Bar Pro/ControlBarPro.big", readAll(big));
        }
        ModImportService.CancellationSignal signal = new ModImportService.CancellationSignal();
        ControlBarPackManager.Plan plan =
            ControlBarPackManager.prepareZip(app(), Uri.fromFile(zip), "Control Bar Pro.zip", signal);
        ControlBarPackManager.Pack pack = ControlBarPackManager.commit(app(), plan);
        assertEquals("Control Bar Pro", pack.name);
        assertEquals(1, pack.archiveCount());
    }

    @Test
    public void aZipWithNoBigArchiveIsRefusedAndLeavesNothingBehind() throws IOException {
        File zip = cache("not-a-pack.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            entry(out, "readme.txt", "nothing here".getBytes(StandardCharsets.US_ASCII));
        }
        ModImportService.CancellationSignal signal = new ModImportService.CancellationSignal();
        ModImportService.ImportProblem problem = assertThrows(ModImportService.ImportProblem.class,
            () -> ControlBarPackManager.prepareZip(app(), Uri.fromFile(zip), "not-a-pack.zip", signal));
        assertEquals(R.string.cbpack_no_big, problem.messageRes);
        assertFalse("the failed staging folder is cleaned up",
            new File(ControlBarPackManager.packsDir(app()), ModImportService.INSTALLING_DIR)
                .listFiles() != null
                && new File(ControlBarPackManager.packsDir(app()), ModImportService.INSTALLING_DIR)
                    .listFiles().length > 0);
    }

    @Test
    public void aBigArchiveWithNoControlBarIsRefusedAsAPackage() throws IOException {
        File zip = cache("other-mod.zip");
        File big = cache("other.big");
        writeBig(big, new String[] { "Data\\INI\\Object\\Foo.ini" },
            new byte[][] { "Object Foo\nEnd\n".getBytes(StandardCharsets.US_ASCII) });
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            entry(out, "Other.big", readAll(big));
        }
        ModImportService.CancellationSignal signal = new ModImportService.CancellationSignal();
        ModImportService.ImportProblem problem = assertThrows(ModImportService.ImportProblem.class,
            () -> ControlBarPackManager.prepareZip(app(), Uri.fromFile(zip), "other-mod.zip", signal));
        assertEquals(R.string.cbpack_no_controlbar, problem.messageRes);
    }

    @Test
    public void syncReinstallsTheActivePackageAfterItsFilesAreLost() throws IOException {
        File game = gameFolder("cb-sync");
        newControlBarPack("Keep");
        ModImportService.CancellationSignal signal = new ModImportService.CancellationSignal();
        ControlBarPackManager.activate(app(), cbPack("Keep"), signal);
        assertTrue(new File(game, "Window/ControlBar.wnd").isFile());

        // The game folder loses the file between sessions.
        ModImportService.deleteRecursively(new File(game, "Window"));
        assertFalse(new File(game, "Window/ControlBar.wnd").exists());

        ControlBarPackManager.sync(app());
        assertTrue("the active package is re-unpacked",
            new File(game, "Window/ControlBar.wnd").isFile());
    }

    @Test
    public void aLanguageFolderInTheArchiveIsRewrittenToTheLanguageTheGameReads() throws IOException {
        File game = gameFolder("cb-language");
        write(new File(game, "EnglishZH.big"), new byte[8]);   // the engine auto-detects English
        File dir = new File(ControlBarPackManager.packsDir(app()), "LangPack");
        assertTrue(dir.mkdirs() || dir.isDirectory());
        writeBig(new File(dir, "LangPack.big"), new String[] {
            "Window\\ControlBar.wnd",
            "Data\\German\\Art\\Textures\\MyIcon.tga",
        }, new byte[][] {
            "WINDOW\nEND\n".getBytes(StandardCharsets.US_ASCII),
            new byte[64],
        });

        assertEquals("english", ControlBarPackManager.gameLanguage(app()));
        ModImportService.CancellationSignal signal = new ModImportService.CancellationSignal();
        ControlBarPackManager.activate(app(), cbPack("LangPack"), signal);
        // The engine builds "Data/<GetRegistryLanguage()>/Art/Textures/", and the token is the
        // lowercase language name, so that is the folder the file must land in.
        assertTrue("the texture lands in the folder the game reads, not the archive's own",
            new File(game, "Data/english/Art/Textures/MyIcon.tga").isFile());
        assertFalse(new File(game, "Data/German/Art/Textures/MyIcon.tga").exists());
    }

    @Test
    public void deletingAnActivePackageSwitchesItOffAndRemovesItsFiles() throws IOException {
        File game = gameFolder("cb-delete");
        newControlBarPack("Doomed");
        ModImportService.CancellationSignal signal = new ModImportService.CancellationSignal();
        ControlBarPackManager.activate(app(), cbPack("Doomed"), signal);
        assertTrue(new File(game, "Window/ControlBar.wnd").isFile());

        assertTrue(ControlBarPackManager.delete(app(), cbPack("Doomed")));
        ControlBarPackManager.sync(app());
        assertNull(ControlBarPackManager.activeName(app()));
        assertFalse(new File(game, "Window/ControlBar.wnd").exists());
    }

    // ------------------------------------------------------------------ helpers

    private static void write(File file, byte[] bytes) {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] readAll(File file) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
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
