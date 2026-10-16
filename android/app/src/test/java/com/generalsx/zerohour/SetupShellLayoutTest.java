// GeneralsX @build Android port 15/10/2026 The launcher screens are built entirely in code, so
// "what does this screen actually put on the display" cannot be answered by reading a layout XML --
// a shell whose children lay out to zero height, or whose page is pushed below the bottom edge,
// looks exactly like a correct one in review. These tests build SetupActivity, run a real
// measure/layout pass at a phone-sized surface, and assert what a user would see and do: the
// dashboard cards are inside the screen, the drawer lists its destinations and each one takes the
// user somewhere, the page survives rotation and a trip to a child screen, and the fallback UI is
// reachable and usable.
//
// They exist because a device reported "black screen, no buttons, but the drawer edge-swipe works".
// That is the signature of a shell that HAS content but gives it no room: the top scrim view was
// added with FrameLayout.LayoutParams to a LinearLayout, and an empty View with WRAP_CONTENT height
// is measured AT_MOST (i.e. it takes the whole parent height, because View.onMeasure turns AT_MOST
// into the spec size). The app bar and the page were then laid out at y = 2340, below the screen --
// leaving a full-height dark scrim covering everything, with the DrawerLayout's edge drag still
// working. Every assertion below fails on that tree and passes on a correct one.
//
// Known harness limits, measured rather than assumed (see the report):
//   * DrawerLayout's animated open/close is advanced from the draw pass (ViewDragHelper plus
//     View.computeScroll), which Robolectric never performs: openDrawer(Gravity.START) leaves the
//     panel at left=-900 with no listener callback, while openDrawer(view, false) moves it to 0. So
//     the app-bar button is asserted for its wiring, and the drawer panel for the open state a user
//     sees, driven non-animated.
//   * Environment.isExternalStorageManager() is not implemented by Robolectric (it throws from
//     android.os.Environment), so the nav_folder destination is asserted for presence and label
//     only, not for the screen it opens.
//   * Window insets are zero here, so the padding the shell receives on a device is not observed.

package com.generalsx.zerohour;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.view.Menu;
import android.os.Bundle;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.navigation.NavigationView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
// sdk 33: high enough for the launcher's Material 3 widgets without pulling an android-all jar for a
// newer SDK. 411x891dp at xxhdpi is an ordinary phone; the layout below uses the pixel size.
@Config(sdk = 33, qualifiers = "w411dp-h891dp-xxhdpi")
public class SetupShellLayoutTest {

    private static final int WIDTH_PX = 1080;
    private static final int HEIGHT_PX = 2340;

    // Mirrors SetupActivity's private tab constant. Only used to call showTab() through reflection
    // in the fallback test, where it is documented to be a no-op.
    private static final int TAB_HOME = 1;

    /** The ten selectable destinations R.menu.drawer defines. */
    private static final int[] DESTINATIONS = {
        R.id.nav_home, R.id.nav_folder, R.id.nav_graphics, R.id.nav_interface,
        R.id.nav_tools, R.id.nav_logs, R.id.nav_netdiag, R.id.nav_replay,
        R.id.nav_online, R.id.nav_help
    };

