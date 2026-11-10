// GeneralsX @build Android touch editor 05/11/2026
//
// The shape geometry is where a "works on screen, wrong under the finger" bug lives, so these
// tests are about the two properties that matter: the hit test follows the drawn shape, and the
// square/circle rule keeps one side. A circle's bounding box must not swallow a corner tap -- that
// tap belongs to the battlefield.

package com.ports.generalsx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.RectF;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class TouchButtonShapeTest {

    private final RectF rect = new RectF(0f, 0f, 100f, 100f);

    @Test
    public void rectangleContainsItsCorners() {
        assertTrue(TouchButtonShape.contains(TouchButtonShape.RECTANGLE, 0, 0f, rect, 1f, 1f));
        assertTrue(TouchButtonShape.contains(TouchButtonShape.RECTANGLE, 0, 0f, rect, 99f, 99f));
        assertFalse(TouchButtonShape.contains(TouchButtonShape.RECTANGLE, 0, 0f, rect, 101f, 50f));
    }

    /**
     * The whole point of an analytic hit test: the corner of a circle's box is outside the circle,
     * so a tap there falls through to the battlefield.
     */
    @Test
    public void circleRejectsBoxCorners() {
        assertTrue(TouchButtonShape.contains(TouchButtonShape.CIRCLE, 0, 0f, rect, 50f, 50f));
        assertTrue(TouchButtonShape.contains(TouchButtonShape.CIRCLE, 0, 0f, rect, 50f, 5f));
        assertFalse("the box corner is outside the circle", TouchButtonShape.contains(
            TouchButtonShape.CIRCLE, 0, 0f, rect, 2f, 2f));
        assertFalse(TouchButtonShape.contains(TouchButtonShape.CIRCLE, 0, 0f, rect, 99f, 99f));
    }

    @Test
    public void ovalIsWiderThanTall() {
        RectF wide = new RectF(0f, 0f, 200f, 100f);
        // On the horizontal axis, inside; off the axes, outside.
        assertTrue(TouchButtonShape.contains(TouchButtonShape.OVAL, 0, 0f, wide, 199f, 50f));
        assertFalse(TouchButtonShape.contains(TouchButtonShape.OVAL, 0, 0f, wide, 10f, 10f));
    }

    @Test
    public void roundedRectangleRejectsTheCornerOutsideTheRadius() {
        // A 100x100 square with a 40 radius: (2,2) is outside the corner disc, (50,2) is inside.
        assertFalse(TouchButtonShape.contains(TouchButtonShape.ROUNDED, 0, 40f, rect, 2f, 2f));
        assertTrue(TouchButtonShape.contains(TouchButtonShape.ROUNDED, 0, 40f, rect, 50f, 2f));
        assertTrue(TouchButtonShape.contains(TouchButtonShape.ROUNDED, 0, 40f, rect, 50f, 50f));
    }

    @Test
    public void cornerRadiusIsClampedToHalfTheShorterSide() {
        // Half the SHORTER side, so 40 and not 50.
        assertEquals(40f, TouchButtonShape.clampCorner(500f, 100f, 80f), 0.001f);
        assertEquals(0f, TouchButtonShape.clampCorner(-5f, 100f, 100f), 0.001f);
    }

    @Test
    public void squareAndCircleAreOneSide() {
        RectF out = new RectF();
        TouchButtonShape.shapeRect(out, 0f, 0f, 100f, 40f, TouchButtonShape.SQUARE);
        assertEquals("a square takes the shorter side", 40f, out.width(), 0.001f);
        assertEquals(40f, out.height(), 0.001f);
        assertEquals("and stays centred", 50f, out.centerX(), 0.001f);

        TouchButtonShape.shapeRect(out, 0f, 0f, 100f, 40f, TouchButtonShape.OVAL);
        assertEquals("an oval is not forced square", 100f, out.width(), 0.001f);
    }

    /** Every polygon contains its centre and rejects the corners of its box. */
    @Test
    public void polygonsContainCentreAndRejectBoxCorners() {
        for (int type = 0; type < TouchButtonShape.POLY_COUNT; ++type) {
            assertTrue("centre of polygon " + type, TouchButtonShape.contains(
                TouchButtonShape.POLYGON, type, 0f, rect, 50f, 50f));
            assertFalse("box corner of polygon " + type, TouchButtonShape.contains(
                TouchButtonShape.POLYGON, type, 0f, rect, 1f, 1f));
        }
    }

    @Test
    public void polygonSidesMatchTheirNames() {
        assertEquals(3, TouchButtonShape.sides(TouchButtonShape.POLY_TRIANGLE));
        assertEquals(5, TouchButtonShape.sides(TouchButtonShape.POLY_PENTAGON));
        assertEquals(6, TouchButtonShape.sides(TouchButtonShape.POLY_HEXAGON));
        assertEquals(8, TouchButtonShape.sides(TouchButtonShape.POLY_OCTAGON));
    }

    @Test
    public void shapeValidationRejectsOutOfRange() {
        assertTrue(TouchButtonShape.isValidShape(TouchButtonShape.POLYGON));
        assertFalse(TouchButtonShape.isValidShape(-1));
        assertFalse(TouchButtonShape.isValidShape(TouchButtonShape.SHAPE_COUNT));
        assertTrue(TouchButtonShape.isValidPolygon(TouchButtonShape.POLY_OCTAGON));
        assertFalse(TouchButtonShape.isValidPolygon(TouchButtonShape.POLY_COUNT));
    }
}
