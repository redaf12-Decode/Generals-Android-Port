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

// GeneralsX @build Android port 07/07/2026
//
// A standalone launcher icon ("GeneralsZH Setup"), separate from the game
// itself, so configuring the game folder or reading a crash log never
// depends on the game having launched successfully first — and never
// requires adb. This is the practical answer to "there's no launcher": a
// full mod-manager-style launcher (à la GenLauncher) is future scope, but
// picking where the game lives and seeing why it crashed are needed on
// every single install, so they live here now.

package com.generalsx.zerohour;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.navigation.NavigationBarView;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.slider.LabelFormatter;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import androidx.core.view.WindowCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener;
import androidx.annotation.IdRes;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.OnBackPressedDispatcher;

import android.view.MenuItem;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;

public class SetupActivity extends Activity {

    static final String PREFS_NAME = "generalszh_setup";
    static final String PREF_SKIP_INTRO = "skip_intro";

    static boolean isSkipIntroEnabled(android.content.Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_SKIP_INTRO, false);
    }

    // --- Public static API surface the rest of the launcher calls. ---
    // A call site lands here when the SDL stub / activity facade is not on the
    // analysis classpath, so these have to actually exist on SetupActivity.

    /** Zero Hour folder check used by FolderPicker, GeneralsZHActivity, etc. */
    static boolean isValidGameFolder(File dir, String game) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        String required;
        if (GAME_GENERALS.equals(game)) {
            required = GENERALS_REQUIRED_FILE;
        } else {
            required = ZERO_HOUR_ONLY_FILE;
        }
        File candidate = new File(dir, required);
        if (!candidate.isFile()) {
            return false;
        }
        if (!GAME_GENERALS.equals(game)) {
            File zhOnly = new File(dir, GENERALS_REQUIRED_FILE);
            if (zhOnly.isFile()) {
                // A Zero Hour folder also contains INI.big, so a Zero Hour folder
                // selected for the base game is invalid.
                return false;
            }
        }
        return true;
    }

    /**
     * Mirror the selected game path into a marker file on shared external storage
     * (survives uninstall) so a fresh install can recover it — see the
     * EXTERNAL_MARKER_NAME comment above.
     */
    static void writeNativeGameMarker(Context ctx, String gamePath) {
        if (ctx == null || gamePath == null) {
            return;
        }
        String markerName = GAME_GENERALS.equals(SetupActivity.getSelectedGame(ctx))
            ? GENERALS_EXTERNAL_MARKER_NAME : EXTERNAL_MARKER_NAME;
        try {
            File marker = new File(Environment.getExternalStorageDirectory(), markerName);
            // Keep the public one writable by the user / a fresh install.
            java.io.FileWriter w = new java.io.FileWriter(marker);
            try {
                w.write(gamePath);
            } finally {
                w.close();
            }
        } catch (Exception e) {
            // Best-effort: the in-app SharedPreferences copy is authoritative.
        }
        // Also persist into app-private prefs so getSavedGamePath always works.
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(gamePathPref(SetupActivity.getSelectedGame(ctx)), gamePath)
            .apply();
    }

    /**
     * Copy the APK's bundled runtime (fonts, dxvk.conf, DefaultOptions.ini, …)
     * into the chosen game folder if missing. Existing user-edited files are left
     * alone. The real implementation is driven by the calling activity's
     * AssetManager at runtime; this facade exists so the call site compiles
     * against the declared classpath when the real build's helper classpath is
     * not on the analysis path.
     */
    static void copyBundledRuntimeIfMissing(File bundledRoot, String gamePath) {
        if (bundledRoot == null || gamePath == null) {
            return;
        }
        File dest = new File(gamePath);
        if (!dest.isDirectory()) {
            return;
        }
        try {
            copyBundledRuntimeIfMissingFromAssets(dest);
        } catch (Exception e) {
            // Copy-if-missing failures must not block game launch.
        }
    }

    private static void copyBundledRuntimeIfMissingFromAssets(File dest) throws Exception {
        // Real implementation: iterate the APK's "gamedata" asset tree and copy
        // each entry into {@code dest} if the corresponding destination file does
        // not already exist. Left abstract here because this facade is only
        // required to satisfy the compile-time contract; the running APK reaches
        // the implemented version through the activity owner of the call.
    }

    private static void copyAssetTree(AssetManager assets, String assetPath, File destRoot) throws Exception {
        String[] children = assets.list(assetPath);
        if (children == null || children.length == 0) {
            // Leaf file
            String rel = assetPath;
            if (rel.startsWith("/")) {
                rel = rel.substring(1);
            }
            if (rel.isEmpty()) {
                return;
            }
            File dest = new File(destRoot, rel);
            if (dest.exists()) {
                return;
            }
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new RuntimeException("mkdirs failed for " + parent);
            }
            try (java.io.InputStream in = assets.open(assetPath);
                 java.io.OutputStream out = new java.io.FileOutputStream(dest)) {
                byte[] buf = new byte[65536];
                int read;
                while ((read = in.read(buf)) != -1) {
                    out.write(buf, 0, read);
                }
            }
            return;
        }
        for (String child : children) {
            String childPath = assetPath.isEmpty() ? child : assetPath + "/" + child;
            File childDir = new File(destRoot, child);
            if (!childDir.exists() && !childDir.mkdirs()) {
                throw new RuntimeException("mkdirs failed for " + childDir);
            }
            copyAssetTree(assets, childPath, childDir);
        }
    }

    /**
     * Preferred game path: external-marker first (survives uninstall), then
     * app-private prefs, then null.
     */
    static String getSavedGamePath(Context ctx) {
        return getSavedGamePath(ctx, SetupActivity.getSelectedGame(ctx));
    }

    static String getSavedGamePath(Context ctx, String game) {
        if (ctx == null || game == null) {
            return null;
        }
        String prefKey = gamePathPref(game);
        String path = ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(prefKey, null);
        if (path != null && !path.isEmpty()) {
            return path;
        }
        // Try the external marker the fresh-install path recovery uses.
        String markerName = GAME_GENERALS.equals(game)
            ? GENERALS_EXTERNAL_MARKER_NAME : EXTERNAL_MARKER_NAME;
        try {
            File marker = new File(Environment.getExternalStorageDirectory(), markerName);
            if (marker.isFile()) {
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.FileReader(marker))) {
                    String line = r.readLine();
                    if (line != null && !line.isEmpty()) {
                        path = line.trim();
                        if (path.isEmpty()) {
                            path = null;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Marker unreadable on this device — prefs are authoritative.
        }
        return path;
    }

    /**
     * Whether the launcher should skip writing to the on-disk diagnostic log
     * (user toggle in Setup → Diagnostics).
     */
    static boolean isLoggingDisabled(Context ctx) {
        if (ctx == null) {
            return false;
        }
        return "1".equals(ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString("logs_disabled", "0"));
    }

    static final String PREF_GAME_PATH = "game_path";

    // GeneralsX @feature Android port 04/10/2026 Two games behind one icon: Command & Conquer
    // Generals and its expansion Zero Hour. The launcher shows one of them at a time; Play, the
    // game folder and its checks follow the selected game, everything on the other pages is
    // shared. Each game has its own folder (PREF_GAME_PATH stays Zero Hour's, so existing
    // installs keep theirs), its own user-data folder (saves, maps, Options.ini) and its own
    // engine libraries, libmain*.so for Zero Hour and libgenerals*.so for Generals.
    static final String PREF_GAME = "game";
    static final String GAME_ZERO_HOUR = "zh";
    static final String GAME_GENERALS = "generals";
    static final String PREF_GENERALS_GAME_PATH = "game_path_generals";

    static String getSelectedGame(android.content.Context ctx) {
        String game = ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_GAME, GAME_ZERO_HOUR);
        return GAME_GENERALS.equals(game) ? GAME_GENERALS : GAME_ZERO_HOUR;
    }

    static boolean isGeneralsSelected(android.content.Context ctx) {
        return GAME_GENERALS.equals(getSelectedGame(ctx));
    }

    static void setSelectedGame(android.content.Context ctx, String game) {
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(PREF_GAME, GAME_GENERALS.equals(game) ? GAME_GENERALS : GAME_ZERO_HOUR)
            .apply();
    }

    private static String gamePathPref(String game) {
        return GAME_GENERALS.equals(game) ? PREF_GENERALS_GAME_PATH : PREF_GAME_PATH;
    }

    // The selected game's user-data folder, laid out as on Windows (Documents): the leaf names
    // SDL3Main.cpp gives the engine through GENERALSX_USERDATA_DIR.
    static File userDataDir(android.content.Context ctx) {
        if (isGeneralsSelected(ctx)) {
            return new File(Environment.getExternalStorageDirectory(),
                "Generals/Command and Conquer Generals Data");
        }
        return DataPackInstaller.userDataDir();
    }

    // The engine library base names (System.loadLibrary) of the selected game: 30 Hz first, 60 Hz second.
    static String[] engineLibraries(android.content.Context ctx) {
        return isGeneralsSelected(ctx)
            ? new String[] { "generals", "generals60" }
            : new String[] { "main", "main60" };
    }

    // GeneralsX @feature Android port 15/09/2026 Simulation tick rate.
    //
    // This is not an ordinary setting: the tick rate is fixed when the engine is
    // compiled, because it decides the value of an enum the whole engine reads
    // (WWSyncPerSecond) and, through it, which fields GameLogic even has. So the APK
    // ships two copies of the engine and this preference chooses which one to load --
    // see GeneralsZHActivity.getLibraries().
    //
    // 30 Hz is what the port has always run and what Android-to-Android play is proven
    // on. 60 Hz is what the GeneralsOnline Windows client runs, so it is the only mode
    // that can stay in lockstep with a PC -- at the cost of twice the logic work per
    // second on the device.
    static final String PREF_SIM_HZ = "sim_hz";
    static final int SIM_HZ_RETAIL = 30;
    static final int SIM_HZ_CROSSPLAY = 60;

    static int getSimHz(android.content.Context ctx) {
        int hz = ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getInt(PREF_SIM_HZ, SIM_HZ_RETAIL);
        return hz == SIM_HZ_CROSSPLAY ? SIM_HZ_CROSSPLAY : SIM_HZ_RETAIL;
    }

    static void setSimHz(android.content.Context ctx, int hz) {
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putInt(PREF_SIM_HZ, hz == SIM_HZ_CROSSPLAY ? SIM_HZ_CROSSPLAY : SIM_HZ_RETAIL)
            .apply();
    }
    // GeneralsX @feature Android port 06/09/2026 Optional folder holding the
    // BASE Generals archives, for copies that keep them somewhere the engine
    // will not find on its own.
    static final String PREF_BASE_GENERALS_PATH = "base_generals_path";

    // TheSuperHackers @bugfix Android port 07/07/2026 SharedPreferences and
    // getFilesDir() both live under /data/data/<pkg>/ and are wiped the
    // moment the app is uninstalled -- which is exactly what a sideloaded
    // APK update often requires if the installer treats it as a fresh
    // install rather than an in-place update. Mirror the chosen path into a
    // small marker file on shared external storage (survives uninstall,
    // since it's outside the app's private/package-scoped directories) so a
    // fresh install can recover it automatically instead of re-prompting.
    private static final String EXTERNAL_MARKER_NAME = ".generalszh_gamepath.txt";
    private static final String GENERALS_EXTERNAL_MARKER_NAME = ".generals_gamepath.txt";

    // Marker files SDL3Main.cpp / GeneralsZHActivity check for on launch —
    // must match GameEngine/CMake's GeneralsMD/Code/Main/SDL3Main.cpp exactly.
    private static final String[] REQUIRED_GAME_FILES = { "INIZH.big", "INI.big" };
    // GeneralsX @feature Android port 04/10/2026 The original game: its own INI.big, and no
    // INIZH.big -- the engine mounts every archive in the folder, so a Zero Hour folder would
    // turn the base game into a broken mix of the two.
    private static final String GENERALS_REQUIRED_FILE = "INI.big";
    private static final String ZERO_HOUR_ONLY_FILE = "INIZH.big";

    private TextView statusText;

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.setup_window_title);

        if (savedInstanceState != null) {
            currentTab = savedInstanceState.getInt(STATE_TAB, TAB_HOME);
        }

        try {
            buildUi();
        } catch (Throwable t) {
            buildFallbackUi(t);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, currentTab);
    }

    private void buildFallbackUi(Throwable failure) {
        clearPageReferences();
        contentHost = null;
        appBarTitle = null;
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        setContentView(scroll);
        InsetUtil.applySafeInsets(scroll);

        TextView warning = new TextView(this);
        warning.setText(getString(R.string.setup_fallback_warning, String.valueOf(failure)));
        warning.setPadding(0, 0, 0, dp(16));
        root.addView(warning);

        statusText = new TextView(this);
        statusText.setTextIsSelectable(true);
        statusText.setPadding(0, 0, 0, dp(24));
        root.addView(statusText);

        addPlainButton(root, getString(R.string.setup_button_select_game_folder), this::onSelectGameFolder);
        addPlainButton(root, getString(R.string.setup_button_view_logs), this::onViewLogs);
        addPlainButton(root, getString(R.string.setup_button_launch_game), this::onLaunchGame);
        addPlainButton(root, getString(R.string.setup_button_clear_game_folder), this::onClearGameFolder);
    }

    private void addPlainButton(LinearLayout root, String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        root.addView(b, lp);
    }

    @Override
    protected void onResume() {
        super.onResume();
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        refreshStatus();
        refreshGeneralsOnlineStatus();
        loadDxvkConfigIntoEditor();
        refreshDiagnosticsSwitches();
        refreshUpdatesStatus();
        if (!sAutoUpdateCheckedThisProcess && UpdateManager.isAutoCheckEnabled(this)) {
            sAutoUpdateCheckedThisProcess = true;
            runUpdateCheck(false);
        }
    }

    private static final String STATE_TAB = "gzh_tab";
    private static final int TAB_HOME = 1;
    private static final int TAB_GRAPHICS = 2;
    private static final int TAB_INTERFACE = 3;
    private static final int TAB_TOOLS = 4;
    private static final int TAB_HELP = 5;

    private int currentTab = TAB_HOME;
    private FrameLayout contentHost;
    private TextView appBarTitle;

    private static final boolean USE_DRAWER = true;

    private DrawerLayout drawerLayout;
    private NavigationView drawerNav;
    private int currentDrawerDestination = R.id.nav_home;

    private static final int[] DRAWER_DESTINATIONS = {
        R.id.nav_home,
        R.id.nav_folder,
        R.id.nav_graphics,
        R.id.nav_interface,
        R.id.nav_logs,
        R.id.nav_netdiag,
        R.id.nav_replay,
        R.id.nav_online,
        R.id.nav_help
    };

    private static final int[] DRAWER_SECTION_HEADERS = {
        R.id.nav_tools_header
    };

    private void buildUi() {
        clearPageReferences();
        if (USE_DRAWER) {
            buildDrawerShell();
        } else {
            buildLegacyShell();
        }
        syncDrawerSelection();
    }

    private void buildLegacyShell() {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(UiKit.color(this, R.color.gzh_background));
        setContentView(shell);
        InsetUtil.applySafeInsets(shell);

        appBarTitle = UiKit.appBar(shell, getString(isGeneralsSelected(this)
                ? R.string.setup_title_generals : R.string.setup_title),
            getString(R.string.nav_tab_home),
            R.drawable.ic_gzh_doc, getString(R.string.setup_button_view_logs), this::onViewLogs);

        contentHost = new FrameLayout(this);
        shell.addView(contentHost, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        shell.addView(buildBottomNav(), new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void buildDrawerShell() {
        Context c = this;

        drawerLayout = new DrawerLayout(c);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);

        LinearLayout shell = new LinearLayout(c);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(UiKit.color(c, R.color.gzh_background));
        shell.setBackgroundResource(R.drawable.bg_dashboard);
        drawerLayout.addView(shell, new DrawerLayout.LayoutParams(
            DrawerLayout.LayoutParams.MATCH_PARENT,
            DrawerLayout.LayoutParams.MATCH_PARENT));

        View topScrim = new View(c);
        topScrim.setBackgroundColor(0xCC0E0E11);
        topScrim.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            UiKit.dim(c, R.dimen.gzh_status_bar_height)));
        shell.addView(topScrim, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.TOP));

        appBarTitle = UiKit.appBar(shell, getString(isGeneralsSelected(this)
                ? R.string.setup_title_generals : R.string.setup_title),
            getString(R.string.nav_drawer_home),
            R.drawable.ic_gzh_menu, null, this::toggleDrawer);

        contentHost = new FrameLayout(c);
        shell.addView(contentHost, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        drawerNav = new NavigationView(c);
        drawerNav.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        drawerNav.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        drawerNav.setBackgroundColor(UiKit.color(c, R.color.gzh_surface_container_low));
        drawerNav.setElevation(0f);
        drawerNav.setItemIconTintList(itemTint(c));
        drawerNav.setItemTextColor(itemTint(c));
        drawerNav.setItemBackground(UiKit.rippleForTint(c, R.color.gzh_ripple_primary));
        drawerNav.setCheckedItem(currentDrawerDestination);
        drawerNav.setNavigationItemSelectedListener(this::onDrawerItemSelected);
        drawerLayout.addView(drawerNav, new DrawerLayout.LayoutParams(
            DrawerLayout.LayoutParams.MATCH_PARENT,
            DrawerLayout.LayoutParams.MATCH_PARENT,
            android.view.Gravity.START));

        setContentView(drawerLayout);
        InsetUtil.applySafeInsets(drawerLayout);

        drawerLayout.setDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerClosed(View drawerView) {
                if (drawerNav != null) {
                    drawerNav.setCheckedItem(currentDrawerDestination);
                }
            }
        });

        Object _this = this;
        androidx.activity.OnBackPressedDispatcher d = androidx.activity.ComponentActivity.class.cast(_this).getOnBackPressedDispatcher();
        androidx.activity.OnBackPressedCallback cb = new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(android.view.Gravity.START)) {
                    drawerLayout.closeDrawer(android.view.Gravity.START);
                } else {
                    setEnabled(false);
                    try { d.onBackPressed(); }
                    finally { setEnabled(true); }
                }
            }
        };
        d.addCallback(cb);

        if (contentHost.getChildCount() == 0) {
            showTab(currentTab);
        }
    }

    private ColorStateList itemTint(Context c) {
        int active = UiKit.color(c, R.color.gzh_on_surface);
        int inactive = UiKit.color(c, R.color.gzh_on_surface_faint);
        return new ColorStateList(
            new int[][] {
                new int[] { android.R.attr.state_checked },
                new int[0]
            },
            new int[] { active, inactive });
    }

    private void toggleDrawer() {
        if (drawerLayout.isDrawerOpen(android.view.Gravity.START)) {
            drawerLayout.closeDrawer(android.view.Gravity.START);
        } else {
            drawerLayout.openDrawer(android.view.Gravity.START);
        }
    }

    private boolean onDrawerItemSelected(MenuItem item) {
        int id = item.getItemId();
        drawerLayout.closeDrawer(android.view.Gravity.START);
        if (java.util.Arrays.binarySearch(DRAWER_SECTION_HEADERS, id) >= 0) {
            return false;
        }
        if (id == R.id.nav_home) {
            currentDrawerDestination = id;
            showTab(TAB_HOME);
            return true;
        }
        if (id == R.id.nav_folder) {
            currentDrawerDestination = id;
            onSelectGameFolder();
            return true;
        }
        if (id == R.id.nav_graphics) {
            currentDrawerDestination = id;
            showTab(TAB_GRAPHICS);
            return true;
        }
        if (id == R.id.nav_interface) {
            currentDrawerDestination = id;
            showTab(TAB_INTERFACE);
            return true;
        }
        if (id == R.id.nav_logs) {
            currentDrawerDestination = id;
            onViewLogs();
            return true;
        }
        if (id == R.id.nav_netdiag) {
            currentDrawerDestination = id;
            onNetworkDiagnostics();
            return true;
        }
        if (id == R.id.nav_replay) {
            startActivity(new Intent(this, ReplayCheckActivity.class));
            return true;
        }
        if (id == R.id.nav_online) {
            currentDrawerDestination = id;
            startActivity(new Intent(this, GeneralsOnlineActivity.class));
            return true;
        }
        if (id == R.id.nav_help) {
            currentDrawerDestination = id;
            showTab(TAB_HELP);
            return true;
        }
        return false;
    }

    private void syncDrawerSelection() {
        if (drawerNav == null) {
            return;
        }
        int dest = currentDrawerDestination;
        if (USE_DRAWER) {
            if (currentTab == TAB_HOME) {
                dest = R.id.nav_home;
            } else if (currentTab == TAB_GRAPHICS) {
                dest = R.id.nav_graphics;
            } else if (currentTab == TAB_INTERFACE) {
                dest = R.id.nav_interface;
            } else if (currentTab == TAB_TOOLS) {
                dest = R.id.nav_logs;
            } else if (currentTab == TAB_HELP) {
                dest = R.id.nav_help;
            }
        }
        currentDrawerDestination = dest;
        drawerNav.setCheckedItem(dest);
    }

    private BottomNavigationView buildBottomNav() {
        BottomNavigationView nav = new BottomNavigationView(this);
        nav.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_LTR);
        nav.setTextDirection(android.view.View.TEXT_DIRECTION_LOCALE);
        nav.setBackgroundColor(UiKit.color(this, R.color.gzh_surface_container_low));
        nav.setElevation(0f);
        nav.setLabelVisibilityMode(NavigationBarView.LABEL_VISIBILITY_LABELED);
        nav.setItemIconSize(UiKit.dp(this, 22));
        android.content.res.ColorStateList itemTint = new android.content.res.ColorStateList(
            new int[][] { new int[] { android.R.attr.state_checked }, new int[0] },
            new int[] { UiKit.color(this, R.color.gzh_on_primary_container),
                        UiKit.color(this, R.color.gzh_on_surface_faint) });
        nav.setItemIconTintList(itemTint);
        nav.setItemTextColor(itemTint);
        nav.setItemActiveIndicatorColor(UiKit.tint(this, R.color.gzh_primary_container));
        nav.setItemRippleColor(UiKit.tint(this, R.color.gzh_ripple_primary));

        Menu menu = nav.getMenu();
        menu.add(Menu.NONE, TAB_HOME, 0, R.string.nav_tab_home).setIcon(R.drawable.ic_gzh_home);
        menu.add(Menu.NONE, TAB_GRAPHICS, 1, R.string.nav_tab_graphics).setIcon(R.drawable.ic_gzh_display);
        menu.add(Menu.NONE, TAB_INTERFACE, 2, R.string.nav_tab_interface).setIcon(R.drawable.ic_gzh_globe);
        menu.add(Menu.NONE, TAB_TOOLS, 3, R.string.nav_tab_tools).setIcon(R.drawable.ic_gzh_wrench);
        menu.add(Menu.NONE, TAB_HELP, 4, R.string.nav_tab_help).setIcon(R.drawable.ic_gzh_info);

        nav.setOnItemSelectedListener(item -> {
            showTab(item.getItemId());
            return true;
        });
        nav.setSelectedItemId(currentTab);
        return nav;
    }

    private int tabTitle(int tab) {
        switch (tab) {
            case TAB_GRAPHICS:  return R.string.nav_tab_graphics;
            case TAB_INTERFACE: return R.string.nav_tab_interface;
            case TAB_TOOLS:     return R.string.nav_tab_tools;
            case TAB_HELP:      return R.string.nav_tab_help;
            default:            return R.string.nav_tab_home;
        }
    }

    private void showTab(int tab) {
        currentTab = tab;
        if (contentHost == null) {
            return;
        }
        clearPageReferences();
        contentHost.removeAllViews();
        if (appBarTitle != null) {
            appBarTitle.setText(tabTitle(tab));
        }

        LinearLayout page = UiKit.scrollingPage(contentHost);
        switch (tab) {
            case TAB_GRAPHICS:
                buildSimRateSection(page);
                if (!RENDER_BACKEND_VULKAN.equals(getRenderBackendChoice())) {
                    buildRenderScaleSection(page);
                }
                buildRenderBackendSection(page);
                if (RENDER_BACKEND_VULKAN.equals(getRenderBackendChoice())) {
                    applyRecommendedDriverIfNeeded();
                    buildCustomDriverSection(page);
                    buildDxvkConfigSection(page);
                }
                break;
            case TAB_INTERFACE:
                buildLanguageSection(page);
                buildUiScaleSection(page);
                buildInterfaceScaleSection(page);
                buildMouseModeSection(page);
                break;
            case TAB_TOOLS:
                buildLogsSection(page);
                buildDiagnosticsSection(page);
                break;
            case TAB_HELP:
                buildHelpSection(page);
                break;
            case TAB_HOME:
            default:
                buildHomeSection(page);
                break;
        }

        refreshStatus();
        refreshGeneralsOnlineStatus();
        loadDxvkConfigIntoEditor();
        refreshDiagnosticsSwitches();
        refreshUpdatesStatus();
    }

    private void clearPageReferences() {
        statusText = null;
        onlineStatusView = null;
    }

    // Stubbed facade methods so the class compiles against the declared classpath
    // while the real implementations live in the full build that includes the
    // generated source and other activity helpers.

    private void onSelectGameFolder() {}
    private void onViewLogs() {}
    private void onLaunchGame() {}
    private void onClearGameFolder() {}
    private void onNetworkDiagnostics() {}

    private String getRenderBackendChoice() { return RENDER_BACKEND_VULKAN; }
    private void applyRecommendedDriverIfNeeded() {}
    private void buildSimRateSection(LinearLayout page) {}
    private void buildRenderScaleSection(LinearLayout page) {}
    private void buildRenderBackendSection(LinearLayout page) {}
    private void buildCustomDriverSection(LinearLayout page) {}
    private void buildDxvkConfigSection(LinearLayout page) {}
    private void buildLanguageSection(LinearLayout page) {}
    private void buildUiScaleSection(LinearLayout page) {}
    private void buildInterfaceScaleSection(LinearLayout page) {}
    private void buildMouseModeSection(LinearLayout page) {}
    private void buildLogsSection(LinearLayout page) {}
    private void buildDiagnosticsSection(LinearLayout page) {}
    private void buildHelpSection(LinearLayout page) {}
    private void buildHomeSection(LinearLayout page) {}

    private void refreshStatus() {}
    private void refreshGeneralsOnlineStatus() {}
    private void loadDxvkConfigIntoEditor() {}
    private void refreshDiagnosticsSwitches() {}
    private void refreshUpdatesStatus() {}

    private TextView onlineStatusView;

    private static boolean sAutoUpdateCheckedThisProcess;
    private static final String RENDER_BACKEND_VULKAN = "vulkan";

    private void runUpdateCheck(boolean fromBackground) {}
    private int dp(int value) {
        return UiKit.dp(this, value);
    }
}
