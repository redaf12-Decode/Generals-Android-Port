/*
**	Command & Conquer Generals Zero Hour(tm)
**	Copyright 2025 Electronic Arts Inc.
**
**	This program is free software: you can redistribute it and/or modify
**	it under the terms of the GNU General Public License as published by
**	the Free Software Foundation, either version 3 of the License, or
**	(at your option) any later version.
**
**	This program is distributed in the hope that it will be useful,
**	but WITHOUT ANY WARRANTY; without even the implied warranty of
**	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
**	GNU General Public License for more details.
**
**	You should have received a copy of the GNU General Public License
**	along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

// GeneralsX @feature Android floating toolbox 17/10/2026 The launcher's floating-toolbox
// settings are read on the game's launch path, so the cases worth testing are the ones that
// file can actually be in: never written, written by an older build, written with values a
// different build disagreed about, and written for the other game.

package com.generalsx.zerohour;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class ToolboxConfigTest {

    private static Context app() {
        return RuntimeEnvironment.getApplication();
    }

    private static SharedPreferences prefs() {
        return app().getSharedPreferences(ToolboxConfig.PREFS_NAME, Context.MODE_PRIVATE);
    }

    @Test
    public void aFreshInstallHasNoToolbox() {
        ToolboxConfig config = ToolboxConfig.load(app(), SetupActivity.GAME_ZERO_HOUR);

        assertFalse("the floating toolbox must be off until it is asked for", config.enabled);
        assertEquals(ToolboxConfig.STYLE_ROUNDED, config.iconStyle);
        assertEquals(ToolboxConfig.SIZE_MEDIUM_DP, config.iconSizeDp, 0.001f);
        assertEquals(ToolboxConfig.DEFAULT_OPACITY, config.iconOpacity, 0.001f);
        assertEquals(ToolboxConfig.DEFAULT_ICON_X, config.iconX, 0.001f);
        assertEquals(ToolboxConfig.DEFAULT_ICON_Y, config.iconY, 0.001f);
        assertEquals("a fresh install restores the built-in button layout",
            "", config.defaultLayoutJson);
    }

    @Test
    public void everySettingSurvivesAReload() {
        ToolboxConfig saved = new ToolboxConfig();
        saved.enabled = true;
        saved.iconStyle = ToolboxConfig.STYLE_DIAMOND;
        saved.iconSizeDp = ToolboxConfig.SIZE_LARGE_DP;
        saved.iconOpacity = 0.6f;
        saved.iconX = 0.4f;
        saved.iconY = 0.7f;
        saved.defaultLayoutJson = "{\"format\":\"GeneralsZHTouchControls\",\"version\":2,\"buttons\":[]}";
        saved.save(app(), SetupActivity.GAME_ZERO_HOUR);

        // A second load is what the launcher and then the game each do: two independent reads
        // of the same file must agree.
        ToolboxConfig loaded = ToolboxConfig.load(app(), SetupActivity.GAME_ZERO_HOUR);
        assertTrue(loaded.enabled);
        assertEquals(ToolboxConfig.STYLE_DIAMOND, loaded.iconStyle);
        assertEquals(ToolboxConfig.SIZE_LARGE_DP, loaded.iconSizeDp, 0.001f);
        assertEquals(0.6f, loaded.iconOpacity, 0.001f);
        assertEquals(0.4f, loaded.iconX, 0.001f);
        assertEquals(0.7f, loaded.iconY, 0.001f);
        assertEquals(saved.defaultLayoutJson, loaded.defaultLayoutJson);
    }

    @Test
    public void eachGameKeepsItsOwnToolbox() {
        ToolboxConfig zh = new ToolboxConfig();
        zh.enabled = true;
        zh.iconStyle = ToolboxConfig.STYLE_CIRCLE;
        zh.save(app(), SetupActivity.GAME_ZERO_HOUR);

        ToolboxConfig generals = ToolboxConfig.load(app(), SetupActivity.GAME_GENERALS);
        assertFalse("one game's icon must not appear over the other game", generals.enabled);
        assertEquals(ToolboxConfig.STYLE_ROUNDED, generals.iconStyle);

        generals.enabled = true;
        generals.iconStyle = ToolboxConfig.STYLE_DIAMOND;
        generals.save(app(), SetupActivity.GAME_GENERALS);

        assertEquals(ToolboxConfig.STYLE_CIRCLE,
            ToolboxConfig.load(app(), SetupActivity.GAME_ZERO_HOUR).iconStyle);
        assertEquals(ToolboxConfig.STYLE_DIAMOND,
            ToolboxConfig.load(app(), SetupActivity.GAME_GENERALS).iconStyle);
    }

    @Test
    public void theSelectedGamePicksWhichToolboxIsRead() {
        ToolboxConfig config = ToolboxConfig.load(app());
        config.enabled = true;
        config.save(app());

        SetupActivity.setSelectedGame(app(), SetupActivity.GAME_GENERALS);
        assertFalse("an enabled Zero Hour toolbox leaked into Generals",
            ToolboxConfig.load(app()).enabled);

        SetupActivity.setSelectedGame(app(), SetupActivity.GAME_ZERO_HOUR);
        assertTrue(ToolboxConfig.load(app()).enabled);
    }

    @Test
    public void valuesOutsideTheAllowedRangeAreClampedOnLoad() {
        prefs().edit()
            .putInt("zh.icon_style", 99)
            .putFloat("zh.icon_size_dp", 9999f)
            .putFloat("zh.icon_opacity", -4f)
            .putFloat("zh.icon_x", 3f)
            .putFloat("zh.icon_y", -2f)
            .commit();

        ToolboxConfig config = ToolboxConfig.load(app(), SetupActivity.GAME_ZERO_HOUR);
        assertEquals("an unknown style falls back to the one that is always drawn",
            ToolboxConfig.STYLE_ROUNDED, config.iconStyle);
        assertTrue(config.iconSizeDp >= ToolboxConfig.MIN_SIZE_DP
            && config.iconSizeDp <= ToolboxConfig.MAX_SIZE_DP);
        assertTrue(config.iconOpacity >= ToolboxConfig.MIN_OPACITY
            && config.iconOpacity <= ToolboxConfig.MAX_OPACITY);
        assertTrue("the icon must stay on screen horizontally", config.iconX <= 0.95f);
        assertTrue("the icon must stay on screen vertically", config.iconY >= 0.06f);
    }

    @Test
    public void unreadableValuesFallBackToTheDefaultsInsteadOfThrowing() {
        // The wrong type under a key of ours is exactly what a hand-edited or half-written file
        // looks like, and this file is read before the engine is loaded.
        prefs().edit().putString("zh.enabled", "yes").commit();

        ToolboxConfig config = ToolboxConfig.load(app(), SetupActivity.GAME_ZERO_HOUR);
        assertFalse(config.enabled);
        assertEquals(ToolboxConfig.STYLE_ROUNDED, config.iconStyle);
    }

    @Test
    public void settingsFromAnOlderFlatFileAreMigratedIntoTheSelectedGame() {
        // No schema key, and the settings written unprefixed: the shape this file had before it
        // was split per game.
        prefs().edit()
            .putBoolean("enabled", true)
            .putInt("icon_style", ToolboxConfig.STYLE_CIRCLE)
            .putFloat("icon_x", 0.25f)
            .putFloat("icon_y", 0.4f)
            .commit();

        SetupActivity.setSelectedGame(app(), SetupActivity.GAME_GENERALS);
        ToolboxConfig migrated = ToolboxConfig.load(app(), SetupActivity.GAME_GENERALS);

        assertTrue("the migrated choice must not be silently dropped", migrated.enabled);
        assertEquals(ToolboxConfig.STYLE_CIRCLE, migrated.iconStyle);
        assertEquals(0.25f, migrated.iconX, 0.001f);
        assertEquals(0.4f, migrated.iconY, 0.001f);
        assertFalse("the old keys must not be read a second time", prefs().contains("enabled"));
        assertEquals("the file must be marked as migrated", ToolboxConfig.SCHEMA,
            prefs().getInt("schema", 0));
        assertFalse("the game that did not migrate must stay untouched",
            ToolboxConfig.load(app(), SetupActivity.GAME_ZERO_HOUR).enabled);
    }

    @Test
    public void migrationNeverOverwritesASettingThatIsAlreadyThere() {
        prefs().edit()
            .putBoolean("generals.enabled", true)
            .putInt("generals.icon_style", ToolboxConfig.STYLE_DIAMOND)
            .putBoolean("enabled", false)
            .putInt("icon_style", ToolboxConfig.STYLE_CIRCLE)
            .commit();

        ToolboxConfig config = ToolboxConfig.load(app(), SetupActivity.GAME_GENERALS);
        assertTrue("the per-game value must win over the legacy one", config.enabled);
        assertEquals(ToolboxConfig.STYLE_DIAMOND, config.iconStyle);
    }

    @Test
    public void resettingOneGameLeavesTheOtherAlone() {
        ToolboxConfig zh = new ToolboxConfig();
        zh.enabled = true;
        zh.save(app(), SetupActivity.GAME_ZERO_HOUR);
        ToolboxConfig generals = new ToolboxConfig();
        generals.enabled = true;
        generals.save(app(), SetupActivity.GAME_GENERALS);

        ToolboxConfig.reset(app(), SetupActivity.GAME_ZERO_HOUR);

        ToolboxConfig reloaded = ToolboxConfig.load(app(), SetupActivity.GAME_ZERO_HOUR);
        assertFalse(reloaded.enabled);
        assertEquals(ToolboxConfig.SIZE_MEDIUM_DP, reloaded.iconSizeDp, 0.001f);
        assertTrue("restoring one game's defaults must not touch the other game",
            ToolboxConfig.load(app(), SetupActivity.GAME_GENERALS).enabled);
    }

    @Test
    public void theSizePresetsAreTheOnlySizesTheLauncherOffers() {
        assertEquals(0, ToolboxConfig.sizeIndex(ToolboxConfig.SIZE_SMALL_DP));
        assertEquals(1, ToolboxConfig.sizeIndex(ToolboxConfig.SIZE_MEDIUM_DP));
        assertEquals(2, ToolboxConfig.sizeIndex(ToolboxConfig.SIZE_LARGE_DP));
        // Anything else collapses onto the nearest preset, so the segmented control can always
        // show something that is actually one of its three choices.
        assertEquals(2, ToolboxConfig.sizeIndex(9999f));
        assertEquals(0, ToolboxConfig.sizeIndex(0f));
        assertEquals(ToolboxConfig.SIZE_LARGE_DP, ToolboxConfig.sizeForIndex(2), 0.001f);
        assertEquals(ToolboxConfig.SIZE_MEDIUM_DP, ToolboxConfig.sizeForIndex(7), 0.001f);
    }

    @Test
    public void savingAPositionKeepsTheRestOfTheSettings() {
        ToolboxConfig config = new ToolboxConfig();
        config.enabled = true;
        config.iconStyle = ToolboxConfig.STYLE_DIAMOND;
        config.save(app(), SetupActivity.GAME_ZERO_HOUR);

        ToolboxConfig.savePosition(app(), SetupActivity.GAME_ZERO_HOUR, 0.15f, 0.8f);

        ToolboxConfig loaded = ToolboxConfig.load(app(), SetupActivity.GAME_ZERO_HOUR);
        assertEquals(0.15f, loaded.iconX, 0.001f);
        assertEquals(0.8f, loaded.iconY, 0.001f);
        assertTrue("the overlay writes the position mid-match: it must not clear the rest",
            loaded.enabled);
        assertEquals(ToolboxConfig.STYLE_DIAMOND, loaded.iconStyle);
    }
}
