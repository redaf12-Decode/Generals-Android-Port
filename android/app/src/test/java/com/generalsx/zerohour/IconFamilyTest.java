package com.generalsx.zerohour;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.VectorDrawable;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.GraphicsMode;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * "Clear and consistent" is a measurable property, not a taste: every launcher icon is one vector
 * on the same 24-unit grid, its painted box stays inside that grid, it is optically centred on
 * (12,12), and it carries a comparable amount of ink. These tests render each ic_gzh_* at 1:1 and
 * read the pixels back, because the three things that made the set inconsistent -- a glyph drawn at
 * a different optical scale, a solid glyph sitting beside stroked ones, and a 6-unit-thick check in
 * a row of 2-unit strokes -- are all invisible to a resource-level assertion but obvious in the
 * raster.
 *
 * The numbers are deliberately loose enough to allow shape differences (a chevron is narrow, a bin
 * is dense) and tight enough to catch the drift that prompted the redraw: 1 unit of edge spill from
 * anti-aliasing, 1.5 units of off-centre placement, 5-45% ink.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class IconFamilyTest {

    private static final int GRID = 24;
    private static final int MIN_ALPHA = 80;

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
    }

    @Test
    public void everyIconIsOneVectorOnTheSharedGrid() throws Exception {
        List<String> names = iconNames();
        assertTrue("found no ic_gzh_* resources -- the icon set moved?", names.size() >= 20);
        for (String name : names) {
            Drawable drawable = context.getDrawable(id(name));
            assertNotNull(name + " does not resolve", drawable);
            assertTrue(name + " is " + drawable.getClass().getSimpleName() + ", not a vector",
                drawable instanceof VectorDrawable);
            assertTrue(name + " is not drawn on the 24dp grid: intrinsic width "
                    + drawable.getIntrinsicWidth() + "px at density "
                    + context.getResources().getDisplayMetrics().density,
                Math.abs(drawable.getIntrinsicWidth()
                    - Math.round(24 * context.getResources().getDisplayMetrics().density)) <= 1);
        }
    }

    @Test
    public void everyIconStaysInsideTheGridAndIsOpticallyCentred() throws Exception {
        for (String name : iconNames()) {
            Box box = measure(name);
            assertTrue(name + " paints outside the grid: " + box,
                box.minX >= 1 && box.minY >= 1 && box.maxX <= GRID - 2 && box.maxY <= GRID - 2);
            float centreX = (box.minX + box.maxX) / 2f;
            float centreY = (box.minY + box.maxY) / 2f;
            assertTrue(name + " is off-centre horizontally at " + centreX + " (box " + box + ")",
                Math.abs(centreX - 12f) <= 1.5f);
            assertTrue(name + " is off-centre vertically at " + centreY + " (box " + box + ")",
                Math.abs(centreY - 12f) <= 1.5f);
        }
    }

    @Test
    public void everyIconCarriesAComparableAmountOfInk() throws Exception {
        for (String name : iconNames()) {
            int ink = inkPercent(name);
            assertTrue(name + " is almost empty (" + ink + "%)", ink >= 5);
            assertTrue(name + " is a solid block (" + ink + "%)", ink <= 45);
        }
    }

    /**
     * The launcher tints every icon (UiKit.icon -> setTint, the drawer's itemIconTint). That works
     * on stroked vectors only while the strokes carry the icon's own colour, so a stroke added with
     * no strokeColor -- which defaults to none -- would render an invisible icon that still passes
     * an "is it a vector" check. This renders one stroked glyph through a tint and reads it back.
     */
    @Test
    public void aStrokedIconTakesTheCallerTint() throws Exception {
        Drawable drawable = context.getDrawable(R.drawable.ic_gzh_menu);
        assertNotNull(drawable);
        drawable.setTint(0xFFFF0000);
        Bitmap bitmap = paint(drawable);

        int painted = 0;
        int red = 0;
        for (int y = 0; y < GRID; y++) {
            for (int x = 0; x < GRID; x++) {
                int pixel = bitmap.getPixel(x, y);
                if ((pixel >>> 24) > MIN_ALPHA) {
                    painted++;
                    if (((pixel >> 16) & 0xFF) > 200 && ((pixel >> 8) & 0xFF) < 80) {
                        red++;
                    }
                }
            }
        }
        assertTrue("the tinted icon painted nothing", painted > 20);
        assertTrue("only " + red + " of " + painted + " painted pixels took the tint",
            red > painted * 8 / 10);
    }

    // ---------------------------------------------------------------- helpers

    private List<String> iconNames() {
        List<String> names = new ArrayList<>();
        for (Field field : R.drawable.class.getDeclaredFields()) {
            if (field.getName().startsWith("ic_gzh_")) {
                names.add(field.getName());
            }
        }
        return names;
    }

    private int id(String name) throws Exception {
        return R.drawable.class.getField(name).getInt(null);
    }

    private Bitmap paint(Drawable drawable) {
        Bitmap bitmap = Bitmap.createBitmap(GRID, GRID, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0);
        drawable.setBounds(0, 0, GRID, GRID);
        drawable.draw(new Canvas(bitmap));
        return bitmap;
    }

    private Box measure(String name) throws Exception {
        Bitmap bitmap = paint(context.getDrawable(id(name)));
        Box box = new Box();
        for (int y = 0; y < GRID; y++) {
            for (int x = 0; x < GRID; x++) {
                if ((bitmap.getPixel(x, y) >>> 24) > MIN_ALPHA) {
                    box.add(x, y);
                }
            }
        }
        return box;
    }

    private int inkPercent(String name) throws Exception {
        Bitmap bitmap = paint(context.getDrawable(id(name)));
        int painted = 0;
        for (int y = 0; y < GRID; y++) {
            for (int x = 0; x < GRID; x++) {
                if ((bitmap.getPixel(x, y) >>> 24) > MIN_ALPHA) {
                    painted++;
                }
            }
        }
        return painted * 100 / (GRID * GRID);
    }

    private static final class Box {
        int minX = GRID;
        int minY = GRID;
        int maxX = -1;
        int maxY = -1;

        void add(int x, int y) {
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
        }

        @Override
        public String toString() {
            return "[" + minX + "," + minY + ".." + maxX + "," + maxY + "]";
        }
    }
}
