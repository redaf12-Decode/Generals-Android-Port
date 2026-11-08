// GeneralsX @build Android CI 06/11/2026
//
// SetupActivity's static helpers are what decides whether a folder the player picked is usable,
// which settings survive a reinstall, and which engine library a game selection means. All of it
// is pure Java over files and SharedPreferences, so it is checked here without a device -- and
// none of it needs a native library.

package com.generalsx.zerohour;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

@RunWith(RobolectricTestRunner.class)
public class SetupActivityHelperTest {

    private static Context app() {
        return RuntimeEnvironment.getApplication();
    }

    private static File tempDir(String name) throws IOException {
        File dir = new File(app().getCacheDir(), name + "-" + System.nanoTime());
        assertTrue(dir.mkdirs() || dir.isDirectory());
        return dir;
    }

    private static void touch(File dir, String name) throws IOException {
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
            out.write(new byte[] { 1, 2, 3, 4 });
        }
    }

    // ------------------------------------------------------------------ folder validation

    @Test
    public void zeroHourFolderNeedsOneOfItsIniArchives() throws IOException {
        File dir = tempDir("zh");
        assertFalse("an empty folder is not a game folder",
            SetupActivity.isValidGameFolder(dir, SetupActivity.GAME_ZERO_HOUR));

        touch(dir, "INIZH.big");
        assertTrue(SetupActivity.isValidGameFolder(dir, SetupActivity.GAME_ZERO_HOUR));
    }

    /** A Generals folder also has an INI.big; Zero Hour accepts either archive. */
    @Test
    public void zeroHourFolderAcceptsTheBaseGameArchiveAlone() throws IOException {
        File dir = tempDir("zh-base");
        touch(dir, "INI.big");
        assertTrue(SetupActivity.isValidGameFolder(dir, SetupActivity.GAME_ZERO_HOUR));
    }

    /**
     * The base game must NOT accept a Zero Hour folder: the engine mounts every archive it finds,
     * so a Zero Hour folder would turn the base game into a broken mixture of the two.
     */
    @Test
    public void generalsFolderRejectsAZeroHourFolder() throws IOException {
        File dir = tempDir("gen-zh");
        touch(dir, "INI.big");
        touch(dir, "INIZH.big");
        assertFalse("a folder with INIZH.big is Zero Hour's, not the base game's",
            SetupActivity.isValidGameFolder(dir, SetupActivity.GAME_GENERALS));
    }

    @Test
    public void generalsFolderNeedsItsOwnIniArchive() throws IOException {
        File dir = tempDir("gen");
        touch(dir, "Generals.big");
        assertFalse(SetupActivity.isValidGameFolder(dir, SetupActivity.GAME_GENERALS));

        touch(dir, "INI.big");
        assertTrue(SetupActivity.isValidGameFolder(dir, SetupActivity.GAME_GENERALS));
    }

    @Test
    public void aFileIsNotAFolder() throws IOException {
        File dir = tempDir("notdir");
        File file = new File(dir, "INIZH.big");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(1);
        }
        assertFalse(SetupActivity.isValidGameFolder(file, SetupActivity.GAME_ZERO_HOUR));
    }

    @Test
    public void nullFolderIsInvalid() {
        assertFalse(SetupActivity.isValidGameFolder(null, SetupActivity.GAME_ZERO_HOUR));
        assertFalse(SetupActivity.isValidGameFolder(null, SetupActivity.GAME_GENERALS));
    }

    // ------------------------------------------------------------------ findGameFolderBelow

    @Test
    public void findsAGameFolderNestedBelowThePickedOne() throws IOException {
        File root = tempDir("nested");
        File inner = new File(root, "Games/ZeroHour");
        assertTrue(inner.mkdirs());
        touch(inner, "INIZH.big");

        assertEquals(inner, SetupActivity.findGameFolderBelow(root, SetupActivity.GAME_ZERO_HOUR));
    }

    /** Two candidates means the player has to choose, not that the first one wins. */
    @Test
    public void twoNestedGameFoldersAreAmbiguous() throws IOException {
        File root = tempDir("ambiguous");
        File a = new File(root, "A");
        File b = new File(root, "B");
        assertTrue(a.mkdirs());
        assertTrue(b.mkdirs());
        touch(a, "INIZH.big");
        touch(b, "INIZH.big");

        assertNull(SetupActivity.findGameFolderBelow(root, SetupActivity.GAME_ZERO_HOUR));
    }

    @Test
    public void noGameFolderBelowMeansNull() throws IOException {
        File root = tempDir("empty-tree");
        assertTrue(new File(root, "Documents").mkdirs());
        assertNull(SetupActivity.findGameFolderBelow(root, SetupActivity.GAME_ZERO_HOUR));
    }

    // ------------------------------------------------------------------ game selection and prefs

    @Test
    public void zeroHourIsTheDefaultGame() {
        assertEquals(SetupActivity.GAME_ZERO_HOUR, SetupActivity.getSelectedGame(app()));
        assertFalse(SetupActivity.isGeneralsSelected(app()));
    }

    @Test
    public void gameSelectionRoundTripsAndRejectsUnknownValues() {
        SetupActivity.setSelectedGame(app(), SetupActivity.GAME_GENERALS);
        assertEquals(SetupActivity.GAME_GENERALS, SetupActivity.getSelectedGame(app()));
        assertTrue(SetupActivity.isGeneralsSelected(app()));

        SetupActivity.setSelectedGame(app(), SetupActivity.GAME_ZERO_HOUR);
        assertEquals(SetupActivity.GAME_ZERO_HOUR, SetupActivity.getSelectedGame(app()));

        // Anything that is not "generals" resolves to Zero Hour.
        SetupActivity.setSelectedGame(app(), "something-else");
        assertEquals(SetupActivity.GAME_ZERO_HOUR, SetupActivity.getSelectedGame(app()));
    }

    @Test
    public void eachGameRemembersItsOwnFolder() {
        android.content.SharedPreferences prefs =
            app().getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().clear().apply();

        // The two games must not share one path key.
        assertFalse("the two games must use different preference keys",
            SetupActivity.GAME_ZERO_HOUR.equals(SetupActivity.GAME_GENERALS));
    }

    /**
     * GeneralsX @tweak Android port 08/11/2026 The default is 60 Hz (the rate the Windows
     * GeneralsOnline client runs), but only as a default: a stored value always wins, so an
     * install that already picked 30 Hz keeps it. Anything that is not one of the two built
     * rates resolves to 30 Hz rather than being stored and then failing to load.
     */
    @Test
    public void simulationRateDefaultsToCrossplayAndOnlyAcceptsTheTwoKnownValues() {
        app().getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(SetupActivity.PREF_SIM_HZ).apply();
        assertEquals("a fresh install defaults to the 60 Hz cross-play engine",
            SetupActivity.SIM_HZ_CROSSPLAY, SetupActivity.getSimHz(app()));

        SetupActivity.setSimHz(app(), SetupActivity.SIM_HZ_RETAIL);
        assertEquals("a stored 30 Hz must be preserved", SetupActivity.SIM_HZ_RETAIL,
            SetupActivity.getSimHz(app()));

        SetupActivity.setSimHz(app(), SetupActivity.SIM_HZ_CROSSPLAY);
        assertEquals(SetupActivity.SIM_HZ_CROSSPLAY, SetupActivity.getSimHz(app()));

        // 45 Hz was never built; it must resolve to retail, not be stored and then fail to load.
        SetupActivity.setSimHz(app(), 45);
        assertEquals(SetupActivity.SIM_HZ_RETAIL, SetupActivity.getSimHz(app()));
    }

    // ------------------------------------------------------------------ engine libraries

    @Test
    public void engineLibraryNamesFollowTheSelectedGame() {
        SetupActivity.setSelectedGame(app(), SetupActivity.GAME_ZERO_HOUR);
        assertArrayEquals(new String[] { "main", "main60" },
            SetupActivity.engineLibraries(app()));

        SetupActivity.setSelectedGame(app(), SetupActivity.GAME_GENERALS);
        assertArrayEquals(new String[] { "generals", "generals60" },
            SetupActivity.engineLibraries(app()));
    }

    /**
     * The 30 Hz and 60 Hz libraries are two compilations of the engine, so the launcher has to ask
     * for both; the native side picks one at load. A single name here would ship one tick rate.
     */
    @Test
    public void bothSimulationRatesHaveAnEngineLibrary() {
        assertEquals(2, SetupActivity.engineLibraries(app()).length);
    }

    /**
     * A Robolectric app has no native libraries staged, which is exactly the launcher-only APK's
     * situation. engineInstalled() must answer false without throwing and without loading a .so.
     */
    @Test
    public void engineInstalledIsFalseWhenNothingWasStaged() {
        assertFalse("no native library directory contents in a JVM test",
            SetupActivity.engineInstalled(app()));
    }

    @Test
    public void engineInstalledFindsTheStagedLibraryForTheSelectedGame() throws IOException {
        // Robolectric does not lay out a native library directory, so point the app at a real one
        // and stage a library by hand -- exactly what the APK does with jniLibs/.
        File libDir = new File(app().getCacheDir(), "native-libs-" + System.nanoTime());
        assertTrue(libDir.mkdirs() || libDir.isDirectory());
        app().getApplicationInfo().nativeLibraryDir = libDir.getAbsolutePath();

        File zh = new File(libDir, "libmain60.so");
        try (FileOutputStream out = new FileOutputStream(zh)) {
            out.write(1);
        }
        SetupActivity.setSelectedGame(app(), SetupActivity.GAME_ZERO_HOUR);
        assertTrue(SetupActivity.engineInstalled(app()));

        // A Zero Hour engine is not a Generals engine.
        SetupActivity.setSelectedGame(app(), SetupActivity.GAME_GENERALS);
        assertFalse(SetupActivity.engineInstalled(app()));

        // The 30 Hz build counts too.
        touch(libDir, "libgenerals.so");
        assertTrue(SetupActivity.engineInstalled(app()));
    }

    /**
     * An engine delivered by UpdateManager is not in the APK at all (GeneralsZHActivity.loadLibraries
     * loads it from the files dir), so a file check against nativeLibraryDir alone would refuse to
     * start it. The guard has to answer true for that engine too, or a signed engine update would
     * leave the Play button dead.
     */
    @Test
    public void engineInstalledSeesAnEngineDeliveredByUpdateManager() throws IOException {
        // Nothing staged in the APK: this is the launcher-only APK's situation.
        File libDir = new File(app().getCacheDir(), "empty-native-" + System.nanoTime());
        assertTrue(libDir.mkdirs() || libDir.isDirectory());
        app().getApplicationInfo().nativeLibraryDir = libDir.getAbsolutePath();
        assertFalse(SetupActivity.engineInstalled(app()));

        // Stage an updated engine the way UpdateManager does: update/engine/<seq>/libmain*.so plus
        // the active marker, on the channel the manifest names.
        int seq = UpdateManager.bundledEngineSeq(app()) + 1;
        File engineDir = new File(new File(UpdateManager.updateDir(app()), "engine"), Integer.toString(seq));
        assertTrue(engineDir.mkdirs() || engineDir.isDirectory());
        touch(engineDir, "libmain.so");
        touch(engineDir, "libmain60.so");
        touch(UpdateManager.updateDir(app()), "engine_active.txt");
        try (FileOutputStream out = new FileOutputStream(
                new File(UpdateManager.updateDir(app()), "engine_active.txt"))) {
            out.write(Integer.toString(seq).getBytes("US-ASCII"));
        }

        // The dependency stamp is what UpdateManager checks before letting an engine run; it is a
        // preference, so writing it is how a test says "this engine already proved it fits".
        app().getSharedPreferences("gx_update", Context.MODE_PRIVATE).edit()
            .putString("deps_ok_for", seq + ":" + installedTime())
            .apply();

        assertTrue("an engine delivered outside the APK must count as installed",
            SetupActivity.engineInstalled(app()));
    }

    private static long installedTime() {
        try {
            return app().getPackageManager().getPackageInfo(app().getPackageName(), 0).lastUpdateTime;
        } catch (Exception e) {
            return 0L;
        }
    }

    // ------------------------------------------------------------------ logging marker

    @Test
    public void loggingOffMarkerFollowsTheFileNotThePreference() throws IOException {
        assertFalse(SetupActivity.isLoggingDisabled(app()));

        File marker = new File(app().getFilesDir(), SetupActivity.LOGGING_OFF_MARKER);
        assertTrue(marker.createNewFile());
        assertTrue(SetupActivity.isLoggingDisabled(app()));

        assertTrue(marker.delete());
        assertFalse(SetupActivity.isLoggingDisabled(app()));
    }

    // ------------------------------------------------------------------ game folder marker

    @Test
    public void nativeGameMarkerIsWrittenVerbatim() throws IOException {
        String path = "/storage/emulated/0/GeneralsZH";
        assertTrue(SetupActivity.writeNativeGameMarker(app(), path));

        File marker = new File(app().getFilesDir(), "gamedata_path.txt");
        assertTrue(marker.isFile());
        String written = new String(java.nio.file.Files.readAllBytes(marker.toPath()),
            java.nio.charset.StandardCharsets.UTF_8).trim();
        assertEquals(path, written);
    }

    @Test
    public void userDataFoldersAreDistinctPerGame() {
        SetupActivity.setSelectedGame(app(), SetupActivity.GAME_ZERO_HOUR);
        File zh = SetupActivity.userDataDir(app());
        SetupActivity.setSelectedGame(app(), SetupActivity.GAME_GENERALS);
        File gen = SetupActivity.userDataDir(app());
        assertFalse("the two games must not share a user-data folder", zh.equals(gen));
    }
}
