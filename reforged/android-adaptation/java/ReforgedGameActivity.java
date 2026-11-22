package com.generalsx.zerohour;

import org.libsdl.apprfx.SDLActivity;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Reforged play activity (Android port): runs the actual olcay Reforged
 * engine (librfx_game.so, shipped as libmain.so) as the game.
 *
 * The Reforged engine statically links its own SDL 3.4.16 and exports the
 * org.libsdl.apprfx JNI layer (see reforged/android-adaptation/tools/
 * stage-rfx-glue.py and reforged-rfx-jni-prefix.patch), so it cannot run
 * under the host shell's org.libsdl.app.SDLActivity. This activity extends
 * the isolated org.libsdl.apprfx.SDLActivity instead.
 *
 * Data flow reuses the existing launcher: SetupActivity (unchanged) lets the
 * player pick the Zero Hour installation folder and stores it (PREF_GAME_PATH).
 * getArguments() reads that same folder and passes it to the engine as -root,
 * which olcay's PosixMain.cpp chdir()s into. No game data is bundled in the
 * APK; the player supplies their own legally-owned installation.
 */
public class ReforgedGameActivity extends SDLActivity {

    private static final String TAG = "ReforgedGame";

    /** The engine library base name (System.loadLibrary) — the Reforged engine. */
    @Override
    protected String[] getLibraries() {
        return new String[] { "main" };
    }

    /** The game folder the player picked in SetupActivity, or null. */
    private String resolveGamePath() {
        final String game = SetupActivity.getSelectedGame(this);
        final String path = SetupActivity.getSavedGamePath(this, game);
        if (path != null && SetupActivity.isValidGameFolder(new java.io.File(path), game)) {
            return path;
        }
        return null;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Never load the engine into an app state that can only black-screen:
        // without a valid game folder, go back to Setup (same guard the shell's
        // GeneralsZHActivity applies before touching its native library).
        if (resolveGamePath() == null) {
            Log.i(TAG, "no valid game folder configured; redirecting to Setup");
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }
        super.onCreate(savedInstanceState);
    }

    /**
     * The engine command line. olcay's PosixMain.cpp reads -root and chdir()s
     * into the install root itself; the rest of the data search then runs off
     * that root. Returns exactly what the desktop launch would carry.
     */
    @Override
    protected String[] getArguments() {
        final String root = resolveGamePath();
        if (root == null) {
            return new String[0];
        }
        return new String[] { "-root", root };
    }
}