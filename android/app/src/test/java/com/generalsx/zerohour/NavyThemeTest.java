// GeneralsX @build Android navy theme 19/10/2026 The launcher has two palettes (values/ daylight,
// values-night/ NAVY) and twelve accent overlays that re-point the surface ladder as well as the
// primary. That is one invariant that no layout or drawable can show by inspection: in darkmode
// EVERY accent must leave the navy walls alone and only recolour its own roles. These tests resolve
// the theme attributes the widgets actually read, per accent, the same way UiKit does.
//
// The expected navy values are written out rather than read from R.color on purpose: reading the
// resource back would agree with itself even if the accent overlays repainted it, which is exactly
// the regression this guards.

package com.generalsx.zerohour;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;

import com.google.android.material.navigation.NavigationView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class NavyThemeTest {

    /** Deep-sea ground, from the source branch's navy palette. */
    private static final int NAVY_BACKGROUND = 0xFF06111D;
    private static final int NAVY_SURFACE = 0xFF0A1726;
    /** The drawer and the bottom rail sit on the low step. */
    private static final int NAVY_SURFACE_CONTAINER_LOW = 0xFF0C1B2D;
    /** Cards and dialogs sit on the container step. */
    private static final int NAVY_SURFACE_CONTAINER = 0xFF0D1E30;
    private static final int NAVY_ON_SURFACE = 0xFFF5F7FB;

    /** Daylight is untouched: the lavender-white ground the launcher shipped with. */
    private static final int DAYLIGHT_BACKGROUND = 0xFFFAF8FC;
    private static final int DAYLIGHT_PRIMARY = 0xFF6750A4;

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
    public void theDarkHalfOfThePaletteIsTheNavyTheme() {
        Context app = RuntimeEnvironment.getApplication();
        assertEquals("the dark ground is not the navy deep-sea tone",
            NAVY_BACKGROUND, app.getColor(R.color.gzh_background));
        assertEquals("the dark surface is not the navy surface",
            NAVY_SURFACE, app.getColor(R.color.gzh_surface));
        assertEquals("the dark surface ladder did not follow the navy palette",
            NAVY_SURFACE_CONTAINER_LOW, app.getColor(R.color.gzh_surface_container_low));
        assertEquals("the dialogs' panel colour (gzh_dialog_background, M3 alert dialogs)"
                + " is not the navy container step",
            0xFF15273C, app.getColor(R.color.gzh_surface_container_high));
        assertEquals("the dark text colour is not the navy palette's cool white",
            NAVY_ON_SURFACE, app.getColor(R.color.gzh_on_surface));
        assertEquals("the status-bar icons are not set for a dark navy ground",
            false, app.getResources().getBoolean(R.bool.gzh_light_system_bars));

        // The status bar is transparent over the navy page, so the icon colour is the only thing
        // keeping it legible: light content in darkmode, dark content in daylight.
        assertFalse("the status-bar icons would be dark on the navy ground",
            attrBool(new ContextThemeWrapper(app, R.style.Theme_GeneralsZHSettings),
                android.R.attr.windowLightStatusBar));
    }

    /**
     * Every one of the twelve accents keeps its own primary (so it stays recognisable, and the
     * picker's swatch still describes what the user will get) while every surface role stays navy,
     * so picking an accent can never wash the navy theme out of the interface.
     */
    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
    public void everyAccentKeepsItsOwnPrimaryOnTheNavyWalls() {
        Context app = RuntimeEnvironment.getApplication();
        try {
            for (int accent = 0; accent < ThemeHelper.ACCENT_COUNT; accent++) {
                ThemeHelper.setSavedAccent(app, accent);
                Context themed = new ContextThemeWrapper(app, R.style.Theme_GeneralsZHSettings);
                ThemeHelper.applyAccentTheme(themed);
                String label = "accent " + accent + ": ";

                assertEquals(label + "repainted the page background",
                    NAVY_BACKGROUND, attr(themed, android.R.attr.colorBackground));
                assertEquals(label + "repainted the window background",
                    NAVY_BACKGROUND, attr(themed, android.R.attr.windowBackground));
                assertEquals(label + "repainted the surface",
                    NAVY_SURFACE, attr(themed, com.google.android.material.R.attr.colorSurface));
                assertEquals(label + "repainted the drawer / rail step",
                    NAVY_SURFACE_CONTAINER_LOW,
                    attr(themed, com.google.android.material.R.attr.colorSurfaceContainerLow));
                assertEquals(label + "repainted the card step",
                    NAVY_SURFACE_CONTAINER,
                    attr(themed, com.google.android.material.R.attr.colorSurfaceContainer));
                assertEquals(label + "changed the text colour",
                    NAVY_ON_SURFACE,
                    attr(themed, com.google.android.material.R.attr.colorOnSurface));

                // The accent itself: exactly the colour the Interface tab's swatch promises, which
                // is the night half of that accent's palette.
                assertEquals(label + "lost its own primary",
                    ThemeHelper.previewColor(themed, accent),
                    attr(themed, com.google.android.material.R.attr.colorPrimary));
            }
        } finally {
            ThemeHelper.setSavedAccent(app, ThemeHelper.ACCENT_DEFAULT);
        }
    }

    /**
     * The real screens read those attributes, so build the main screen on the navy theme and check
     * the two surfaces it paints itself (the page shell and the drawer) plus the accent it uses.
     */
    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
    public void theMainScreenAndDrawerPaintThemselvesNavy() {
        Context app = RuntimeEnvironment.getApplication();
        ThemeHelper.setSavedAccent(app, ThemeHelper.ACCENT_GOLD);
        ActivityController<SetupActivity> controller =
            Robolectric.buildActivity(SetupActivity.class).setup();
        try {
            SetupActivity activity = controller.get();
            assertEquals("the page shell is not painted the navy ground",
                NAVY_BACKGROUND, UiKit.backgroundColor(activity));
            assertEquals("the drawer / rail surface is not the navy step",
                NAVY_SURFACE_CONTAINER_LOW, UiKit.surfaceContainerLowColor(activity));

            NavigationView drawer = findDrawer(activity.getWindow().getDecorView());
            assertNotNull("the main screen no longer has a navigation drawer", drawer);
            assertTrue("the drawer background is not a flat navy fill",
                drawer.getBackground() instanceof ColorDrawable);
            assertEquals("the drawer is not painted the navy step",
                NAVY_SURFACE_CONTAINER_LOW,
                ((ColorDrawable) drawer.getBackground()).getColor());

            assertEquals("the selected accent is not the one on screen",
                app.getColor(R.color.gzh_accent_gold_primary), UiKit.accentColor(activity));
        } finally {
            controller.close();
            ThemeHelper.setSavedAccent(app, ThemeHelper.ACCENT_DEFAULT);
        }
    }

    /** Daylight mode is preserved as it was, accents included. */
    @Test
    @Config(qualifiers = "w411dp-h891dp-notnight-xxhdpi")
    public void daylightKeepsItsLightPaletteAndItsAccents() {
        Context app = RuntimeEnvironment.getApplication();
        assertEquals("daylight lost its light ground",
            DAYLIGHT_BACKGROUND, app.getColor(R.color.gzh_background));
        assertEquals("daylight's status-bar icons are not set for a light ground",
            true, app.getResources().getBoolean(R.bool.gzh_light_system_bars));
        assertTrue("the daylight status-bar icons would be light on a light ground",
            attrBool(new ContextThemeWrapper(app, R.style.Theme_GeneralsZHSettings),
                android.R.attr.windowLightStatusBar));
        assertEquals("the daylight default accent is not the base violet",
            DAYLIGHT_PRIMARY,
            attr(new ContextThemeWrapper(app, R.style.Theme_GeneralsZHSettings),
                com.google.android.material.R.attr.colorPrimary));

        try {
            ThemeHelper.setSavedAccent(app, ThemeHelper.ACCENT_BLUE);
            Context blue = new ContextThemeWrapper(app, R.style.Theme_GeneralsZHSettings);
            ThemeHelper.applyAccentTheme(blue);
            assertEquals("the blue accent no longer resolves in daylight",
                app.getColor(R.color.gzh_accent_blue_primary),
                attr(blue, com.google.android.material.R.attr.colorPrimary));
        } finally {
            ThemeHelper.setSavedAccent(app, ThemeHelper.ACCENT_DEFAULT);
        }
    }

    private static NavigationView findDrawer(View root) {
        if (root instanceof NavigationView) {
            return (NavigationView) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                NavigationView found = findDrawer(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Resolve a colour attribute the way UiKit and the widgets do. */
    private static int attr(Context c, int attribute) {
        TypedValue tv = new TypedValue();
        if (!c.getTheme().resolveAttribute(attribute, tv, true)) {
            throw new AssertionError("attribute 0x" + Integer.toHexString(attribute)
                + " does not resolve; is the mode " + isNight(c) + "?");
        }
        return tv.data;
    }

    private static boolean attrBool(Context c, int attribute) {
        return attr(c, attribute) != 0;
    }

    private static boolean isNight(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
    }
}
