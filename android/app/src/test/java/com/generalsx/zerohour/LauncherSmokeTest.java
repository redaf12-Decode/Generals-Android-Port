// GeneralsX @build Android CI 06/11/2026
//
// The launcher's own wiring, checked without a device:
//
//   - the manifest is the source of truth for which screen is the entry point, which components
//     are reachable, and that no production id is hardcoded;
//   - UiKit builds the screens' shared widgets against the real styles and the real strings, so a
//     missing resource or a broken style fails here rather than as a blank screen on a phone.
//
// Nothing here needs a native library: the launcher is Java, resources and a manifest.

package com.generalsx.zerohour;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class LauncherSmokeTest {

    private static Context app() {
        return RuntimeEnvironment.getApplication();
    }

    // ------------------------------------------------------------------ manifest, as declared

    private static String manifestText() throws Exception {
        File manifest = new File("src/main/AndroidManifest.xml");
        if (!manifest.isFile()) {
            manifest = new File("app/src/main/AndroidManifest.xml");
        }
        assertTrue("AndroidManifest.xml not found from " + new File(".").getAbsolutePath(),
            manifest.isFile());
        return new String(java.nio.file.Files.readAllBytes(manifest.toPath()),
            java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Setup is the single home-screen icon; GeneralsZHActivity is exported with MAIN but no
     * LAUNCHER category, so a shortcut placed before that change keeps resolving while no second
     * icon appears.
     */
    @Test
    public void setupIsTheOnlyLauncherEntry() throws Exception {
        String xml = manifestText();
        int launcherCount = 0, index = 0;
        while ((index = xml.indexOf("android.intent.category.LAUNCHER", index)) >= 0) {
            launcherCount++;
            index++;
        }
        assertEquals("exactly one activity must declare the LAUNCHER category", 1, launcherCount);

        // The one that does is SetupActivity.
        int setup = xml.indexOf("android:name=\".SetupActivity\"");
        assertTrue(setup >= 0);
        int nextActivity = xml.indexOf("<activity", setup + 1);
        String setupBlock = nextActivity < 0 ? xml.substring(setup) : xml.substring(setup, nextActivity);
        assertTrue(setupBlock.contains("android.intent.category.LAUNCHER"));
    }

    @Test
    public void everyActivityClassDeclaredInTheManifestExists() throws Exception {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("android:name=\"(\\.[A-Za-z0-9_.]+Activity)\"")
            .matcher(manifestText());
        int checked = 0;
        while (m.find()) {
            Class.forName("com.generalsx.zerohour" + m.group(1));
            checked++;
        }
        assertTrue("expected to check several activities, checked " + checked, checked >= 8);
    }

    /** The launcher must not hardcode its own id: the fileprovider authority is parameterised. */
    @Test
    public void theFileProviderAuthorityIsNotHardcoded() throws Exception {
        String xml = manifestText();
        assertTrue(xml.contains("android:authorities=\"${applicationId}.fileprovider\""));
    }

    /** The launcher advertises RTL support; without it Arabic and Persian screens mirror wrong. */
    @Test
    public void rtlSupportIsDeclared() throws Exception {
        assertTrue(manifestText().contains("android:supportsRtl=\"true\""));
    }



    // ------------------------------------------------------------------ manifest, as resolved

    @Test
    public void theLauncherIntentResolvesToSetupActivity() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);

        // Flags 0: a launcher queries ACTION_MAIN + CATEGORY_LAUNCHER directly. The filter does
        // not declare CATEGORY_DEFAULT, so MATCH_DEFAULT_ONLY would exclude the one real entry.
        List<ResolveInfo> resolved = app().getPackageManager().queryIntentActivities(intent, 0);
        assertNotNull(resolved);
        assertEquals("the home screen must show exactly one icon", 1, resolved.size());
        assertEquals(SetupActivity.class.getName(), resolved.get(0).activityInfo.name);
    }

    /**
     * GeneralsZHActivity is still reachable by an explicit intent (a shortcut placed before the
     * LAUNCHER category was removed), but it is not a launcher entry.
     *
     * The resolved list is taken from the manifest on disk rather than from queryIntentActivities,
     * which a Robolectric JVM answers from its own package stub and not from the real manifest.
     */
    @Test
    public void theGameActivityIsReachableButIsNotALauncherEntry() throws Exception {
        Intent explicit = new Intent(app(), GeneralsZHActivity.class);
        assertNotNull(app().getPackageManager().resolveActivity(explicit,
            PackageManager.MATCH_DEFAULT_ONLY));

        String xml = manifestText();
        int main = xml.indexOf("android:name=\".GeneralsZHActivity\"");
        assertTrue(main >= 0);
        int end = xml.indexOf("</activity>", main);
        String block = xml.substring(main, end);
        assertTrue("GeneralsZHActivity keeps MAIN so an old shortcut still resolves",
            block.contains("android.intent.action.MAIN"));
        assertFalse("GeneralsZHActivity must not be a launcher entry",
            block.contains("android.intent.category.LAUNCHER"));
    }

    // ------------------------------------------------------------------ UiKit against real resources

    /** The launcher's own theme: the widgets below are the ones the settings screens build. */
    private static Context themed() {
        return new android.view.ContextThemeWrapper(app(), R.style.Theme_GeneralsZHSettings);
    }

    @Test
    public void dpScalesWithDensity() {
        // Robolectric's default density is 1.0, so 16dp is 16px here; the point is the conversion
        // goes through the display metrics rather than being a fixed number.
        assertEquals(16, UiKit.dp(app(), 16f));
        assertEquals(0, UiKit.dp(app(), 0f));
    }

    @Test
    public void colorAndDimensionResolveAgainstTheRealResourceTable() {
        int color = UiKit.color(app(), R.color.gzh_surface);
        assertTrue("a resolved colour must have opaque alpha", (color >>> 24) != 0);
        assertTrue(UiKit.dim(app(), R.dimen.gzh_gutter) > 0);
    }

    @Test
    public void cardBodyAndSupportingTextBuildOnARealHost() {
        LinearLayout host = new LinearLayout(themed());
        LinearLayout card = UiKit.card(host);
        assertNotNull(card);

        TextView body = UiKit.body(card, "Body");
        assertEquals("Body", body.getText().toString());

        TextView supporting = UiKit.supporting(card, "Supporting");
        assertEquals("Supporting", supporting.getText().toString());
        assertNotNull(UiKit.caption(card, "Caption"));
        assertNotNull(UiKit.helpText(card, "Help"));
    }

    /** sectionHeader returns the value TextView only when asked for one, and null otherwise. */
    @Test
    public void sectionHeaderAndDividerBuild() {
        LinearLayout host = new LinearLayout(themed());
        LinearLayout section = new LinearLayout(themed());
        assertNotNull(UiKit.sectionHeader(section, 0, "Section", true));
        assertNull("no value was asked for", UiKit.sectionHeader(section, 0, "Plain", false));
        assertNotNull(UiKit.divider(host));
    }

    @Test
    public void segmentedControlBuildsAndReportsTheChosenIndex() {
        LinearLayout host = new LinearLayout(themed());
        final int[] picked = { -1 };
        View segmented = UiKit.segmented(host, new CharSequence[] { "30 Hz", "60 Hz" }, 1,
            index -> picked[0] = index);
        assertNotNull(segmented);
        assertTrue(segmented instanceof ViewGroup);

        // The index passed in is the one checked, and a tap reports the index of the button hit.
        com.google.android.material.button.MaterialButtonToggleGroup group =
            (com.google.android.material.button.MaterialButtonToggleGroup) segmented;
        assertEquals("the initially checked index must be the one asked for",
            group.getChildAt(1).getId(), group.getCheckedButtonId());

        ((com.google.android.material.button.MaterialButton) group.getChildAt(0)).performClick();
        assertEquals("selecting the first button reports index 0", 0, picked[0]);
    }

    /** The rows and buttons the settings screens are made of, built against the real styles. */
    @Test
    public void buttonRowAndListRowBuild() {
        LinearLayout host = new LinearLayout(themed());
        LinearLayout row = UiKit.buttonRow(host);
        assertNotNull(row);
        assertNotNull(UiKit.button(row, UiKit.BTN_PRIMARY, 0, "Play", () -> { }));
        assertNotNull(UiKit.listRow(host, 0, "Title", "Summary", () -> { }));
        assertNotNull(UiKit.switchRow(host, "Switch", "Description"));
        assertNotNull(UiKit.chip(host, 0, "Chip", R.color.gzh_on_surface, R.color.gzh_surface_container));
    }

    @Test
    public void everyStringTheLauncherNeedsIsPresentInTheBaseLocale() {
        // A handful of the launcher's own labels, one from each screen group, resolved for real.
        int[] ids = {
            R.string.app_name,
            R.string.setup_window_title,
            R.string.setup_card_sim_rate,
            R.string.setup_engine_missing_title,
            R.string.setup_engine_missing_message,
        };
        for (int id : ids) {
            String value = app().getString(id);
            assertNotNull(value);
            assertFalse("string resource " + id + " is empty", value.trim().isEmpty());
        }
    }

    /** The engine-missing sentence has to be actionable, not a one-word error. */
    @Test
    public void theEngineMissingMessageTellsThePlayerWhatToDo() {
        String message = app().getString(R.string.setup_engine_missing_message);
        assertTrue("the message must name the engine", message.toLowerCase().contains("engine"));
        assertTrue("the message must say what to do",
            message.toLowerCase().contains("apk") || message.toLowerCase().contains("install"));
    }

    private static int selectedChildIndex(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            if (group.getChildAt(i).isSelected()) {
                return i;
            }
        }
        return -1;
    }
}
