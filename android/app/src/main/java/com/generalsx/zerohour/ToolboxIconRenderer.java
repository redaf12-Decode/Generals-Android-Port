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

// GeneralsX @feature Android floating toolbox 18/10/2026 ONE place that decides what the
// floating bar's icon looks like: the geometry of the three styles, the three paints (surface
// fill, accent stroke, white toolbox glyph) and what the launcher's opacity setting means as
// an alpha. The game's ToolboxOverlayView draws through this class, and so does the launcher's
// ToolboxIconPreviewView, so the card in Setup and the bar over the battlefield are the same
// drawing by construction -- a style, colour or transparency change cannot reach one and not
// the other. (Before this, the launcher card only described the icon in words while the game
// drew it: nothing checked the two agreed, and the game screen did not even carry the
// launcher's accent until GeneralsZHActivity started applying it.)

package com.generalsx.zerohour;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

final class ToolboxIconRenderer {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Resolved once: the theme's surface colour does not change while a screen is up. */
    private final int fillColor;
    private final Path path = new Path();
    private final RectF glyphBody = new RectF();
    private final RectF glyphHandle = new RectF();

    ToolboxIconRenderer(Context context, int accent) {
        fillColor = UiKit.surfaceContainerHighColor(context);
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(context, 1.6f));
        stroke.setColor(accent);
        glyph.setStyle(Paint.Style.FILL);
        glyph.setColor(Color.WHITE);
    }

    /**
     * The 0..255 alpha a stored icon_opacity draws at: the single translation between the
     * launcher's percentage and what is painted, clamped through ToolboxConfig exactly as
     * load() clamps the stored value. The card's slider, the preview and the game all end here,
     * so one setting cannot produce three different transparencies.
     */
    static int alphaFor(float opacity) {
        return Math.round(255f * ToolboxConfig.clampOpacity(opacity));
    }

    /** The three styles' shapes for the caller's rect, into the path this renderer owns. */
    private void buildPath(RectF rect, int style) {
        path.reset();
        float side = rect.width();
        float cx = rect.centerX();
        float cy = rect.centerY();
        switch (ToolboxConfig.clampStyle(style)) {
            case ToolboxConfig.STYLE_CIRCLE:
                path.addOval(rect, Path.Direction.CW);
                break;
            case ToolboxConfig.STYLE_DIAMOND:
                path.moveTo(cx, rect.top);
                path.lineTo(rect.right, cy);
                path.lineTo(cx, rect.bottom);
                path.lineTo(rect.left, cy);
                path.close();
                break;
            case ToolboxConfig.STYLE_ROUNDED:
            default:
                float radius = side * 0.28f;
                path.addRoundRect(rect, radius, radius, Path.Direction.CW);
                break;
        }
    }

    /**
     * The icon: fill, accent stroke and glyph, all three at the alpha the opacity setting asks
     * for. Every object the draw needs is owned here, so a frame costs no allocation.
     */
    void draw(Canvas canvas, RectF rect, int style, float opacity) {
        int alpha = alphaFor(opacity);
        fill.setColor((fillColor & 0x00FFFFFF) | (alpha << 24));
        stroke.setAlpha(alpha);
        glyph.setAlpha(alpha);

        buildPath(rect, style);
        canvas.drawPath(path, fill);
        canvas.drawPath(path, stroke);
        drawGlyph(canvas, rect);
    }

    /**
     * A toolbox: a body with a handle above it -- the glyph inside the icon, scaled to the
     * rectangle it sits in, as it was when only the overlay drew it.
     */
    private void drawGlyph(Canvas canvas, RectF rect) {
        float side = rect.width();
        float cx = rect.centerX();
        float cy = rect.centerY();

        float bodyWidth = side * 0.54f;
        float bodyHeight = side * 0.30f;
        float radius = side * 0.06f;
        glyphBody.set(cx - bodyWidth / 2f, cy - bodyHeight * 0.1f,
            cx + bodyWidth / 2f, cy - bodyHeight * 0.1f + bodyHeight);
        canvas.drawRoundRect(glyphBody, radius, radius, glyph);

        float handleWidth = side * 0.30f;
        float handleHeight = side * 0.16f;
        glyphHandle.set(cx - handleWidth / 2f, glyphBody.top - handleHeight,
            cx + handleWidth / 2f, glyphBody.top + handleHeight * 0.4f);
        glyph.setStyle(Paint.Style.STROKE);
        glyph.setStrokeWidth(Math.max(1f, side * 0.045f));
        canvas.drawArc(glyphHandle, 180f, 180f, false, glyph);
        glyph.setStyle(Paint.Style.FILL);
    }

    private static float dp(Context context, float value) {
        return value * context.getResources().getDisplayMetrics().density;
    }
}