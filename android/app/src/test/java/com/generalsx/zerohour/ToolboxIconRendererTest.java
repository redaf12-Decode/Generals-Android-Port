package com.generalsx.zerohour;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.GraphicsMode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The shared icon renderer: the one place the launcher's preview card and the in-game overlay
 * both draw the floating bar's icon through. These tests pin the two promises that make the
 * card honest about the game -- one setting produces one alpha everywhere, and the three
 * styles really are three different shapes in the same rectangle.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ToolboxIconRendererTest {

    private static final int ACCENT = 0xFF6750A4;

    ToolboxIconRenderer renderer;

    @Before
    public void setUp() {
        renderer = new ToolboxIconRenderer(RuntimeEnvironment.getApplication(), ACCENT);
    }

    @Test
    public void iconOpacityTranslatesToExactlyOneAlphaInOnePlace() {
        assertEquals("the default 85%", 217, ToolboxIconRenderer.alphaFor(ToolboxConfig.DEFAULT_OPACITY));
        assertEquals("the floor, 35%", 89, ToolboxIconRenderer.alphaFor(ToolboxConfig.MIN_OPACITY));
        assertEquals("the ceiling, 100%", 255, ToolboxIconRenderer.alphaFor(ToolboxConfig.MAX_OPACITY));
        assertEquals("half is half", 128, ToolboxIconRenderer.alphaFor(0.5f));
        // Stored values are clamped on load, and a stale malformed one is clamped again here:
        // below the floor still draws at the floor, above the ceiling at the ceiling.
        assertEquals("0 clamps up to the floor", 89, ToolboxIconRenderer.alphaFor(0f));
        assertEquals("a huge value clamps to opaque", 255, ToolboxIconRenderer.alphaFor(9f));
    }

    @Test
    public void eachStyleDrawsItsOwnShapeIntoTheSameRect() {
        RectF rect = new RectF(10f, 10f, 110f, 110f);
        // Two probe pixels inside the shared 100x100 box where the three styles disagree:
        // (21,21) lies under the rounded rectangle's corner only -- outside both the circle and
        // the diamond; (45,102) is inside the circle and the rounded rectangle but past the
        // diamond's lower-left edge. Every probe sits well clear of any anti-aliased boundary.
        int[][] probes = { {21, 21}, {45, 102} };
        int[] styles = { ToolboxConfig.STYLE_ROUNDED, ToolboxConfig.STYLE_CIRCLE,
            ToolboxConfig.STYLE_DIAMOND };
        boolean[][] covered = {
            { true,  true  },   // rounded: covers both
            { false, true  },   // circle: misses the corner, covers the low probe
            { false, false },   // diamond: misses both
        };

        for (int s = 0; s < styles.length; s++) {
            Bitmap bitmap = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.TRANSPARENT);
            renderer.draw(new Canvas(bitmap), rect, styles[s], 1f);
            for (int p = 0; p < probes.length; p++) {
                int alpha = Color.alpha(bitmap.getPixel(probes[p][0], probes[p][1]));
                if (covered[s][p]) {
                    assertTrue("style " + styles[s] + " misses probe " + p
                        + " (alpha " + alpha + ")", alpha > 128);
                } else {
                    assertTrue("style " + styles[s] + " covers probe " + p
                        + " (alpha " + alpha + ")", alpha < 64);
                }
            }
        }
    }

    @Test
    public void theAlphaTheSettingAsksIsTheAlphaThePixelsGet() {
        RectF rect = new RectF(10f, 10f, 110f, 110f);
        // (60,20) is plain icon fill: ten pixels inside the circle's edge, far above the glyph.
        // Drawing at 0.4 renders the stored alpha (255 * 0.4 == 102) onto a transparent canvas,
        // so the composited pixel alpha IS the derived value; at full opacity it is opaque.
        float[][] cases = { { 0.4f, 102f }, { 1f, 255f }, { ToolboxConfig.DEFAULT_OPACITY, 217f } };
        for (float[] testCase : cases) {
            Bitmap bitmap = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.TRANSPARENT);
            renderer.draw(new Canvas(bitmap), rect, ToolboxConfig.STYLE_CIRCLE, testCase[0]);
            int alpha = Color.alpha(bitmap.getPixel(60, 20));
            float expected = testCase[1];
            assertTrue("opacity " + testCase[0] + " rendered alpha " + alpha
                    + ", expected about " + expected,
                Math.abs(alpha - expected) <= 3f);
        }
    }
}