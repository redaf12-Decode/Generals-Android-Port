// GeneralsX @build Android port 07/11/2026
//
// The optional scripts-folder backup (ModScriptsBackup) is the one place the launcher renames a
// directory inside the player's own game folder, so every one of the required scenarios is driven
// here against a real game folder and real files: the rename on activation, the restore on
// deactivation, a missing scripts folder, an existing scriptsbackup, both of them at once, a
// rename that cannot be done, a restart with the folder still aside, and several mods on one game.
// No mocks: ModManager, SetupActivity and ModScriptsBackup are the real classes, and the folder
// the player picked is a real directory under the Robolectric cache.

package com.generalsx.zerohour;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RunWith(RobolectricTestRunner.class)
public class ModScriptsBackupTest {

    private static final String MOD = "Test Mod";

    private Context app;
    private File game;
    private File data;

    private static Context app() {
        return RuntimeEnvironment.getApplication();
    }

    @Before
    public void setUp() throws IOException {
        app = app();
        game = new File(app.getCacheDir(), "scripts-game-" + System.nanoTime());
        assertTrue(game.mkdirs() || game.isDirectory());
        data = new File(game, "data");
        assertTrue(data.mkdirs() || data.isDirectory());
        prefs().edit().clear()
            .putString(SetupActivity.PREF_GAME_PATH, game.getAbsolutePath())
            .putString(SetupActivity.PREF_GAME, SetupActivity.GAME_ZERO_HOUR)
            .commit();
        ModScriptsBackup.setEnabled(app, false);
    }

