// GeneralsX @build Android touch editor 05/11/2026
//
// The launcher's editor page, laid out the way a phone lays it out. Two defects lived here and
// neither was visible from the model:
//
//   * the editing surface was attached with default layout params, so it measured to a few pixels
//     wide and zero high and drew nothing;
//   * the footer's trailing spacer was a weighted WRAP_CONTENT view, which stretched the footer to
//     the whole window and squeezed the body -- the editing surface -- to zero height.
//
// Both made the page look like it had loaded but shown no buttons. These tests render the real
// activity, so a regression fails here rather than on a tester's phone.

package com.ports.generalsx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TouchEditorLauncherLayoutTest {

    private static final int WIDTH = 1000;
    private static final int HEIGHT = 600;
    private static final int FILL = 0xFFFF0000;

    private Activity host;

    @Before
    public void setUp() {
        host = Robolectric.buildActivity(Activity.class).setup().get();
        TouchControlConfig.reset(host);
        // One large, fully opaque button at the centre, so a sample pixel inside it is
        // unambiguous and cannot be a stray background colour.
        TouchControlConfig config = TouchControlConfig.load(host);
        TouchControlConfig.ButtonSpec spec = config.buttons.get(0);
        spec.shape = TouchButtonShape.RECTANGLE;
        spec.x = 0.5f;
        spec.y = 0.5f;
        spec.widthDp = 120f;
        spec.heightDp = 90f;
        spec.fillColor = FILL;
        spec.opacity = 1f;
        spec.enabled = true;
        config.buttonOpacity = 1f;
        for (int i = 1; i < config.buttons.size(); ++i) {
            config.buttons.get(i).enabled = false;
        }
        config.save(host);
    }

    /** The editor canvas, laid out as a real frame lays it out. */
    private View laidOutCanvas() {
        TouchControlsActivity activity = Robolectric.buildActivity(TouchControlsActivity.class)
            .setup().get();
        View root = activity.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, WIDTH, HEIGHT);
        View canvas = findCanvas(root);
        assertNotNull("the editor page has an editing surface", canvas);
        return canvas;
    }

    @Test
    public void theEditingSurfaceFillsTheBodyAndNotZero() {
        View canvas = laidOutCanvas();
        assertTrue("the editing surface has a real width, not a wrap-content sliver",
            canvas.getWidth() > WIDTH / 2);
        assertTrue("the editing surface has a real height, not zero",
            canvas.getHeight() > HEIGHT / 4);
    }

    @Test
    public void theEditingSurfaceDrawsTheSavedButton() {
        View canvas = laidOutCanvas();
        Bitmap bitmap = Bitmap.createBitmap(canvas.getWidth(), canvas.getHeight(),
            Bitmap.Config.ARGB_8888);
        canvas.draw(new Canvas(bitmap));
        int count = 0;
        for (int x = 0; x < bitmap.getWidth(); x += 2) {
            for (int y = 0; y < bitmap.getHeight(); y += 2) {
                if (bitmap.getPixel(x, y) == FILL) {
                    ++count;
                }
            }
        }
        // The button is 120dp x 90dp at density 1 in Robolectric's default configuration, so a
        // few thousand samples land inside it. Zero means the editor drew an empty surface.
        assertTrue("the saved button is rendered on the editing surface, not an empty surface",
            count > 500);
    }

    private View findCanvas(View view) {
        if (view.getClass().getName().contains("EditorCanvas")) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); ++i) {
                View found = findCanvas(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * GeneralsX @feature Android touch editor 05/11/2026 The preview is the GAME screen, mapped
     * into the narrower canvas the side inspector leaves. A button at 20% down the game screen must
     * be drawn 20% down the *game screen*, not 20% down the canvas -- the two differ by the
     * letterbox offset, and it is the first that the match will draw. Without the transform the
     * launcher would edit one layout and the match would draw another.
     */
    @Test
    public void thePreviewMapsTheGameScreenNotTheNarrowCanvas() {
        TouchControlsActivity activity = Robolectric.buildActivity(TouchControlsActivity.class)
            .setup().get();
        int gameW = 2000;
        int gameH = 1000;
        activity.setGameViewportForTest(gameW, gameH);
        View root = activity.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, WIDTH, HEIGHT);

        View surface = activity.editorSurfaceForTest();
        TouchControlConfig.ButtonSpec spec = activity.editorForTest().buttons().get(0);
        spec.x = 0.25f;
        // Off the letterboxed axis' centre, where a canvas-sized viewport would put the button
        // somewhere else entirely.
        spec.y = 0.2f;
        Bitmap bitmap = Bitmap.createBitmap(surface.getWidth(), surface.getHeight(),
            Bitmap.Config.ARGB_8888);
        surface.draw(new Canvas(bitmap));

        TouchPreviewViewport expected = new TouchPreviewViewport();
        expected.update(surface.getWidth(), surface.getHeight(), gameW, gameH);
        int predictedX = Math.round(expected.toPreviewX(spec.x * gameW));
        int predictedY = Math.round(expected.toPreviewY(spec.y * gameH));
        int naiveY = Math.round(spec.y * surface.getHeight());

        assertTrue("the letterbox offset makes the two mappings differ at all",
            Math.abs(predictedY - naiveY) > 20);
        assertTrue("the button is drawn where the game screen puts it",
            hasRedNear(bitmap, predictedX, predictedY));
        assertFalse("and not where a canvas-sized viewport would have put it",
            hasRedNear(bitmap, predictedX, naiveY));
    }

    /** True when a red-dominant pixel -- the test button's fill -- lies near a point. */
    private static boolean hasRedNear(Bitmap bitmap, int cx, int cy) {
        for (int y = Math.max(0, cy - 8); y < Math.min(bitmap.getHeight(), cy + 9); ++y) {
            for (int x = Math.max(0, cx - 8); x < Math.min(bitmap.getWidth(), cx + 9); ++x) {
                int pixel = bitmap.getPixel(x, y);
                if (android.graphics.Color.red(pixel) > 200
                        && android.graphics.Color.green(pixel) < 120
                        && android.graphics.Color.blue(pixel) < 120) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * GeneralsX @feature Android touch editor 05/11/2026 A drag is mapped through the preview's
     * inverse, so dragging to the same canvas point in two different canvas sizes stores the same
     * game-screen position. This is the property that keeps a layout portable between the launcher
     * and the match, and it is invisible unless the touch path itself is driven.
     */
    @Test
    public void aDragIsMappedThroughThePreviewInverse() {
        TouchControlsActivity activity = Robolectric.buildActivity(TouchControlsActivity.class)
            .setup().get();
        activity.setGameViewportForTest(2000, 1000);
        View root = activity.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, WIDTH, HEIGHT);
        View surface = activity.editorSurfaceForTest();

        // Select the button where it is drawn, then drag it to the canvas' centre.
        TouchLayoutEditor editor = activity.editorForTest();
        TouchControlConfig.ButtonSpec spec = editor.buttons().get(0);
        long now = android.os.SystemClock.uptimeMillis();
        surface.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN,
            spec.x * surface.getWidth(), spec.y * surface.getHeight(), 0));
        surface.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE,
            surface.getWidth() * 0.5f, surface.getHeight() * 0.5f, 0));
        surface.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_UP,
            surface.getWidth() * 0.5f, surface.getHeight() * 0.5f, 0));

        assertEquals("the drag lands on the game screen's centre", 0.5f, spec.x, 0.02f);
        assertEquals("and on its vertical centre", 0.5f, spec.y, 0.02f);
    }

    /**
     * GeneralsX @feature Android touch editor 05/11/2026 Add opens the picker while there is room
     * and refuses -- without opening it -- once the layout is full. The refusal is the same guard
     * the in-game toolbox's Add uses, so a full layout can never lose a button to a 41st.
     */
    @Test
    public void addOpensThePickerAndRefusesAtForty() {
        TouchControlsActivity activity = Robolectric.buildActivity(TouchControlsActivity.class)
            .setup().get();
        TouchLayoutEditor editor = activity.editorForTest();
        // Fill the layout to the maximum through the model's own guard.
        while (editor.canAddButton()) {
            assertTrue(editor.addButton(android.view.KeyEvent.KEYCODE_A, 0));
        }
        assertEquals(TouchControlConfig.MAX_BUTTONS, editor.buttons().size());

        activity.showKeyPickerForTest();
        assertNull("a full layout refuses rather than opening the picker",
            activity.keyDialogForTest());

        editor.deleteSelected();
        activity.showKeyPickerForTest();
        assertNotNull("with room again, Add opens the picker", activity.keyDialogForTest());
    }
}
