// GeneralsX @build Android Mod Manager 19/10/2026 The Mods screen is built entirely in code, like
// every other launcher screen, so "does it have anything to say before a game folder is chosen" and
// "does each game only see its own mods" cannot be answered by reading a layout. These two tests
// build the Activity and read the view tree, then drive ModManager directly with two game folders.

package com.generalsx.zerohour;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, qualifiers = "w411dp-h891dp-xxhdpi")
public class ModManagerActivityTest {

    /**
     * With no game folder chosen yet the screen must say so instead of showing an empty list that
     * looks like "your mods are gone" -- and it must build at all, with both games unconfigured.
     */
    @Test
    public void withoutAGameFolderTheScreenSaysWhatToDoFirst() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply();

        ActivityController<ModManagerActivity> controller =
            Robolectric.buildActivity(ModManagerActivity.class).setup();
        try {
            ModManagerActivity activity = controller.get();
            assertNotNull("the Mods screen has no content view", activity.getWindow().getDecorView());
            View decor = activity.getWindow().getDecorView();
            assertNotNull("the Mods screen does not say a game folder is needed",
                textAnywhere(decor, activity.getString(R.string.mods_no_game_title)));
            assertNotNull("the Mods screen does not say where to choose the game folder",
                textAnywhere(decor, activity.getString(R.string.mods_no_game_message)));
        } finally {
            controller.close();
        }
    }

    /**
     * Both games are supported: each game's mods/ folder is its own list, with its own order and
     * switches. Switching the game on Home switches which list this screen shows.
     */
    @Test
    public void eachGameListsOnlyItsOwnMods() throws IOException {
        Context context = RuntimeEnvironment.getApplication();
        File zh = new File(context.getCacheDir(), "game-zh-" + System.nanoTime());
        File generals = new File(context.getCacheDir(), "game-generals-" + System.nanoTime());
        assertTrue(zh.mkdirs());
        assertTrue(generals.mkdirs());
        addMod(zh, "Contra007", "Contra 007");
        addMod(zh, "Shockwave", "Shockwave");
        addMod(generals, "ColdWarCrisis", "Cold War Crisis");

        context.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(SetupActivity.PREF_GAME_PATH, zh.getAbsolutePath())
            .putString(SetupActivity.PREF_GENERALS_GAME_PATH, generals.getAbsolutePath())
            .putString("mods_order", "")
            .putString("mods_order_generals", "")
            .apply();

        SetupActivity.setSelectedGame(context, SetupActivity.GAME_ZERO_HOUR);
        List<ModManager.Mod> zhMods = ModManager.listMods(context);
        assertEquals("Zero Hour does not list its two mods", 2, zhMods.size());
        assertEquals("Contra007", zhMods.get(0).dir.getName());
        assertEquals("Shockwave", zhMods.get(1).dir.getName());
        assertEquals("the mod.ini name is not used", "Contra 007", zhMods.get(0).name);
        assertFalse("a mod without a stored switch must start switched off", zhMods.get(0).enabled);

        // Switch one on and put it on top: the choice belongs to Zero Hour alone.
        java.util.Collections.reverse(zhMods);
        zhMods.get(0).enabled = true;
        ModManager.saveOrder(context, zhMods);

        List<ModManager.Mod> reordered = ModManager.listMods(context);
        assertEquals("the stored order was not applied", "Shockwave", reordered.get(0).dir.getName());
        assertTrue("the stored switch was not applied", reordered.get(0).enabled);
        assertEquals("the other mod must stay off", false, reordered.get(1).enabled);

        SetupActivity.setSelectedGame(context, SetupActivity.GAME_GENERALS);
        List<ModManager.Mod> generalsMods = ModManager.listMods(context);
        assertEquals("Generals does not list its own mod", 1, generalsMods.size());
        assertEquals("ColdWarCrisis", generalsMods.get(0).dir.getName());
        assertFalse("a mod switched on in Zero Hour leaked into Generals", generalsMods.get(0).enabled);
        assertEquals("the enabled count must follow the selected game",
            0, ModManager.enabledCount(context));

        // And back, so the shared pref is left the way it was found.
        SetupActivity.setSelectedGame(context, SetupActivity.GAME_ZERO_HOUR);
        assertEquals("Zero Hour lost the switch after switching games",
            1, ModManager.enabledCount(context));
    }

    private static void addMod(File game, String folder, String name) throws IOException {
        File dir = new File(new File(game, ModManager.MODS_DIR), folder);
        assertTrue(dir.mkdirs());
        try (FileWriter w = new FileWriter(new File(dir, "mod.ini"), false)) {
            w.write("Name = " + name + "\n");
        }
    }

    private static TextView textAnywhere(View root, String text) {
        if (root instanceof TextView && text.contentEquals(((TextView) root).getText())) {
            return (TextView) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = textAnywhere(group.getChildAt(i), text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
