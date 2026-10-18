package com.generalsx.zerohour;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.VectorDrawable;
import android.os.Build;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.GraphicsMode;

import java.lang.reflect.Field;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The launcher's icons must be vectors, and the game shortcut's emblem must render the way it
 * was computed. The first test keeps every ic_gzh_* drawable on the vector path (a raster or
 * shape swapped in behind the same name fails here), the second pins the adaptive-icon layers
 * to vectors now that the density PNGs are gone, and the last two actually RASTERIZE the new
 * emblem to check its geometry: gold star, silver shield ring, the even-odd hole, and the
 * corners of the 108dp canvas untouched so no launcher mask can cut the shield.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LauncherIconVectorTest {

    /** The adaptive icon's own viewport: layers are drawn onto a canvas this size, 1:1. */
    private static final int VIEWPORT = 108;

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
    }

    @Test
    public void everyGzhUiIconInflatesAsAVector() throws Exception {
        int checked = 0;
        for (Field field : R.drawable.class.getDeclaredFields()) {
            if (!field.getName().startsWith("ic_gzh_")) {
                continue;
            }
            int id = field.getInt(null);
            Drawable d = context.getDrawable(id);
            assertNotNull(field.getName() + " does not resolve", d);
            assertTrue(field.getName() + " is " + d.getClass().getSimpleName()
                    + ", not a vector -- the launcher's UI icons must all be vectors",
                d instanceof VectorDrawable);
            checked++;
        }
        assertTrue("found no ic_gzh_* resources at all -- the icon set moved?",
            checked >= 20);
    }

    @Test
    public void bothLauncherShortcutIconsAreVectorBasedAdaptiveIcons() {
        Drawable game = context.getDrawable(R.mipmap.ic_launcher);
        assertTrue("the game icon is " + game.getClass().getSimpleName()
                + ", not an adaptive icon", game instanceof AdaptiveIconDrawable);
        AdaptiveIconDrawable adaptive = (AdaptiveIconDrawable) game;
        assertTrue("the game emblem layer is " + adaptive.getForeground().getClass()
                + "-- the density PNGs are gone, this must be a vector",
            adaptive.getForeground() instanceof VectorDrawable);
        assertTrue("the game background must stay a vector too",
            adaptive.getBackground() instanceof VectorDrawable);
        assertFalse("no layer of the icon may fall back to a bitmap",
            adaptive.getForeground() instanceof BitmapDrawable);
        if (Build.VERSION.SDK_INT >= 33) {
            assertTrue("the themed-icon layer must be a vector",
                adaptive.getMonochrome() instanceof VectorDrawable);
        }

        Drawable setup = context.getDrawable(R.mipmap.ic_launcher_setup);
        assertTrue(setup instanceof AdaptiveIconDrawable);
        AdaptiveIconDrawable setupAdaptive = (AdaptiveIconDrawable) setup;
        assertTrue("the Setup shortcut's gear must be a vector",
            setupAdaptive.getForeground() instanceof VectorDrawable);
        assertFalse(setupAdaptive.getForeground() instanceof BitmapDrawable);
    }

    @Test
    public void theEmblemRendersInItsSafeCircleWithTheRightColours() {
        Bitmap bitmap = renderForeground();
        // (54,51): dead centre of the star -- gold #E8C547 (232,197,71).
        int star = bitmap.getPixel(54, 51);
        assertTrue("the star centre is not gold: " + Integer.toHexString(star),
            (star >>> 24) > 200 && ColorRed(star) > 200 && ColorBlue(star) < 120);
        // (54,36): the 4-unit shield band between y34 and y38 -- silver #EDF1F7.
        int ring = bitmap.getPixel(54, 36);
        assertTrue("the shield ring is not silver: " + Integer.toHexString(ring),
            (ring >>> 24) > 200 && ColorRed(ring) > 200 && ColorBlue(ring) > 180);
        // (54,70): inside the inner shield, below the star -- the even-odd hole, so the
        // background gradient shows through: the layer itself paints nothing there.
        assertTrue("the shield's hole is painted over: "
                + Integer.toHexString(bitmap.getPixel(54, 70)),
            (bitmap.getPixel(54, 70) >>> 24) < 40);
        // Corners of the canvas: the farthest emblem point is 30 of the 33 safe units from
        // centre, so a launcher's circle/squircle mask has nothing to cut at (3,3).
        assertTrue("the emblem reaches outside its safe circle at (3,3)",
            (bitmap.getPixel(3, 3) >>> 24) < 40);

        int painted = 0;
        for (int y = 0; y < VIEWPORT; y += 2) {
            for (int x = 0; x < VIEWPORT; x += 2) {
                if ((bitmap.getPixel(x, y) >>> 24) > 128) {
                    painted++;
                }
            }
        }
        assertTrue("the emblem rendered almost nothing (" + painted + " samples)",
            painted > 75);
    }

    @Test
    public void theMonochromeLayerIsTheSameSilhouetteInWhite() {
        AdaptiveIconDrawable adaptive =
            (AdaptiveIconDrawable) context.getDrawable(R.mipmap.ic_launcher);
        Drawable monochrome = adaptive.getMonochrome();
        assertTrue(monochrome instanceof VectorDrawable);

        Bitmap bitmap = Bitmap.createBitmap(VIEWPORT, VIEWPORT, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0);
        monochrome.setBounds(0, 0, VIEWPORT, VIEWPORT);
        monochrome.draw(new android.graphics.Canvas(bitmap));

        int star = bitmap.getPixel(54, 51);
        assertTrue("the themed-icon star is not white: " + Integer.toHexString(star),
            (star >>> 24) > 200 && ColorRed(star) > 250 && ColorGreen(star) > 250
                && ColorBlue(star) > 250);
        assertTrue("the themed-icon layer keeps the shield's hole",
            (bitmap.getPixel(54, 70) >>> 24) < 40);
        assertTrue("the tinted silhouette must not reach the corner at (3,3)",
            (bitmap.getPixel(3, 3) >>> 24) < 40);
    }

    private Bitmap renderForeground() {
        AdaptiveIconDrawable adaptive =
            (AdaptiveIconDrawable) context.getDrawable(R.mipmap.ic_launcher);
        Drawable foreground = adaptive.getForeground();
        Bitmap bitmap = Bitmap.createBitmap(VIEWPORT, VIEWPORT, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0);
        foreground.setBounds(0, 0, VIEWPORT, VIEWPORT);
        foreground.draw(new android.graphics.Canvas(bitmap));
        return bitmap;
    }

    // Color.red/green/blue as functions on an int pixel; kept local so the probes above read
    // like the colour contract they are.
    private static int ColorRed(int pixel) { return (pixel >> 16) & 0xFF; }
    private static int ColorGreen(int pixel) { return (pixel >> 8) & 0xFF; }
    private static int ColorBlue(int pixel) { return pixel & 0xFF; }
}