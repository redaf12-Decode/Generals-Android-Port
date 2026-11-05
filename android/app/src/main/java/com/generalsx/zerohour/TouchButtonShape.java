/*
**      Command & Conquer Generals Zero Hour(tm)
**      Copyright 2025 Electronic Arts Inc.
**
**      This program is free software: you can redistribute it and/or modify
**      it under the terms of the GNU General Public License as published by
**      the Free Software Foundation, either version 3 of the License, or
**      (at your option) any later version.
**
**      This program is distributed in the hope that it will be useful,
**      but WITHOUT ANY WARRANTY; without even the implied warranty of
**      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
**      GNU General Public License for more details.
**
**      You should have received a copy of the GNU General Public License
**      along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

// GeneralsX @feature Android touch editor 05/11/2026 The shapes an on-screen button can take,
// and the geometry that draws them and decides whether a finger is inside one.
//
// Two properties are deliberate:
//
//  * Nothing here allocates while a frame is drawn or a touch is tested. Drawing fills a Path the
//    caller owns and reuses; hit testing is analytic -- an ellipse test, a rounded-rect test, a
//    convex-polygon test -- so no Path is measured and no array is built per event. The polygon
//    corner tables are computed once, at class load, and are immutable afterwards.
//
//  * Hit testing follows the drawn shape, not its bounding box. A tap in the corner of a circle's
//    box must fall through to the battlefield, which is exactly the case the reference's
//    RectF-only test got wrong. The exception is the polygon, where the test is against the same
//    vertices the Path is built from, so the two cannot drift apart.
//
// The shape constants keep the reference's numbering (0..4) and append POLYGON as 5, so a layout
// saved by an older build loads with the same shape it was saved with.

package com.generalsx.zerohour;

import android.graphics.Path;
import android.graphics.RectF;

final class TouchButtonShape {

    static final int RECTANGLE = 0;
    static final int ROUNDED = 1;
    static final int SQUARE = 2;
    static final int CIRCLE = 3;
    static final int OVAL = 4;
    static final int POLYGON = 5;
    static final int SHAPE_COUNT = 6;

    static final int POLY_TRIANGLE = 0;
    static final int POLY_PENTAGON = 1;
    static final int POLY_HEXAGON = 2;
    static final int POLY_OCTAGON = 3;
    static final int POLY_COUNT = 4;

    /** Corner radius is a per-button setting, in dp, clamped to this range. */
    static final float MIN_CORNER_DP = 0f;
    static final float MAX_CORNER_DP = 60f;
    /** Used when a shape needs a radius and the button has none set. */
    static final float DEFAULT_CORNER_DP = 12f;

    private static final float[][] CORNER_COS = buildCos();
    private static final float[][] CORNER_SIN = buildSin();

    private TouchButtonShape() {
    }

    private static float[][] buildCos() {
        return new float[][] {cos(POLY_TRIANGLE), cos(POLY_PENTAGON), cos(POLY_HEXAGON),
            cos(POLY_OCTAGON)};
    }

    private static float[][] buildSin() {
        return new float[][] {sin(POLY_TRIANGLE), sin(POLY_PENTAGON), sin(POLY_HEXAGON),
            sin(POLY_OCTAGON)};
    }

    /**
     * Unit-circle vertices of one regular polygon, x values. The first vertex is at the top
     * (-90 degrees) so a triangle points up and an octagon sits flat, which is what the shapes
     * in the editor's picker show.
     */
    private static float[] cos(int polygonType) {
        int n = sides(polygonType);
        float[] values = new float[n];
        for (int i = 0; i < n; ++i) {
            values[i] = (float) Math.cos(-Math.PI / 2.0 + i * 2.0 * Math.PI / n);
        }
        return values;
    }

    private static float[] sin(int polygonType) {
        int n = sides(polygonType);
        float[] values = new float[n];
        for (int i = 0; i < n; ++i) {
            values[i] = (float) Math.sin(-Math.PI / 2.0 + i * 2.0 * Math.PI / n);
        }
        return values;
    }

    static int sides(int polygonType) {
        switch (polygonType) {
            case POLY_TRIANGLE: return 3;
            case POLY_PENTAGON: return 5;
            case POLY_HEXAGON: return 6;
            case POLY_OCTAGON: return 8;
            default: return 6;
        }
    }

    /** A square and a circle are one shape, not a rectangle with two numbers: width == height. */
    static boolean isFixedAspect(int shape) {
        return shape == SQUARE || shape == CIRCLE;
    }

    static boolean isValidShape(int shape) {
        return shape >= RECTANGLE && shape < SHAPE_COUNT;
    }

    static boolean isValidPolygon(int polygonType) {
        return polygonType >= POLY_TRIANGLE && polygonType < POLY_COUNT;
    }

    /** The polygon's vertices, for the editor to draw its picker swatches with. */
    static float[] polygonCos(int polygonType) {
        return CORNER_COS[isValidPolygon(polygonType) ? polygonType : POLY_HEXAGON];
    }

    static float[] polygonSin(int polygonType) {
        return CORNER_SIN[isValidPolygon(polygonType) ? polygonType : POLY_HEXAGON];
    }

    static String shapeName(int shape) {
        switch (shape) {
            case RECTANGLE: return "Rectangle";
            case ROUNDED: return "Rounded";
            case SQUARE: return "Square";
            case CIRCLE: return "Circle";
            case OVAL: return "Oval";
            case POLYGON: return "Polygon";
            default: return "Rounded";
        }
    }

    static String polygonName(int polygonType) {
        switch (polygonType) {
            case POLY_TRIANGLE: return "Triangle";
            case POLY_PENTAGON: return "Pentagon";
            case POLY_HEXAGON: return "Hexagon";
            case POLY_OCTAGON: return "Octagon";
            default: return "Hexagon";
        }
    }

    /**
     * The largest corner radius that still leaves the shape readable: half the shorter side.
     * Applied wherever a radius is used, so a stored value can never exceed the geometry.
     */
    static float clampCorner(float cornerPx, float width, float height) {
        float limit = Math.min(width, height) * 0.5f;
        if (cornerPx < 0f) {
            return 0f;
        }
        return Math.min(cornerPx, Math.max(0f, limit));
    }

    /**
     * The rectangle a shape is actually drawn in. Square and circle are centred in the button's
     * box and take its shorter side, so a resize that changes only one dimension moves the shape
     * instead of stretching it -- which is also what keeps the hit test honest.
     */
    static void shapeRect(RectF out, float left, float top, float right, float bottom, int shape) {
        if (isFixedAspect(shape)) {
            float side = Math.min(right - left, bottom - top);
            float cx = (left + right) * 0.5f;
            float cy = (top + bottom) * 0.5f;
            out.set(cx - side * 0.5f, cy - side * 0.5f, cx + side * 0.5f, cy + side * 0.5f);
        } else {
            out.set(left, top, right, bottom);
        }
    }

    /**
     * Fills <code>out</code> with the shape, reusing the caller's Path. No allocation: the same
     * Path object is reset and refilled on every frame.
     */
    static void buildPath(Path out, RectF rect, int shape, int polygonType, float cornerPx) {
        out.reset();
        switch (shape) {
            case CIRCLE:
                out.addOval(rect, Path.Direction.CW);
                break;
            case OVAL:
                out.addOval(rect, Path.Direction.CW);
                break;
            case SQUARE:
            case RECTANGLE:
                out.addRect(rect, Path.Direction.CW);
                break;
            case POLYGON: {
                float[] cs = polygonCos(polygonType);
                float[] sn = polygonSin(polygonType);
                float cx = rect.centerX();
                float cy = rect.centerY();
                float rx = rect.width() * 0.5f;
                float ry = rect.height() * 0.5f;
                for (int i = 0; i < cs.length; ++i) {
                    float x = cx + rx * cs[i];
                    float y = cy + ry * sn[i];
                    if (i == 0) {
                        out.moveTo(x, y);
                    } else {
                        out.lineTo(x, y);
                    }
                }
                out.close();
                break;
            }
            case ROUNDED:
            default: {
                float radius = clampCorner(cornerPx, rect.width(), rect.height());
                if (radius <= 0f) {
                    out.addRect(rect, Path.Direction.CW);
                } else {
                    out.addRoundRect(rect, radius, radius, Path.Direction.CW);
                }
                break;
            }
        }
    }

    /**
     * Is the point inside the shape drawn in <code>rect</code>? Analytic and allocation-free.
     * Used for both the runtime hit test and the editor's selection, so what the player can press
     * is exactly what the player can see.
     */
    static boolean contains(int shape, int polygonType, float cornerPx, RectF rect,
                            float px, float py) {
        if (rect.width() <= 0f || rect.height() <= 0f) {
            return false;
        }
        switch (shape) {
            case CIRCLE:
                return containsEllipse(rect, px, py);
            case OVAL:
                return containsEllipse(rect, px, py);
            case POLYGON:
                return containsPolygon(polygonType, rect, px, py);
            case ROUNDED:
                return containsRounded(rect, cornerPx, px, py);
            case SQUARE:
            case RECTANGLE:
            default:
                return rect.contains(px, py);
        }
    }

    private static boolean containsEllipse(RectF rect, float px, float py) {
        float rx = rect.width() * 0.5f;
        float ry = rect.height() * 0.5f;
        float u = (px - rect.centerX()) / rx;
        float v = (py - rect.centerY()) / ry;
        return u * u + v * v <= 1f;
    }

    /**
     * A rounded rectangle is the inner rectangle plus its four corner discs. Testing it that way
     * avoids building a Region per event, which is what an earlier draft did and what made a
     * layout drag allocate.
     */
    private static boolean containsRounded(RectF rect, float cornerPx, float px, float py) {
        float radius = clampCorner(cornerPx, rect.width(), rect.height());
        if (radius <= 0f) {
            return rect.contains(px, py);
        }
        if (!rect.contains(px, py)) {
            return false;
        }
        float innerLeft = rect.left + radius;
        float innerRight = rect.right - radius;
        float innerTop = rect.top + radius;
        float innerBottom = rect.bottom - radius;
        if (px >= innerLeft && px <= innerRight) {
            return true;
        }
        if (py >= innerTop && py <= innerBottom) {
            return true;
        }
        float cx = px < innerLeft ? innerLeft : innerRight;
        float cy = py < innerTop ? innerTop : innerBottom;
        float dx = px - cx;
        float dy = py - cy;
        return dx * dx + dy * dy <= radius * radius;
    }

    /**
     * Convex point-in-polygon: the point is inside when it is on the same side of every edge.
     * The point is first mapped into the unit circle the vertices live on, so a wide button gets
     * a wide polygon without a second vertex table.
     */
    private static boolean containsPolygon(int polygonType, RectF rect, float px, float py) {
        float rx = rect.width() * 0.5f;
        float ry = rect.height() * 0.5f;
        if (rx <= 0f || ry <= 0f) {
            return false;
        }
        float u = (px - rect.centerX()) / rx;
        float v = (py - rect.centerY()) / ry;
        float[] cs = polygonCos(polygonType);
        float[] sn = polygonSin(polygonType);
        int count = cs.length;
        boolean hasPositive = false;
        boolean hasNegative = false;
        for (int i = 0; i < count; ++i) {
            int j = (i + 1) % count;
            float edgeX = cs[j] - cs[i];
            float edgeY = sn[j] - sn[i];
            float cross = edgeX * (v - sn[i]) - edgeY * (u - cs[i]);
            if (cross > 1e-6f) {
                hasPositive = true;
            } else if (cross < -1e-6f) {
                hasNegative = true;
            }
            if (hasPositive && hasNegative) {
                return false;
            }
        }
        return true;
    }
}
