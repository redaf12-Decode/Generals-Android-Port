/*
**    Stub mirroring the SDL3 activity subclass the GeneralsX Android shell
**    expects at compile time (org.libsdl.app.SDLActivity). The real class is
**    provided by the SDL3 project at build time (staged into app/src/main/java-sdl);
**    this file only exists so static analysis / lint tasks that validate against
**    the project's declared compile classpath — i.e. that do NOT include the
**    staged java-sdl source tree — can resolve android/GeneralsZHActivity.java
**    without error. It is a compile-time seam, not a runtime implementation.
**
**    GeneralsX @build Android stub visibility seam 15/08/2026
*/

package org.libsdl.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;

/**
 * Minimal compile-time stand-in for SDL3's SDLActivity so Java source that
 * {@code extends SDLActivity} and overrides its lifecycle/hook methods can be
 * type-checked without the full SDL3 source tree on the analysis classpath.
 */
public class SDLActivity extends Activity {

    // Layout root the game shell creates text-editor UI against.
    protected android.view.ViewGroup mLayout;

    // The surface view SDL3 creates for the native window.
    protected android.view.SurfaceView mSurface;

    /**
     * Ordered list of native libraries the activity must load. Subclasses
     * override this to name the game .so alongside the SDL3 .so.
     */
    protected String[] getLibraries() {
        return new String[0];
    }

    /** Load each library named by {@link #getLibraries()}. */
    public void loadLibraries() {
        for (String lib : getLibraries()) {
            System.loadLibrary(lib);
        }
    }

    /**
     * Extra launch arguments the engine passes to the native entry point.
     * Subclasses override this to forward safe-insets and other launcher-set
     * options into the engine.
     */
    protected String[] getArguments() {
        return new String[0];
    }

    /** Name of the main shared object the engine entry point lives in. */
    protected String getMainSharedObject() {
        return null;
    }

    /** Called when the activity is created. Subclasses that redirect on a
     *  misconfigured install must NOT call super when they intend to skip
     *  native library loading. */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
    }

    /** Called when a new intent arrives for a singleInstance activity. */
    @Override
    protected void onNewIntent(Intent intent) {
    }

    /** Hook the native window-creation path calls to set the requested
     *  orientation. Subclasses pin it here. */
    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
    }

    /** Dispatch a key event; subclasses use this to intercept Back while an
     *  in-app editor bar is showing. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return super.dispatchKeyEvent(event);
    }

    /** Called when the window focus changes; subclasses use this to re-assert
     *  system-gesture behavior each time the window has focus. */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
    }
}
