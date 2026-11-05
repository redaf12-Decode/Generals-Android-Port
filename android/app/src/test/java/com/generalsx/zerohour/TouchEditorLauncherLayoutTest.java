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

package com.generalsx.zerohour;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
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
}
