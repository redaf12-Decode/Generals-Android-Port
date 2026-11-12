// GeneralsX @build Android port 08/11/2026
//
// ThemeHelper decides which palette the launcher draws in. The one behaviour that is easy to
// get wrong and impossible to see in a screenshot is the DEFAULT: a fresh install must follow
// the phone (SYSTEM), while any stored choice must be preserved. That is pure SharedPreferences
// plus Configuration logic, so it is checked here without a device.

package com.ports.generalsx;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.res.Configuration;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
public class ThemeHelperTest {

    private static Context app() {
        return RuntimeEnvironment.getApplication();
    }

    private static void clearMode() {
        app().getSharedPreferences(LocaleHelper.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove("launcher_theme_mode").apply();
    }

    @Test
    public void freshInstallDefaultsToSystem() {
        clearMode();
        assertEquals("no stored preference means SYSTEM",
            ThemeHelper.SYSTEM, ThemeHelper.getMode(app()));
    }

    @Test
    public void storedChoiceIsPreserved() {
        for (int mode : new int[] { ThemeHelper.STANDARD, ThemeHelper.NAVY, ThemeHelper.DAY,
                ThemeHelper.SYSTEM }) {
            ThemeHelper.setMode(app(), mode);
            assertEquals("a stored mode must survive", mode, ThemeHelper.getMode(app()));
        }
        clearMode();
    }

    /** An out-of-range value falls back to the default rather than crashing a screen. */
    @Test
    public void unknownStoredModeFallsBackToSystem() {
        app().getSharedPreferences(LocaleHelper.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt("launcher_theme_mode", 99).apply();
        assertEquals(ThemeHelper.SYSTEM, ThemeHelper.getMode(app()));
        clearMode();
    }

    /** Light system -> DAY palette; dark system -> STANDARD, with no preference stored. */
    @Test
    @Config(qualifiers = "notnight")
    public void systemModeResolvesToDayInLightMode() {
        clearMode();
        assertEquals(ThemeHelper.DAY, ThemeHelper.palette(app()));
    }

    @Test
    @Config(qualifiers = "night")
    public void systemModeResolvesToStandardInDarkMode() {
        clearMode();
        assertEquals(ThemeHelper.STANDARD, ThemeHelper.palette(app()));
    }

    /** A manual choice ignores the phone's setting entirely. */
    @Test
    @Config(qualifiers = "night")
    public void manualChoiceIgnoresSystemNight() {
        ThemeHelper.setMode(app(), ThemeHelper.DAY);
        assertEquals(ThemeHelper.DAY, ThemeHelper.palette(app()));
        clearMode();
    }

    @Test
    public void systemNightMaskMatchesTheApi() {
        Configuration light = new Configuration();
        light.uiMode = Configuration.UI_MODE_NIGHT_NO;
        assertEquals(Configuration.UI_MODE_NIGHT_NO,
            light.uiMode & Configuration.UI_MODE_NIGHT_MASK);
    }
}
