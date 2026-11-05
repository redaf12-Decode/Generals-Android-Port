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

// GeneralsX @feature Android touch editor 05/11/2026 Draws a button layout in edit mode: every
// button, its shape, the selection ring, the resize handle and the position badge.
//
// Both editors use it -- the in-game one and the launcher's full-screen one -- so a button looks
// the same in both and there is exactly one place that knows how an editable button is drawn.
// Each host draws its own toolbar, because the two toolbars are genuinely different.
//
// The renderer owns its Paints, RectFs and Paths and reuses all of them: draw() allocates
// nothing, which matters because it runs inside the game's frame loop.

package com.generalsx.zerohour;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

final class TouchEditorRenderer {

    private final Context context;
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shapePath = new Path();
    private final Path dashPath = new Path();
    private final RectF rect = new RectF();
    private final RectF handleRect = new RectF();

    private final int accentColor;

    TouchEditorRenderer(Context context) {
        this.context = context;
        this.accentColor = resolveAccent(context);
        textPaint.setTextAlign(Paint.Align.CENTER);
        borderPaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setPathEffect(new android.graphics.DashPathEffect(
            new float[] {dp(7f), dp(5f)}, 0f));
    }

    private static int resolveAccent(Context context) {
        try {
            return ThemeHelper.swatchColor(context, ThemeHelper.getAccent(context));
        } catch (Exception e) {
            return Color.rgb(220, 181, 86);
        }
    }

    int accentColor() {
        return accentColor;
    }

    private float dp(float value) {
        return value * context.getResources().getDisplayMetrics().density;
    }

    /**
     * Draws the whole editable field. A host that wants an overlay may draw it after this call;
     * everything here is inside the layout's own buttons.
     */
    void draw(Canvas canvas, TouchLayoutEditor editor) {
        int selectedIndex = editor.selectedIndex();
        float scale = editor.working().buttonScale;
        float globalOpacity = editor.working().buttonOpacity;
        for (TouchControlConfig.ButtonSpec spec : editor.working().inDrawOrder()) {
            editor.bounds(spec, rect);
            boolean selected = editor.buttons().indexOf(spec) == selectedIndex;
            float alpha = TouchControlConfig.clamp(spec.opacity * globalOpacity, 0f, 1f);
            fillPaint.setColor(spec.fillColor);
            fillPaint.setAlpha(Math.round(255f * alpha * (spec.enabled ? 1f : 0.35f)));
            drawShape(canvas, spec, scale);
            if (selected) {
                borderPaint.setColor(accentColor);
                borderPaint.setStrokeWidth(dp(3f));
                borderPaint.setAlpha(255);
                drawShape(canvas, spec, scale);
            } else {
                outlinePaint.setColor(0xFF39D0D8);
                outlinePaint.setStrokeWidth(dp(1.5f));
                outlinePaint.setAlpha(spec.enabled ? 200 : 90);
                TouchButtonShape.buildPath(dashPath, rect, spec.shape, spec.polygonType,
                    spec.cornerDp * density() * scale);
                canvas.drawPath(dashPath, outlinePaint);
            }
            drawLabel(canvas, spec, scale);
            if (selected) {
                editor.resizeHandle(handleRect);
                badgePaint.setColor(accentColor);
                badgePaint.setAlpha(230);
                canvas.drawCircle(handleRect.centerX(), handleRect.centerY(),
                    handleRect.width() * 0.5f, badgePaint);
                drawPositionBadge(canvas, spec);
            }
        }
    }

    private float density() {
        return context.getResources().getDisplayMetrics().density;
    }

    private void drawShape(Canvas canvas, TouchControlConfig.ButtonSpec spec, float scale) {
        TouchButtonShape.buildPath(shapePath, rect, spec.shape, spec.polygonType,
            spec.cornerDp * density() * scale);
        canvas.drawPath(shapePath, fillPaint);
        if (borderPaint.getStrokeWidth() > 0f && borderPaint.getAlpha() > 0) {
            canvas.drawPath(shapePath, borderPaint);
        }
    }

    private void drawLabel(Canvas canvas, TouchControlConfig.ButtonSpec spec, float scale) {
        textPaint.setColor(spec.textColor);
        textPaint.setAlpha(spec.enabled ? 255 : 120);
        textPaint.setTextSize(dp(spec.label.length() > 5 ? 12f : 15f) * scale);
        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        canvas.drawText(spec.label, rect.centerX(),
            rect.centerY() - (metrics.ascent + metrics.descent) * 0.5f, textPaint);
    }

    /** The badge is only drawn in the editor, so the string it builds never runs in a match. */
    private void drawPositionBadge(Canvas canvas, TouchControlConfig.ButtonSpec spec) {
        textPaint.setTextSize(dp(11f));
        String badge = Math.round(spec.x * 100f) + "% , " + Math.round(spec.y * 100f) + "%";
        float width = textPaint.measureText(badge) + dp(12f);
        float left = rect.centerX() - width * 0.5f;
        float top = rect.top - dp(26f);
        badgePaint.setColor(0xCC000000);
        badgePaint.setAlpha(0xCC);
        canvas.drawRoundRect(left, top, left + width, top + dp(20f), dp(4f), dp(4f), badgePaint);
        textPaint.setColor(0xFFE8F6F7);
        textPaint.setAlpha(255);
        canvas.drawText(badge, left + width * 0.5f, top + dp(14f), textPaint);
    }
}