    private SharedPreferences prefs() {
        return app.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE);
    }

    private File scripts() {
        return new File(data, ModScriptsBackup.SCRIPTS_DIR);
    }

    private File backup() {
        return new File(data, ModScriptsBackup.BACKUP_DIR);
    }

    /** A game folder with a scripts directory holding one recognizable file. */
    private void makeScripts() throws IOException {
        assertTrue(scripts().mkdirs() || scripts().isDirectory());
        write(new File(scripts(), "SkirmishScripts.scb"), "AI-SCRIPTS".getBytes(StandardCharsets.US_ASCII));
    }

    /** The Mods screen's saved list: which mods are switched on, top first. */
    private void setModsOn(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("1|").append(MOD).append(i);
        }
        prefs().edit().putString("mods_order", sb.toString()).commit();
    }

    private void setModsAllOff(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("0|").append(MOD).append(i);
        }
        prefs().edit().putString("mods_order", sb.toString()).commit();
    }

    private static void write(File file, byte[] bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
    }

    private static String read(File file) throws IOException {
        byte[] bytes = new byte[(int) file.length()];
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            int n = 0, got;
            while (n < bytes.length && (got = in.read(bytes, n, bytes.length - n)) > 0) {
                n += got;
            }
            return new String(bytes, 0, n, StandardCharsets.US_ASCII);
        }
    }

    // ------------------------------------------------------------- the option

    /** Scenario 1/4: the preference is kept, and survives a fresh read of the store. */
    @Test
    public void theOptionIsRemembered() {
        assertFalse("off by default", ModScriptsBackup.isEnabled(app));
        ModScriptsBackup.setEnabled(app, true);
        assertTrue(ModScriptsBackup.isEnabled(app));
        ModScriptsBackup.setEnabled(app, false);
        assertFalse(ModScriptsBackup.isEnabled(app));
    }

    // --------------------------------------------------------- the two moves

    /** Scenario 2: switching a mod on with the option on moves the folder aside. */
    @Test
    public void activatingAModMovesTheScriptsFolderAside() throws IOException {
        makeScripts();
        ModScriptsBackup.setEnabled(app, true);
        setModsOn(1);

        ModScriptsBackup.Result result = ModScriptsBackup.apply(app);

        assertTrue("the rename is reported", result.renamed);
        assertFalse(scripts().exists());
        assertTrue("data/scriptsbackup exists", backup().isDirectory());
        assertEquals("AI-SCRIPTS", read(new File(backup(), "SkirmishScripts.scb")));
        assertEquals(ModScriptsBackup.SCRIPTS_DIR, ModScriptsBackup.recordedName(app));
        assertEquals(game.getAbsolutePath(), ModScriptsBackup.recordedGamePath(app));
    }

    /** Scenario 3: switching the last mod off puts the folder back, with its contents. */
    @Test
    public void deactivatingTheLastModRestoresTheScriptsFolder() throws IOException {
        makeScripts();
        ModScriptsBackup.setEnabled(app, true);
        setModsOn(1);
        ModScriptsBackup.apply(app);
        assertTrue(backup().isDirectory());

        setModsAllOff(1);
        ModScriptsBackup.Result result = ModScriptsBackup.sync(app);

        assertTrue("the restore is reported", result.restored);
        assertTrue(scripts().isDirectory());
        assertFalse(backup().exists());
        assertEquals("the original file is intact", "AI-SCRIPTS",
            read(new File(scripts(), "SkirmishScripts.scb")));
        assertNull("no marker is left behind", ModScriptsBackup.recordedName(app));
    }

    /** Scenario 4: with the option off, activation changes nothing. */
    @Test
    public void withTheOptionOffNothingIsRenamed() throws IOException {
        makeScripts();
        ModScriptsBackup.setEnabled(app, false);
        setModsOn(1);

        ModScriptsBackup.Result result = ModScriptsBackup.sync(app);

        assertFalse(result.renamed);
        assertFalse(result.restored);
        assertTrue(scripts().isDirectory());
        assertFalse(backup().exists());
    }

    /** Scenario 5: no scripts folder at all is a no-op, not an error. */
    @Test
    public void aMissingScriptsFolderIsLeftAlone() {
        ModScriptsBackup.setEnabled(app, true);
        setModsOn(1);

        ModScriptsBackup.Result result = ModScriptsBackup.apply(app);

        assertFalse(result.renamed);
        assertFalse(result.failed);
        assertFalse(result.conflict);
        assertFalse(scripts().exists());
        assertFalse(backup().exists());
    }

    /** Scenario 6: an existing backup is remembered, not renamed over. */
    @Test
    public void anExistingBackupIsNotRenamedAgain() throws IOException {
        assertTrue(backup().mkdirs() || backup().isDirectory());
        write(new File(backup(), "old.scb"), "OLD".getBytes(StandardCharsets.US_ASCII));
        ModScriptsBackup.setEnabled(app, true);
        setModsOn(1);

        ModScriptsBackup.Result result = ModScriptsBackup.apply(app);

        assertFalse("nothing was renamed", result.renamed);
        assertFalse(result.failed);
        assertEquals("OLD", read(new File(backup(), "old.scb")));
        assertFalse(scripts().exists());
        assertEquals("the marker points at the folder so it can be restored",
            ModScriptsBackup.SCRIPTS_DIR, ModScriptsBackup.recordedName(app));
    }

    /** Scenario 6: both folders at once is a conflict, and neither is touched. */
    @Test
    public void bothFoldersAtOnceIsAConflict() throws IOException {
        makeScripts();
        assertTrue(backup().mkdirs() || backup().isDirectory());
        write(new File(backup(), "old.scb"), "OLD".getBytes(StandardCharsets.US_ASCII));
        ModScriptsBackup.setEnabled(app, true);
        setModsOn(1);

        ModScriptsBackup.Result result = ModScriptsBackup.apply(app);

        assertTrue("the conflict is reported", result.conflict);
        assertFalse(result.renamed);
        assertTrue("the scripts folder is untouched", scripts().isDirectory());
        assertEquals("AI-SCRIPTS", read(new File(scripts(), "SkirmishScripts.scb")));
        assertEquals("the backup is untouched", "OLD", read(new File(backup(), "old.scb")));
    }

    /** Scenario 7: a rename that cannot be done stops the activation and changes nothing. */
    @Test
    public void aFailedRenameIsReportedAndNothingIsChanged() throws IOException {
        makeScripts();
        // A plain file where the backup directory would go: the rename cannot succeed.
        write(backup(), "in the way".getBytes(StandardCharsets.US_ASCII));
        ModScriptsBackup.setEnabled(app, true);
        setModsOn(1);

        ModScriptsBackup.Result result = ModScriptsBackup.apply(app);

        assertTrue("the failure is reported", result.failed);
        assertTrue(result.mustStopActivation());
        assertTrue("the scripts folder is still there", scripts().isDirectory());
        assertEquals("AI-SCRIPTS", read(new File(scripts(), "SkirmishScripts.scb")));
        assertNull("no marker is written for a move that did not happen",
            ModScriptsBackup.recordedName(app));
    }

    /** Scenario 8: a restart with the folder aside keeps it aside, then restores it when off. */
    @Test
    public void aRestartWithTheFolderAsideRecovers() throws IOException {
        makeScripts();
        ModScriptsBackup.setEnabled(app, true);
        setModsOn(1);
        ModScriptsBackup.apply(app);

        // "Restart": the marker survives, the folder is still aside, the mods are still on.
        ModScriptsBackup.Result again = ModScriptsBackup.sync(app);
        assertFalse("already backed up: no second rename", again.renamed);
        assertTrue(backup().isDirectory());
        assertFalse(scripts().exists());

        // The player turns the option off before the process is torn down; the next launch heals.
        ModScriptsBackup.setEnabled(app, false);
        ModScriptsBackup.recover(app);
        assertTrue(scripts().isDirectory());
        assertFalse(backup().exists());
        assertEquals("AI-SCRIPTS", read(new File(scripts(), "SkirmishScripts.scb")));
    }

    /** Scenario 9: the folder stays aside until the last of several mods is switched off. */
    @Test
    public void theFolderIsRestoredOnlyWhenTheLastModIsOff() throws IOException {
        makeScripts();
        ModScriptsBackup.setEnabled(app, true);

        setModsOn(1);
        assertTrue(ModScriptsBackup.apply(app).renamed);

        // A second mod switched on: still backed up, and not renamed a second time.
        setModsOn(2);
        assertFalse("already aside", ModScriptsBackup.apply(app).renamed);
        assertTrue(backup().isDirectory());

        // One of the two switched off: still one on, so the folder stays aside.
        prefs().edit().putString("mods_order", "1|" + MOD + "0\n0|" + MOD + "1").commit();
        assertFalse(ModScriptsBackup.sync(app).restored);
        assertTrue("still aside while a mod is on", backup().isDirectory());

        // The last one switched off: back it comes.
        setModsAllOff(2);
        assertTrue(ModScriptsBackup.sync(app).restored);
        assertTrue(scripts().isDirectory());
        assertFalse(backup().exists());
    }

    /** Scenario 10: the round trip loses nothing, whatever the scripts folder holds. */
    @Test
    public void theRoundTripLosesNoFile() throws IOException {
        makeScripts();
        File nested = new File(scripts(), "campaign");
        assertTrue(nested.mkdirs() || nested.isDirectory());
        write(new File(nested, "Mission01.scb"), "MISSION".getBytes(StandardCharsets.US_ASCII));
        write(new File(scripts(), "empty.scb"), new byte[0]);

        ModScriptsBackup.setEnabled(app, true);
        setModsOn(1);
        ModScriptsBackup.apply(app);
        setModsAllOff(1);
        ModScriptsBackup.restore(app);

        assertEquals("AI-SCRIPTS", read(new File(scripts(), "SkirmishScripts.scb")));
        assertEquals("MISSION", read(new File(new File(scripts(), "campaign"), "Mission01.scb")));
        assertEquals("an empty file survives too", 0, new File(scripts(), "empty.scb").length());
    }

    /** A folder left aside by the other game is repaired when that game no longer wants it. */
    @Test
    public void aFolderLeftAsideByTheOtherGameIsRepaired() throws IOException {
        // The other game (Generals) owns its own folder, recorded in its own preference.
        File other = new File(app.getCacheDir(), "scripts-generals-" + System.nanoTime());
        assertTrue(other.mkdirs() || other.isDirectory());
        File otherData = new File(other, "data");
        assertTrue(otherData.mkdirs() || otherData.isDirectory());
        File otherBackup = new File(otherData, ModScriptsBackup.BACKUP_DIR);
        assertTrue(otherBackup.mkdirs() || otherBackup.isDirectory());
        write(new File(otherBackup, "SkirmishScripts.scb"),
            "G".getBytes(StandardCharsets.US_ASCII));
        prefs().edit()
            .putString(SetupActivity.PREF_GENERALS_GAME_PATH, other.getAbsolutePath())
            .commit();

        // A marker naming that folder, written while it was moved aside.
        ModScriptsBackup.setEnabled(app, true);
        prefs().edit().putString("mods_order_generals", "").commit();
        // Simulate the marker by moving it aside through the Generals folder path directly.
        // recover() reads the marker file, so drive reconcile() the way the launcher would.
        ModScriptsBackup.Result renamed = ModScriptsBackup.reconcile(app, other.getAbsolutePath(), true);
        assertFalse("already aside", renamed.renamed);

        // Zero Hour is selected and has no mods on, so the other game's folder must come back.
        ModScriptsBackup.recover(app);
        assertTrue(new File(otherData, ModScriptsBackup.SCRIPTS_DIR).isDirectory());
        assertFalse(otherBackup.exists());
    }

    /** No game folder chosen: nothing to do, and no stale marker is kept. */
    @Test
    public void noGameFolderIsHarmless() {
        prefs().edit().remove(SetupActivity.PREF_GAME_PATH).commit();
        ModScriptsBackup.setEnabled(app, true);
        ModScriptsBackup.Result result = ModScriptsBackup.sync(app);
        assertFalse(result.renamed);
        assertFalse(result.failed);
    }

    /** The recorded name is honoured: a scripts folder that was spelled differently comes back. */
    @Test
    public void theOriginalNameIsRestoredExactly() throws IOException {
        File upper = new File(data, "Scripts");
        assertTrue(upper.mkdirs() || upper.isDirectory());
        write(new File(upper, "SkirmishScripts.scb"), "UPPER".getBytes(StandardCharsets.US_ASCII));
        ModScriptsBackup.setEnabled(app, true);
        setModsOn(1);

        ModScriptsBackup.Result result = ModScriptsBackup.apply(app);

        assertTrue(result.renamed);
        assertTrue("moved to the lower-case backup name", backup().isDirectory());
        assertEquals("the name it had is remembered", "Scripts", ModScriptsBackup.recordedName(app));

        setModsAllOff(1);
        assertTrue(ModScriptsBackup.sync(app).restored);
        assertTrue("restored under its own name", upper.isDirectory());
        assertEquals("UPPER", read(new File(upper, "SkirmishScripts.scb")));
        assertNull(ModScriptsBackup.recordedName(app));
    }

    /**
     * The real device layout: the game's scripts live in an upper-case {@code Data/Scripts} (the
     * engine was written on Windows), while the launcher installs its language packs into a
     * lower-case {@code data/<language>/generals.str} beside it. Both directories are spelled
     * "data" and the engine matches either, so the feature must act on the one that holds the
     * scripts. Taking the exact lower-case match renamed the language folder and left the scripts
     * in place, which is the reported "it does not disable the folder".
     */
    @Test
    public void theScriptsAreFoundWhenTheLauncherAlsoMadeALowerCaseData() throws IOException {
        File upperData = new File(game, "Data");
        File upperScripts = new File(upperData, "Scripts");
        assertTrue(upperScripts.mkdirs() || upperScripts.isDirectory());
        write(new File(upperScripts, "SkirmishScripts.scb"), "AI".getBytes(StandardCharsets.US_ASCII));
        File lowerData = new File(game, "data");
        File language = new File(lowerData, "english");
        assertTrue(language.mkdirs() || language.isDirectory());
        write(new File(language, "generals.str"), "STR".getBytes(StandardCharsets.US_ASCII));

        ModScriptsBackup.setEnabled(app, true);
        setModsOn(1);

        assertTrue("the retail scripts folder is the one moved aside",
            ModScriptsBackup.apply(app).renamed);
        assertFalse(upperScripts.exists());
        assertTrue("the backup sits beside it", new File(upperData, "scriptsbackup").isDirectory());
        assertFalse("nothing in the launcher's language folder moved",
            new File(lowerData, "scriptsbackup").exists());
        assertTrue("the language file is untouched", new File(language, "generals.str").isFile());

        setModsAllOff(1);
        assertTrue(ModScriptsBackup.sync(app).restored);
        assertTrue("back under its own spelling", upperScripts.isDirectory());
        assertEquals("AI", read(new File(upperScripts, "SkirmishScripts.scb")));
    }

    /** The other way round: the scripts are in the lower-case data, an upper-case Data has none. */
    @Test
    public void theScriptsAreFoundWhenTheyLiveInTheLowerCaseData() throws IOException {
        File upperData = new File(game, "Data");
        assertTrue(upperData.mkdirs() || upperData.isDirectory());
        makeScripts();
        ModScriptsBackup.setEnabled(app, true);
        setModsOn(1);

        assertTrue(ModScriptsBackup.apply(app).renamed);

        assertFalse(scripts().exists());
        assertTrue("the backup is in the folder that held the scripts", backup().isDirectory());
    }

    /**
     * Safe start ("start without mods") switches every mod off through {@link ModManager#switchOff}
     * rather than the Mods screen, so the folder has to come back the same way: the caller follows
     * it with a {@link ModScriptsBackup#sync}.
     */
    @Test
    public void switchingAllModsOffRestoresTheScriptsFolder() throws IOException {
        makeScripts();
        ModScriptsBackup.setEnabled(app, true);
        setModsOn(1);
        ModScriptsBackup.apply(app);
        assertTrue(backup().isDirectory());

        ModManager.switchOff(app, null);
        assertTrue(ModScriptsBackup.sync(app).restored);

        assertTrue(scripts().isDirectory());
        assertFalse(backup().exists());
        assertEquals("AI-SCRIPTS", read(new File(scripts(), "SkirmishScripts.scb")));
    }
}
