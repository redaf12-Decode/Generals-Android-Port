// GeneralsX @build Android port 07/11/2026
//
// The scripts-folder option as the player meets it: the real Mods screen, the real switch on it,
// and the real switch on a mod card. This is the wiring the unit tests cannot see -- that the
// option is on the screen, that its state is saved, and that switching a mod on and off is what
// moves the folder aside and puts it back.

package com.generalsx.zerohour;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;

import com.google.android.material.materialswitch.MaterialSwitch;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class ModScriptsBackupScreenTest {

    private static final String MOD_NAME = "Example Mod";

    private Context app;
    private File game;
    private File data;

    @Before
    public void setUp() throws IOException {
        app = RuntimeEnvironment.getApplication();
        game = new File(app.getCacheDir(), "screen-game-" + System.nanoTime());
        assertTrue(game.mkdirs() || game.isDirectory());
        data = new File(game, "data");
        assertTrue(data.mkdirs() || data.isDirectory());
        prefs().edit().clear()
            .putString(SetupActivity.PREF_GAME_PATH, game.getAbsolutePath())
            .putString(SetupActivity.PREF_GAME, SetupActivity.GAME_ZERO_HOUR)
            // The mod is installed but switched off, so nothing auto-enables it.
            .putString("mods_order", "0|" + MOD_NAME)
            .commit();
        ModScriptsBackup.setEnabled(app, false);
        makeScripts();
        makeMod();
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

    private void makeScripts() throws IOException {
        assertTrue(scripts().mkdirs() || scripts().isDirectory());
        try (FileOutputStream out = new FileOutputStream(new File(scripts(), "SkirmishScripts.scb"))) {
            out.write("AI".getBytes(StandardCharsets.US_ASCII));
        }
    }

    /** A mod folder the Mods screen lists: a folder in mods/ holding one .big archive. */
    private void makeMod() throws IOException {
        File dir = new File(ModManager.modsRoot(app), MOD_NAME);
        assertTrue(dir.mkdirs() || dir.isDirectory());
        try (FileOutputStream out = new FileOutputStream(new File(dir, "mod.big"))) {
            out.write(new byte[128]);
        }
    }

    private static List<View> allViews(View root) {
        List<View> out = new ArrayList<>();
        out.add(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                out.addAll(allViews(group.getChildAt(i)));
            }
        }
        return out;
    }

    private static View findSwitch(View root, String label) {
        for (View view : allViews(root)) {
            if (view instanceof TextView && label.contentEquals(((TextView) view).getText())) {
                // The switch row is title/description in one column beside the switch itself, so
                // the switch is a sibling of the title's column, not the title's own view.
                ViewGroup column = (ViewGroup) view.getParent();
                ViewGroup row = (ViewGroup) column.getParent();
                for (int i = 0; i < row.getChildCount(); i++) {
                    if (row.getChildAt(i) instanceof CompoundButton) {
                        return row.getChildAt(i);
                    }
                }
            }
        }
        return null;
    }

    private static View findModToggle(View root, String description) {
        for (View view : allViews(root)) {
            if (view instanceof CompoundButton && description.contentEquals(view.getContentDescription())) {
                return view;
            }
        }
        return null;
    }

    /** The Mods screen with the game folder set up as this test leaves it. */
    private ActivityController<ModManagerActivity> openScreen() {
        ActivityController<ModManagerActivity> controller =
            Robolectric.buildActivity(ModManagerActivity.class).setup();
        View root = controller.get().findViewById(android.R.id.content);
        assertNotNull(root);
        return controller;
    }

    @Test
    public void theOptionIsOnTheModsScreenAndItsStateIsSaved() {
        ActivityController<ModManagerActivity> controller = openScreen();
        View root = controller.get().findViewById(android.R.id.content);

        String label = app.getString(R.string.mods_scripts_backup_switch);
        View option = findSwitch(root, label);
        assertNotNull("the option is on the Mods screen", option);
        assertTrue("it is the switch row's own switch", option instanceof MaterialSwitch);
        assertFalse("off by default", ((CompoundButton) option).isChecked());

        ((CompoundButton) option).setChecked(true);

        assertTrue("the choice is saved", ModScriptsBackup.isEnabled(app));
        assertTrue("no mod is on yet, so the folder stays", scripts().isDirectory());

        controller.pause().stop().destroy();
    }

    @Test
    public void switchingAModOnMovesTheFolderAndSwitchingItOffPutsItBack() {
        ModScriptsBackup.setEnabled(app, true);
        ActivityController<ModManagerActivity> controller = openScreen();
        View root = controller.get().findViewById(android.R.id.content);

        View toggle = findModToggle(root, MOD_NAME);
        assertNotNull("the mod card has its switch", toggle);
        assertFalse("the mod starts switched off", ((CompoundButton) toggle).isChecked());

        ((CompoundButton) toggle).setChecked(true);
        assertFalse("data/scripts is moved aside", scripts().exists());
        assertTrue("data/scriptsbackup is there", backup().isDirectory());
        assertTrue("the mod is switched on", ModManager.anyEnabled(app));

        ((CompoundButton) toggle).setChecked(false);
        assertTrue("data/scripts is back", scripts().isDirectory());
        assertFalse("data/scriptsbackup is gone", backup().exists());
        assertFalse("the mod is switched off", ModManager.anyEnabled(app));
        assertEquals("AI", new String(readFile(new File(scripts(), "SkirmishScripts.scb")),
            StandardCharsets.US_ASCII));

        controller.pause().stop().destroy();
    }

    /** Turning the option off with a mod still on brings the folder back at once. */
    @Test
    public void turningTheOptionOffRestoresTheFolder() {
        ModScriptsBackup.setEnabled(app, true);
        prefs().edit().putString("mods_order", "1|" + MOD_NAME).commit();
        assertTrue(ModScriptsBackup.apply(app).renamed);
        assertTrue(backup().isDirectory());

        ActivityController<ModManagerActivity> controller = openScreen();
        View root = controller.get().findViewById(android.R.id.content);
        View option = findSwitch(root, app.getString(R.string.mods_scripts_backup_switch));
        assertNotNull(option);
        assertTrue("the switch reflects the saved choice", ((CompoundButton) option).isChecked());

        ((CompoundButton) option).setChecked(false);

        assertTrue("the folder is back though the mod is still on", scripts().isDirectory());
        assertFalse(backup().exists());
        assertFalse(ModScriptsBackup.isEnabled(app));

        controller.pause().stop().destroy();
    }

    private static byte[] readFile(File file) {
        try {
            byte[] bytes = new byte[(int) file.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                int n = 0, got;
                while (n < bytes.length && (got = in.read(bytes, n, bytes.length - n)) > 0) {
                    n += got;
                }
            }
            return bytes;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