    @Test
    public void theDashboardIsOnScreenAndTheDrawerHasItems() {
        ActivityController<SetupActivity> controller = Robolectric.buildActivity(SetupActivity.class);
        SetupActivity activity = controller.setup().get();
        try {
            layOut(activity);
            dump(activity.getWindow().getDecorView(), 0);

            DrawerLayout drawer = drawerOf(activity);
            View shell = drawer.getChildAt(0);
            assertTrue("the content shell is not a ViewGroup", shell instanceof ViewGroup);
            ViewGroup shellGroup = (ViewGroup) shell;
            assertTrue("the shell has only " + shellGroup.getChildCount() + " children",
                shellGroup.getChildCount() >= 2);

            // 1. Nothing on screen may be laid out beyond the bottom edge, and every child of the
            //    shell must actually take vertical space -- the scrim that ate the whole height left
            //    the app bar and the page at exactly zero.
            for (int i = 0; i < shellGroup.getChildCount(); i++) {
                View child = shellGroup.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE) {
                    continue;
                }
                assertTrue("shell child " + i + " (" + child.getClass().getSimpleName()
                    + ") got no height, so it is invisible", child.getHeight() > 0);
                assertTrue("shell child " + i + " (" + child.getClass().getSimpleName()
                    + ") is laid out below the screen: top=" + absoluteTop(child)
                    + " screenHeight=" + HEIGHT_PX, absoluteTop(child) < HEIGHT_PX);
            }

            // 2. The app bar (the row that carries the title) must be on screen.
            TextView appBarText = firstVisibleTextOnScreen(shell, HEIGHT_PX, 1);
            assertNotNull("no app-bar text is on screen at all", appBarText);

            // 3. The home page must be attached AND on screen: at least the first card, which is the
            //    whole point of the screen.
            List<MaterialCardView> cards = new ArrayList<>();
            collect(shell, MaterialCardView.class, cards);
            assertTrue("the home page built no cards", !cards.isEmpty());
            MaterialCardView firstCard = cards.get(0);
            assertTrue("the first dashboard card has no height", firstCard.getHeight() > 0);
            assertTrue("the first dashboard card is below the bottom edge: top=" + absoluteTop(firstCard)
                + " screenHeight=" + HEIGHT_PX
                + " -- the page was built but given no room, which is the reported black screen",
                absoluteTop(firstCard) < HEIGHT_PX);

            // 4. Enough on-screen text to be a usable screen, not one stray label.
            int onScreen = countVisibleTextOnScreen(shell, HEIGHT_PX);
            assertTrue("only " + onScreen + " text views are actually on screen; "
                + "the page is not visible to the user", onScreen >= 5);

            // 5. The drawer must carry its destinations, all nine plus the section header. An empty
            //    NavigationView opens as a full-width blank panel -- the same "black screen" from the
            //    other side, and R.menu.drawer was referenced nowhere until this was found.
            NavigationView nav = navigationOf(shell);
            assertNotNull("the drawer has no NavigationView", nav);
            for (int id : DESTINATIONS) {
                assertNotNull("drawer menu is missing item " + idName(id), nav.getMenu().findItem(id));
            }
            assertNotNull("drawer menu is missing its section header nav_tools_header",
                nav.getMenu().findItem(R.id.nav_tools_header));
        } finally {
            controller.close();
        }
    }

    @Test
    public void theDrawerListsEveryDestinationAndEachOneTakesTheUserSomewhere() {
        ActivityController<SetupActivity> controller = Robolectric.buildActivity(SetupActivity.class);
        SetupActivity activity = controller.setup().get();
        try {
            layOut(activity);
            DrawerLayout drawer = drawerOf(activity);
            View shell = drawer.getChildAt(0);
            NavigationView nav = navigationOf(shell);

            // The app bar's trailing button is the only way in for a user who does not know the edge
            // swipe, so it has to be there and carry a click action.
            View hamburger = appBarButton(shell);
            assertNotNull("the app bar has no drawer button", hamburger);
            assertTrue("the app bar's drawer button is not clickable", hamburger.isClickable());
            assertNotNull("the app bar's drawer button has no click listener", listenerOf(hamburger));
            assertTrue("the app bar's drawer button is not attached to the window",
                hamburger.getWindowToken() != null);
            assertTrue("tapping the app bar's drawer button reported no click", hamburger.performClick());

            // The panel itself, driven non-animated (see the harness note at the top of the file):
            // open, it has to be at the screen edge with its rows on screen; closed, it has to be off
            // screen entirely.
            assertTrue("the drawer panel is not off screen before it is opened: left=" + absoluteLeft(nav),
                absoluteLeft(nav) + nav.getWidth() <= 0);
            drawer.openDrawer(nav, false);
            assertTrue("the opened drawer is not at the screen edge: left=" + absoluteLeft(nav),
                absoluteLeft(nav) == 0);
            assertTrue("the drawer reports itself closed while its panel is on screen",
                drawer.isDrawerOpen(nav));
            assertTrue("the opened drawer shows no labels at all",
                countVisibleTextInArea(nav, WIDTH_PX, HEIGHT_PX) >= 5);

            // Every destination must be listed on screen under its own label, must be checkable
            // (otherwise the drawer cannot mark it as the current destination: Material's presenter
            // refuses non-checkable items), and its row must take the user somewhere.
            for (int id : DESTINATIONS) {
                MenuItem item = nav.getMenu().findItem(id);
                assertNotNull("drawer menu is missing item " + idName(id), item);
                assertTrue("drawer destination " + idName(id) + " is not checkable, so the drawer "
                    + "can never show it as the current destination", item.isCheckable());
                assertNotNull("the drawer shows no row labelled \"" + item.getTitle() + "\" for "
                    + idName(id), rowFor(nav, item.getTitle()));
            }
            assertRowShowsPage(activity, nav, R.id.nav_home, R.string.nav_tab_home);
            assertRowShowsPage(activity, nav, R.id.nav_graphics, R.string.nav_tab_graphics);
            assertRowShowsPage(activity, nav, R.id.nav_interface, R.string.nav_tab_interface);
            // GeneralsX @tweak Android port ui-organisation 16/10/2026 The Tools page has a drawer
            // entry of its own, so it is checked here too. Deliberately before nav_help: the header
            // check below needs the page to be Help again.
            assertRowShowsPage(activity, nav, R.id.nav_tools, R.string.nav_tab_tools);
            assertRowShowsPage(activity, nav, R.id.nav_help, R.string.nav_tab_help);

            // The section header is a label, not a destination: it has to be displayed in the open
            // drawer, must not be checkable, and tapping it must not move the user (who is on Help
            // after the loop above).
            MenuItem header = nav.getMenu().findItem(R.id.nav_tools_header);
            assertNotNull("the drawer menu has no Tools section header", header);
            assertFalse("the Tools section header is checkable; section headers are labels, not "
                + "destinations", header.isCheckable());
            TextView headerLabel = labelView(nav, header.getTitle());
            assertNotNull("the drawer shows no Tools section header", headerLabel);
            assertTrue("the Tools section header is off screen horizontally: left="
                + absoluteLeft(headerLabel), onScreenX(headerLabel, WIDTH_PX));
            headerLabel.performClick();
            settle();
            assertEquals("tapping the Tools section header changed the page",
                activity.getString(R.string.nav_tab_help), appBarTitleText(activity));

            // Closing it again -- how it is left after a destination is chosen -- takes the panel off
            // screen, so it cannot sit invisibly over the page.
            drawer.closeDrawer(nav, false);
            assertTrue("the closed drawer is still on screen: left=" + absoluteLeft(nav),
                absoluteLeft(nav) + nav.getWidth() <= 0);
        } finally {
            controller.close();
        }
    }

    @Test
    public void theToolDestinationsOpenTheirScreensAndThePageSurvivesComingBack() {
        ActivityController<SetupActivity> controller = Robolectric.buildActivity(SetupActivity.class);
        SetupActivity activity = controller.setup().get();
        try {
            layOut(activity);
            NavigationView nav = navigationOf(drawerOf(activity).getChildAt(0));

            // Start from a page that is not Home, so "did we come back to the right page" is a real
            // question rather than the default.
            assertRowShowsPage(activity, nav, R.id.nav_interface, R.string.nav_tab_interface);

            assertRowStartsScreen(activity, nav, R.id.nav_logs, LogViewerActivity.class);
            assertRowStartsScreen(activity, nav, R.id.nav_netdiag, NetworkDiagnosticsActivity.class);
            assertRowStartsScreen(activity, nav, R.id.nav_replay, ReplayCheckActivity.class);
            assertRowStartsScreen(activity, nav, R.id.nav_online, GeneralsOnlineActivity.class);

            // nav_folder is asserted for presence and label only: on API >= R its handler reads
            // Environment.isExternalStorageManager() first, and Robolectric does not implement that
            // call -- it throws from android.os.Environment, inside production code this test cannot
            // stub. See the harness note at the top of the file.
            MenuItem folder = nav.getMenu().findItem(R.id.nav_folder);
            assertNotNull("drawer menu has no Game Folder item", folder);
            assertEquals("the Game Folder drawer item has the wrong label",
                activity.getString(R.string.nav_drawer_game_folder), String.valueOf(folder.getTitle()));
            assertNotNull("the drawer shows no Game Folder row",
                rowFor(nav, folder.getTitle()));

            // Coming back (onResume runs the refresh* methods against the current page, and the
            // drawer listener re-marks the selected item) must leave that page intact.
            controller.pause().resume();
            layOut(activity);
            assertEquals("the page changed while a child screen was in front",
                activity.getString(R.string.nav_tab_interface), appBarTitleText(activity));
            assertTrue("the page is no longer on screen after coming back",
                firstCardInPageHost(activity) != null);
        } finally {
            controller.close();
        }
    }

    @Test
    public void rotationKeepsThePageAndARecreateRestoresIt() {
        ActivityController<SetupActivity> controller = Robolectric.buildActivity(SetupActivity.class);
        SetupActivity activity = controller.setup().get();
        Bundle state;
        try {
            layOut(activity);
            assertRowShowsPage(activity, navigationOf(drawerOf(activity).getChildAt(0)),
                R.id.nav_interface, R.string.nav_tab_interface);

            // SetupActivity declares orientation|screenSize|keyboardHidden, so a rotation does not
            // rebuild it; the framework calls onConfigurationChanged on the same instance.
            Configuration landscape = new Configuration(activity.getResources().getConfiguration());
            landscape.orientation = Configuration.ORIENTATION_LANDSCAPE;
            activity.onConfigurationChanged(landscape);
            layOut(activity);
            assertEquals("rotating changed the page", activity.getString(R.string.nav_tab_interface),
                appBarTitleText(activity));
            assertTrue("the page is not on screen after a rotation",
                firstCardInPageHost(activity) != null);

            state = new Bundle();
            controller.saveInstanceState(state);
        } finally {
            controller.pause().stop().destroy();
        }

        // A recreate (memory pressure, or a change the activity does not declare) must restore the
        // page the user was on rather than dropping them back on Home.
        ActivityController<SetupActivity> recreated =
            Robolectric.buildActivity(SetupActivity.class).setup(state);
        SetupActivity second = recreated.get();
        try {
            layOut(second);
            assertEquals("the page was not restored after a recreate",
                second.getString(R.string.nav_tab_interface), appBarTitleText(second));
        } finally {
            recreated.close();
        }

        // And a cold start still lands on Home.
        ActivityController<SetupActivity> cold = Robolectric.buildActivity(SetupActivity.class).setup();
        try {
            layOut(cold.get());
            assertEquals("a cold start did not land on the home page",
                cold.get().getString(R.string.nav_tab_home), appBarTitleText(cold.get()));
        } finally {
            cold.close();
        }
    }

    @Test
    public void switchingGamesRebuildsTheShellAndInflatesTheMenuOnce() {
        ActivityController<SetupActivity> controller = Robolectric.buildActivity(SetupActivity.class);
        SetupActivity activity = controller.setup().get();
        try {
            layOut(activity);
            // The app bar's overline names the game the dashboard is showing.
            assertEquals(activity.getString(R.string.setup_title), appBarOverlineText(activity));
            int itemsBefore = navigationOf(drawerOf(activity).getChildAt(0)).getMenu().size();
            assertTrue("the drawer started with " + itemsBefore + " items", itemsBefore >= 11);

            // What the dashboard's game selector does: set the pref and rebuild the whole shell.
            SetupActivity.setSelectedGame(activity, SetupActivity.GAME_GENERALS);
            invokePrivate(activity, "buildUi", new Class<?>[0]);
            layOut(activity);
            assertEquals("switching to Generals did not change the app bar",
                activity.getString(R.string.setup_title_generals), appBarOverlineText(activity));
            assertTrue("the home page is not on screen after switching games",
                firstCardInPageHost(activity) != null);
            NavigationView rebuilt = navigationOf(drawerOf(activity).getChildAt(0));
            assertEquals("rebuilding the shell left " + rebuilt.getMenu().size()
                + " drawer items instead of " + itemsBefore
                + ": the menu is inflated more than once per NavigationView",
                itemsBefore, rebuilt.getMenu().size());

            // And back, so the shared pref is left the way it was found.
            SetupActivity.setSelectedGame(activity, SetupActivity.GAME_ZERO_HOUR);
            invokePrivate(activity, "buildUi", new Class<?>[0]);
            layOut(activity);
            assertEquals("switching back to Zero Hour did not change the app bar",
                activity.getString(R.string.setup_title), appBarOverlineText(activity));
        } finally {
            controller.close();
        }
    }

    @Test
    public void theDashboardLeadsWithTheGameCardThenOnlineThenUpdates() {
        ActivityController<SetupActivity> controller = Robolectric.buildActivity(SetupActivity.class);
        SetupActivity activity = controller.setup().get();
        try {
            layOut(activity);
            View host = pageHostOrNull(activity);
            List<MaterialCardView> cards = new ArrayList<>();
            collect(host, MaterialCardView.class, cards);
            assertEquals("the dashboard has " + cards.size() + " cards; it is meant to lead with "
                + "the game card, then Generals Online, then the brief updates line, and nothing else",
                3, cards.size());
            assertTrue("the game card is not above the Online section",
                absoluteTop(cards.get(0)) < absoluteTop(cards.get(1)));
            assertTrue("the Online section is not above the updates section",
                absoluteTop(cards.get(1)) < absoluteTop(cards.get(2)));

            // The main card answers "which game, is it playable, where is it": the selected game's
            // name, the launch button, the folder action, and both games' names for the switch.
            assertNotNull("the main card does not name the selected game",
                textOnScreen(cards.get(0), HEIGHT_PX, activity.getString(R.string.setup_game_zero_hour)));
            Button play = buttonWithText(cards.get(0), activity.getString(R.string.setup_button_launch_game));
            assertNotNull("the dashboard has no launch button on screen", play);
            assertTrue("the launch button is off screen: " + absoluteTop(play), absoluteTop(play) < HEIGHT_PX);
            assertNotNull("the launch button is not wired to the launch path", listenerOf(play));
            assertNotNull("the main card has no way to change the game folder",
                buttonWithText(cards.get(0), activity.getString(R.string.setup_button_select_game_folder)));
            assertNotNull("the main card does not offer Generals",
                textOnScreen(cards.get(0), HEIGHT_PX, activity.getString(R.string.setup_game_generals)));

            // Generals Online sits on the dashboard itself, with its real sign-in line (no invented
            // status: refreshGeneralsOnlineStatus() reads the account state) and a button into the
            // screen that owns it.
            Button online = buttonWithText(cards.get(1), activity.getString(R.string.setup_button_online_account));
            assertNotNull("the dashboard has no Generals Online section", online);
            assertTrue("the Generals Online button is off screen", absoluteTop(online) < HEIGHT_PX);
            online.performClick();
            Intent started = anyStartedIntent(activity);
            assertNotNull("the Generals Online button starts nothing", started);
            assertNotNull(started.getComponent());
            assertEquals(GeneralsOnlineActivity.class.getName(), started.getComponent().getClassName());

            // The updates card is the brief version: a status line and its controls, not a page.
            assertNotNull("the dashboard does not show the updates section",
                textOnScreen(cards.get(2), HEIGHT_PX, activity.getString(R.string.setup_card_updates)));
        } finally {
            controller.close();
        }
    }

    @Test
    public void theDrawerOpensTheGameFolderPageWithItsActions() {
        ActivityController<SetupActivity> controller = Robolectric.buildActivity(SetupActivity.class);
        SetupActivity activity = controller.setup().get();
        try {
            layOut(activity);
            NavigationView nav = navigationOf(drawerOf(activity).getChildAt(0));

            // The drawer entry is a page, not a one-shot action: it has the folder work the dashboard
            // used to stack under the launch card.
            assertRowShowsPage(activity, nav, R.id.nav_folder, R.string.nav_drawer_game_folder);
            View host = pageHostOrNull(activity);
            assertNotNull("the Game folder destination attached no page", host);
            assertNotNull("the Game folder page cannot change the folder",
                buttonWithText(host, activity.getString(R.string.setup_button_select_game_folder)));
            assertNotNull("the Game folder page cannot clear the folder",
                buttonWithText(host, activity.getString(R.string.setup_button_clear_game_folder)));
            // Zero Hour is the default game here, so the base-game folder row belongs on this page.
            assertNotNull("the Game folder page cannot pick the base-game folder for Zero Hour",
                buttonWithText(host, activity.getString(R.string.setup_button_select_base_generals)));
        } finally {
            controller.close();
        }
    }

    @Test
    public void theDrawerListsItsSectionsInTheRequestedOrder() {
        ActivityController<SetupActivity> controller = Robolectric.buildActivity(SetupActivity.class);
        SetupActivity activity = controller.setup().get();
        try {
            layOut(activity);
            NavigationView nav = navigationOf(drawerOf(activity).getChildAt(0));
            Menu menu = nav.getMenu();

            // GeneralsX @tweak Android port ui-organisation 16/10/2026 Storage before how it is
            // rendered and how it looks, and the Tools page is an entry of its own now (it had
            // none, so the drawer highlighted "View logs" -- a different screen -- whenever it was
            // opened from the bottom rail).
            int[] expected = {
                R.id.nav_home, R.id.nav_settings_header, R.id.nav_folder, R.id.nav_graphics,
                R.id.nav_interface, R.id.nav_tools_header, R.id.nav_tools, R.id.nav_logs,
                R.id.nav_netdiag, R.id.nav_replay, R.id.nav_online, R.id.nav_help
            };
            assertEquals("the drawer has " + menu.size() + " items instead of " + expected.length,
                expected.length, menu.size());
            for (int i = 0; i < expected.length; i++) {
                assertEquals("drawer item " + i + " is " + idName(menu.getItem(i).getItemId())
                    + ", expected " + idName(expected[i]), expected[i], menu.getItem(i).getItemId());
            }
            // Both section headers are labels, not destinations.
            assertFalse(menu.findItem(R.id.nav_settings_header).isCheckable());
            assertFalse(menu.findItem(R.id.nav_tools_header).isCheckable());
            for (int id : DESTINATIONS) {
                assertTrue("drawer destination " + idName(id) + " is not checkable",
                    menu.findItem(id).isCheckable());
            }
        } finally {
            controller.close();
        }
    }

    @Test
    public void backClosesTheOpenDrawerBeforeLeavingTheApp() {
        ActivityController<SetupActivity> controller = Robolectric.buildActivity(SetupActivity.class);
        SetupActivity activity = controller.setup().get();
        try {
            layOut(activity);
            DrawerLayout drawer = drawerOf(activity);
            NavigationView nav = navigationOf(drawerOf(activity).getChildAt(0));
            drawer.openDrawer(nav, false);
            assertTrue("the drawer did not open", absoluteLeft(nav) == 0);

            activity.onBackPressed();
            settle();
            // Back has to take the drawer branch (close it and return) rather than the app branch:
            // the activity not finishing is what that branch looks like from out here. The panel's
            // slide back off screen is the animated path, which this harness does not run (see the
            // note at the top of the file).
            assertFalse("Back left the app while the drawer was open", activity.isFinishing());

            // With the drawer closed, Back is the app's own behaviour again. Closed has to be set
            // up directly: the Back above asked the drawer to close with the animated path, which
            // never runs here, so the drawer still reports itself open.
            drawer.closeDrawer(nav, false);
            activity.onBackPressed();
            assertTrue("Back with the drawer closed should leave the launcher", activity.isFinishing());
        } finally {
            controller.close();
        }
    }

    @Test
    public void theSettingsPagesKeepTheirSectionsAndEngineOptions() {
        ActivityController<SetupActivity> controller = Robolectric.buildActivity(SetupActivity.class);
        SetupActivity activity = controller.setup().get();
        try {
            layOut(activity);
            NavigationView nav = navigationOf(drawerOf(activity).getChildAt(0));

            // Graphics keeps the simulation rate and the render backend (Vulkan/GLES/ANGLE), which
            // is what "the engine options are preserved" means on screen. These pages scroll, so
            // the sections are searched in the laid-out page rather than in the visible viewport:
            // what is being checked is that the settings are still there, not where the reader is.
            assertRowShowsPage(activity, nav, R.id.nav_graphics, R.string.nav_tab_graphics);
            View host = pageHostOrNull(activity);
            assertNotNull(activity.getString(R.string.setup_card_sim_rate),
                textAnywhere(host, activity.getString(R.string.setup_card_sim_rate)));
            assertNotNull(activity.getString(R.string.setup_card_render_backend),
                textAnywhere(host, activity.getString(R.string.setup_card_render_backend)));

            // Interface keeps language, text size, interface scale and controls.
            assertRowShowsPage(activity, nav, R.id.nav_interface, R.string.nav_tab_interface);
            host = pageHostOrNull(activity);
            for (int label : new int[] { R.string.setup_card_language, R.string.setup_card_text_size,
                    R.string.setup_card_interface_scale, R.string.setup_card_controls }) {
                assertNotNull("the Interface page lost \"" + activity.getString(label) + "\"",
                    textAnywhere(host, activity.getString(label)));
            }
        } finally {
            controller.close();
        }
    }

    @Test
    public void eachGameKeepsItsOwnFolderWhenTheSelectionChanges() {
        ActivityController<SetupActivity> controller = Robolectric.buildActivity(SetupActivity.class);
        SetupActivity activity = controller.setup().get();
        try {
            // The two games must never share a folder preference: Zero Hour keeps the original key
            // so existing installs do not lose their folder, and Generals gets its own.
            assertNotEquals("the two games share one folder preference key",
                SetupActivity.PREF_GAME_PATH, SetupActivity.PREF_GENERALS_GAME_PATH);

            SharedPreferences prefs =
                activity.getSharedPreferences(SetupActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE);
            prefs.edit()
                .putString(SetupActivity.PREF_GAME_PATH, "/zh/folder")
                .putString(SetupActivity.PREF_GENERALS_GAME_PATH, "/generals/folder")
                .commit();
            assertEquals("/zh/folder",
                SetupActivity.getSavedGamePath(activity, SetupActivity.GAME_ZERO_HOUR));
            assertEquals("/generals/folder",
                SetupActivity.getSavedGamePath(activity, SetupActivity.GAME_GENERALS));

            // Switching which game the dashboard shows must follow the selection, not leak one
            // game's folder into the other's launch path -- Play uses getSavedGamePath().
            SetupActivity.setSelectedGame(activity, SetupActivity.GAME_GENERALS);
            assertEquals("/generals/folder", SetupActivity.getSavedGamePath(activity));
            SetupActivity.setSelectedGame(activity, SetupActivity.GAME_ZERO_HOUR);
            assertEquals("/zh/folder", SetupActivity.getSavedGamePath(activity));
        } finally {
            controller.close();
        }
    }

    @Test
    public void theFallbackUiIsReachableUsableAndSurvivesTheFailure() {
        ActivityController<SetupActivity> controller = Robolectric.buildActivity(SetupActivity.class);
        SetupActivity activity = controller.setup().get();
        try {
            // The shell's own catch block calls this, and only a Throwable can reach it: build it the
            // way onCreate() does when buildUi() throws.
            invokePrivate(activity, "buildFallbackUi", new Class<?>[] { Throwable.class },
                new IllegalStateException("synthetic shell failure"));
            layOut(activity);
            dump(activity.getWindow().getDecorView(), 0);

            View root = ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
            assertTrue("the fallback UI is not the plain scroll root, it is "
                + root.getClass().getSimpleName(), root instanceof ScrollView);
            assertNull("the fallback UI still has a page host, so showTab() would write into a "
                + "view that is not on screen", pageHostOrNull(activity));

            // The failure has to be legible, or the fallback is as useless as a blank screen.
            assertTrue("the fallback does not show the failure text",
                textOnScreen(root, HEIGHT_PX, "synthetic shell failure") != null);

            // ...and the screen still has to do the four things it exists for, using plain widgets:
            // the whole point is that a Material/theme failure cannot reach them.
            List<Button> buttons = new ArrayList<>();
            collect(root, Button.class, buttons);
            assertTrue("the fallback has only " + buttons.size() + " buttons", buttons.size() >= 4);
            for (Button b : buttons) {
                assertFalse("the fallback UI uses a MaterialButton ("
                    + String.valueOf(b.getText()) + "), which can fail for the same reason the "
                    + "fallback was reached", b instanceof MaterialButton);
                assertTrue("a fallback button is off screen: " + absoluteTop(b), absoluteTop(b) < HEIGHT_PX);
                assertNotNull("a fallback button has no click listener", listenerOf(b));
            }
            assertNotNull("the fallback cannot reach the log viewer",
                buttonWithText(root, activity.getString(R.string.setup_button_view_logs)));
            assertNotNull("the fallback cannot launch the game",
                buttonWithText(root, activity.getString(R.string.setup_button_launch_game)));
            assertNotNull("the fallback cannot pick the game folder",
                buttonWithText(root, activity.getString(R.string.setup_button_select_game_folder)));

            // The original failure must not break what comes next: the tab API is a documented no-op
            // with no page host, and the resume/refresh path has to survive.
            invokePrivate(activity, "showTab", new Class<?>[] { int.class }, TAB_HOME);
            activity.onResume();
            Configuration landscape = new Configuration(activity.getResources().getConfiguration());
            landscape.orientation = Configuration.ORIENTATION_LANDSCAPE;
            activity.onConfigurationChanged(landscape);
            layOut(activity);
            assertTrue("the fallback UI did not stay up", firstCardInPageHost(activity) == null);
            assertNotNull("the fallback's failure text disappeared",
                textOnScreen(root, HEIGHT_PX, "synthetic shell failure"));
        } finally {
            controller.close();
        }
    }

    // ------------------------------------------------------------------ assertions

    /** Clicks a destination's row and asserts the visible outcome, not just the click. */
    private void assertRowShowsPage(SetupActivity activity, NavigationView nav, int id, int titleRes) {
        MenuItem item = nav.getMenu().findItem(id);
        assertNotNull("drawer menu has no item " + idName(id), item);
        clickRow(nav, item.getTitle(), idName(id));
        assertEquals("drawer destination " + idName(id) + " did not switch the page title",
            activity.getString(titleRes), appBarTitleText(activity));
        View card = firstCardInPageHost(activity);
        assertTrue("drawer destination " + idName(id) + " attached no page", card != null);
        assertTrue("the page for " + idName(id) + " is below the bottom edge",
            absoluteTop(card) < HEIGHT_PX);
        assertEquals("the drawer does not mark " + idName(id) + " as the current item",
            id, nav.getCheckedItem().getItemId());
    }

    private void assertRowStartsScreen(SetupActivity activity, NavigationView nav, int id,
                                       Class<?> expected) {
        MenuItem item = nav.getMenu().findItem(id);
        assertNotNull("drawer menu has no item " + idName(id), item);
        clickRow(nav, item.getTitle(), idName(id));
        Intent started = anyStartedIntent(activity);
        assertNotNull("selecting " + idName(id) + " started no screen", started);
        assertNotNull("selecting " + idName(id) + " started an intent with no component",
            started.getComponent());
        assertEquals("selecting " + idName(id) + " opened the wrong screen",
            expected.getName(), started.getComponent().getClassName());
    }

    /** Taps the row labelled {@code label} the way a user does -- through the row view itself. */
    private void clickRow(NavigationView nav, CharSequence label, String what) {
        View row = rowFor(nav, label);
        assertNotNull("the drawer shows no row for " + what + " (\"" + label + "\")", row);
        assertTrue("tapping the " + what + " row reported no click", row.performClick());
        // The destination closes the drawer, and the close listener is what re-marks the current
        // item (see buildDrawerShell); let that animation and listener run before asserting.
        settle();
    }

    // ------------------------------------------------------------------ helpers

    private static void layOut(SetupActivity activity) {
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT_PX, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, WIDTH_PX, HEIGHT_PX);
    }

    private static DrawerLayout drawerOf(SetupActivity activity) {
        View content = activity.findViewById(android.R.id.content);
        assertNotNull("no content view at all", content);
        View root = ((ViewGroup) content).getChildAt(0);
        assertNotNull("the activity's content view has no child", root);
        if (!(root instanceof DrawerLayout)) {
            // buildUi() is wrapped in a try/catch that swaps in the plain-widget fallback, so a
            // ScrollView here means the drawer shell threw while being built -- name it.
            fail("the content view is a " + root.getClass().getSimpleName()
                + ", not the DrawerLayout the redesign builds: buildUi() fell back");
        }
        return (DrawerLayout) root;
    }

    /** The page host (the shell's weighted FrameLayout), or null when there is none. */
    private static View pageHostOrNull(SetupActivity activity) {
        View content = activity.findViewById(android.R.id.content);
        if (content == null) {
            return null;
        }
        View root = ((ViewGroup) content).getChildAt(0);
        if (!(root instanceof DrawerLayout)) {
            return null;
        }
        View shell = ((DrawerLayout) root).getChildAt(0);
        if (!(shell instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) shell;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (group.getChildAt(i) instanceof FrameLayout) {
                return group.getChildAt(i);
            }
        }
        return null;
    }

    /** The first card of the page currently attached to the page host, or null. */
    private static View firstCardInPageHost(SetupActivity activity) {
        View host = pageHostOrNull(activity);
        if (host == null) {
            return null;
        }
        List<MaterialCardView> cards = new ArrayList<>();
        collect(host, MaterialCardView.class, cards);
        return cards.isEmpty() ? null : cards.get(0);
    }

    /** The app bar's trailing button (the drawer opener). */
    private static View appBarButton(View shell) {
        View bar = ((ViewGroup) shell).getChildAt(0);
        if (!(bar instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) bar;
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            if (group.getChildAt(i) instanceof ImageView) {
                return group.getChildAt(i);
            }
        }
        return null;
    }

    private static NavigationView navigationOf(View shell) {
        View parent = ((View) shell).getParent() instanceof View
            ? (View) ((View) shell).getParent() : shell;
        List<NavigationView> found = new ArrayList<>();
        collect(parent, NavigationView.class, found);
        return found.isEmpty() ? null : found.get(0);
    }

    /** The label view of a drawer row, whether or not the row is a selectable destination. */
    private static TextView labelView(View nav, CharSequence label) {
        List<View> all = new ArrayList<>();
        collect(nav, View.class, all);
        for (View v : all) {
            if (v instanceof TextView) {
                CharSequence text = ((TextView) v).getText();
                if (text != null && text.toString().contentEquals(label)) {
                    return (TextView) v;
                }
            }
        }
        return null;
    }

    /**
     * The clickable drawer row whose label is {@code label}. The label belongs to a TextView inside
     * the row, so the clickable ancestor is what a tap actually hits.
     */
    private static View rowFor(View nav, CharSequence label) {
        List<View> all = new ArrayList<>();
        collect(nav, View.class, all);
        for (View v : all) {
            if (!(v instanceof TextView)) {
                continue;
            }
            CharSequence text = ((TextView) v).getText();
            if (text == null || !text.toString().contentEquals(label)) {
                continue;
            }
            View clickable = v;
            while (clickable != null && !clickable.isClickable()) {
                clickable = clickable.getParent() instanceof View
                    ? (View) clickable.getParent() : null;
            }
            if (clickable != null) {
                return clickable;
            }
        }
        return null;
    }

    /** The app bar's title: the last text line of the first shell child. */
    private static String appBarTitleText(SetupActivity activity) {
        View shell = drawerOf(activity).getChildAt(0);
        View bar = ((ViewGroup) shell).getChildAt(0);
        List<TextView> texts = new ArrayList<>();
        collect(bar, TextView.class, texts);
        assertTrue("the app bar has no title", !texts.isEmpty());
        return String.valueOf(texts.get(texts.size() - 1).getText());
    }

    /** The app bar's overline: the muted line above the title, which names the selected game. */
    private static String appBarOverlineText(SetupActivity activity) {
        View shell = drawerOf(activity).getChildAt(0);
        View bar = ((ViewGroup) shell).getChildAt(0);
        List<TextView> texts = new ArrayList<>();
        collect(bar, TextView.class, texts);
        assertTrue("the app bar has no overline", texts.size() > 1);
        return String.valueOf(texts.get(0).getText());
    }

    private static void invokePrivate(Object target, String name, Class<?>[] types, Object... args) {
        try {
            Method method = target.getClass().getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not call " + name + " on " + target.getClass(), e);
        }
    }

    /** Runs the drawer's animations and the listeners they fire when they end. */
    private static void settle() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
    }

    /**
     * The next screen this activity asked for, from either the started or for-result queue, removed
     * from that queue: peeking would make every later assertion see the first screen again.
     */
    private static Intent anyStartedIntent(SetupActivity activity) {
        ShadowActivity shadow = Shadows.shadowOf(activity);
        Intent started = shadow.getNextStartedActivity();
        if (started != null) {
            return started;
        }
        ShadowActivity.IntentForResult forResult = shadow.getNextStartedActivityForResult();
        return forResult == null ? null : forResult.intent;
    }

    private static Object listenerOf(View b) {
        try {
            java.lang.reflect.Field field = View.class.getDeclaredField("mListenerInfo");
            field.setAccessible(true);
            Object info = field.get(b);
            if (info == null) {
                return null;
            }
            java.lang.reflect.Field click = info.getClass().getDeclaredField("mOnClickListener");
            click.setAccessible(true);
            return click.get(info);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not read the click listener", e);
        }
    }

    private static Button buttonWithText(View v, String label) {
        List<Button> buttons = new ArrayList<>();
        collect(v, Button.class, buttons);
        for (Button b : buttons) {
            if (label.contentEquals(b.getText())) {
                return b;
            }
        }
        return null;
    }

    // Comparisons, not a switch: AGP 8 generates non-final R fields, which cannot be case labels.
    private static String idName(int id) {
        if (id == R.id.nav_home)         return "nav_home";
        if (id == R.id.nav_folder)       return "nav_folder";
        if (id == R.id.nav_graphics)     return "nav_graphics";
        if (id == R.id.nav_interface)    return "nav_interface";
        if (id == R.id.nav_tools)        return "nav_tools";
        if (id == R.id.nav_logs)         return "nav_logs";
        if (id == R.id.nav_netdiag)      return "nav_netdiag";
        if (id == R.id.nav_replay)       return "nav_replay";
        if (id == R.id.nav_online)       return "nav_online";
        if (id == R.id.nav_help)         return "nav_help";
        if (id == R.id.nav_tools_header) return "nav_tools_header";
        return "id " + id;
    }

    /** Depth-first dump: class, visibility, geometry and background -- the evidence to read. */
    private static void dump(View v, int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        sb.append(v.getClass().getSimpleName())
            .append(" vis=").append(v.getVisibility())
            .append(" ").append(v.getWidth()).append("x").append(v.getHeight())
            .append(" abs=").append(absoluteTop(v)).append(',').append(absoluteLeft(v))
            .append(v.getBackground() != null ? " bg=" + v.getBackground().getClass().getSimpleName() : "");
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.length() > 0) {
                sb.append(" text=\"").append(t.length() > 40 ? t.subSequence(0, 40) + "…" : t).append('"');
            }
        }
        System.out.println("[shell-layout] " + sb);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                dump(g.getChildAt(i), depth + 1);
            }
        }
    }

    /** Absolute Y of a view, by walking up the (already laid out) parent chain. */
    private static int absoluteTop(View v) {
        int top = 0;
        View cur = v;
        while (cur != null) {
            top += cur.getTop();
            cur = (cur.getParent() instanceof View) ? (View) cur.getParent() : null;
        }
        return top;
    }

    /** Absolute X of a view, the same way -- used to tell an open drawer from a hidden one. */
    private static int absoluteLeft(View v) {
        int left = 0;
        View cur = v;
        while (cur != null) {
            left += cur.getLeft();
            cur = (cur.getParent() instanceof View) ? (View) cur.getParent() : null;
        }
        return left;
    }

    private static boolean onScreenY(View v, int screenHeight) {
        return v.getVisibility() == View.VISIBLE
            && v.getWidth() > 0 && v.getHeight() > 0
            && absoluteTop(v) < screenHeight
            && absoluteTop(v) + v.getHeight() > 0;
    }

    private static boolean onScreenX(View v, int screenWidth) {
        return absoluteLeft(v) < screenWidth && absoluteLeft(v) + v.getWidth() > 0;
    }

    private static int countVisibleTextOnScreen(View v, int screenHeight) {
        int n = 0;
        if (v instanceof TextView && onScreenY(v, screenHeight)) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.length() > 0) {
                n++;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                n += countVisibleTextOnScreen(g.getChildAt(i), screenHeight);
            }
        }
        return n;
    }

    /** On-screen text in both axes -- the drawer is judged by where its rows sit horizontally. */
    private static int countVisibleTextInArea(View v, int screenWidth, int screenHeight) {
        int n = 0;
        if (v instanceof TextView && onScreenY(v, screenHeight) && onScreenX(v, screenWidth)) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.length() > 0) {
                n++;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                n += countVisibleTextInArea(g.getChildAt(i), screenWidth, screenHeight);
            }
        }
        return n;
    }

    /** The n-th on-screen non-empty text, depth-first (1 = the first found). */
    private static TextView firstVisibleTextOnScreen(View v, int screenHeight, int nth) {
        List<TextView> found = new ArrayList<>();
        collectText(v, screenHeight, found);
        return found.size() >= nth ? found.get(nth - 1) : null;
    }

    /** The first on-screen text whose content contains {@code needle}, or null. */
    private static TextView textOnScreen(View v, int screenHeight, String needle) {
        List<TextView> found = new ArrayList<>();
        collectText(v, screenHeight, found);
        for (TextView t : found) {
            if (String.valueOf(t.getText()).contains(needle)) {
                return t;
            }
        }
        return null;
    }

    /** The first laid-out text whose content contains {@code needle}, on screen or scrolled below. */
    private static TextView textAnywhere(View v, String needle) {
        List<TextView> found = new ArrayList<>();
        collect(v, TextView.class, found);
        for (TextView t : found) {
            if (String.valueOf(t.getText()).contains(needle)) {
                return t;
            }
        }
        return null;
    }

    private static void collectText(View v, int screenHeight, List<TextView> out) {
        if (v instanceof TextView && onScreenY(v, screenHeight)) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.length() > 0) {
                out.add((TextView) v);
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectText(g.getChildAt(i), screenHeight, out);
            }
        }
    }

    private static <T extends View> void collect(View v, Class<T> type, List<T> out) {
        if (type.isInstance(v)) {
            out.add(type.cast(v));
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collect(g.getChildAt(i), type, out);
            }
        }
    }
}
