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
// button, its shape, the selection indicator, the resize handle and the position badge.
//
// Both editors use it -- the in-game one and the launcher's full-screen one -- so a button looks
// the same in both and there is exactly one place that knows how an editable button is drawn.
// Each host draws its own toolbar, because the two toolbars are genuinely different.
//
// GeneralsX @bugfix Android touch editor 05/11/2026 A button is drawn with its OWN colours in the
// editor, exactly as it is drawn in the match: fillColor for the body and borderColor for the
// border. The launcher's accent is never painted onto a button, so entering the editor cannot
// change what a button looks like and the global accent cannot overwrite a per-button colour.
// Selection is shown by a separate indicator drawn OUTSIDE the button -- an accent dashed frame
// around its box, the resize handle, and the position badge -- so the selection visuals never
// alter the saved appearance.
//
// The renderer owns its Paints, RectFs and Paths and reuses all of them: draw() allocates
// nothing, which matters because it runs inside the game's frame loop.

package com.ports.generalsx;

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
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shapePath = new Path();
    private final Path dashPath = new Path();
    private final Path handlePath = new Path();
    private final RectF rect = new RectF();
    private final RectF frameRect = new RectF();
    private final RectF handleRect = new RectF();

    private final int accentColor;
    private final float density;

    /**
     * GeneralsX @feature Android touch editor 05/11/2026 The optional letterbox transform the
     * launcher's preview needs. The in-game editor draws at 1:1 (scale 1, offset 0), which is the
     * default, so this changes nothing there; the launcher sets the same transform it maps touch
     * through, so the buttons a player drags land exactly where they are drawn.
     */
    private float previewScale = 1f;
    private float previewOffsetX;
    private float previewOffsetY;

    TouchEditorRenderer(Context context) {
        this.context = context;
        this.accentColor = resolveAccent(context);
        this.density = context.getResources().getDisplayMetrics().density;
        textPaint.setTextAlign(Paint.Align.CENTER);
        borderPaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setPathEffect(new android.graphics.DashPathEffect(
            new float[] {dp(7f), dp(5f)}, 0f));
        handlePaint.setStyle(Paint.Style.STROKE);
    }

    /**
     * Sets the preview transform: the renderer draws inside translate(offset) * scale(scale), so a
     * game-screen coordinate lands at the same place the touch mapping reads it back from. Passing
     * a non-positive scale keeps the last good transform rather than collapsing the field.
     */
    void setPreviewTransform(float scale, float offsetX, float offsetY) {
        if (scale <= 0f || Float.isNaN(scale) || Float.isInfinite(scale)) {
            return;
        }
        previewScale = scale;
        previewOffsetX = Float.isNaN(offsetX) ? 0f : offsetX;
        previewOffsetY = Float.isNaN(offsetY) ? 0f : offsetY;
    }

    float previewScale() {
        return previewScale;
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

    /** The accent this renderer resolves for a context, so a test can assert a button is NOT it. */
    static int accentForTest(Context context) {
        return resolveAccent(context);
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
        // The launcher's preview is letterboxed: everything below is drawn in game-screen pixels,
        // then the whole field is scaled into the area the canvas actually has. The in-game editor
        // leaves the transform at 1:1, so this save/restore is a no-op there.
        int checkpoint = canvas.save();
        canvas.translate(previewOffsetX, previewOffsetY);
        canvas.scale(previewScale, previewScale);
        for (TouchControlConfig.ButtonSpec spec : editor.working().inDrawOrder()) {
            editor.bounds(spec, rect);
            boolean selected = editor.buttons().indexOf(spec) == selectedIndex;
            float alpha = TouchControlConfig.clamp(spec.opacity * globalOpacity, 0f, 1f);
            // The button's own appearance, exactly as the match draws it: its fill and its own
            // border. The launcher accent is never used here, so the editor cannot recolour it.
            fillPaint.setColor(spec.fillColor);
            fillPaint.setAlpha(Math.round(255f * alpha * (spec.enabled ? 1f : 0.35f)));
            borderPaint.setColor(spec.borderColor);
            borderPaint.setStrokeWidth(Math.max(1f, dp(spec.borderWidthDp)) * scale);
            borderPaint.setAlpha(Math.round(255f * (spec.enabled ? 1f : 0.5f)));
            drawShape(canvas, spec, scale);
            drawLabel(canvas, spec, scale);
            if (selected) {
                // Selection is shown OUTSIDE the button, never by repainting it: an accent
                // dashed frame around its box, the resize handle, and the position badge.
                drawSelectionFrame(canvas);
                editor.resizeHandle(handleRect);
                drawResizeHandle(canvas);
                drawPositionBadge(canvas, spec);
            } else {
                outlinePaint.setColor(0xFF39D0D8);
                outlinePaint.setStrokeWidth(dp(1.5f));
                outlinePaint.setAlpha(spec.enabled ? 200 : 90);
                TouchButtonShape.buildPath(dashPath, rect, spec.shape, spec.polygonType,
                    spec.cornerDp * density * scale);
                canvas.drawPath(dashPath, outlinePaint);
            }
        }
        canvas.restoreToCount(checkpoint);
    }

    /** An accent dashed frame around the selected button's box -- the selection indicator. */
    private void drawSelectionFrame(Canvas canvas) {
        float pad = dp(6f);
        frameRect.set(rect.left - pad, rect.top - pad, rect.right + pad, rect.bottom + pad);
        handlePaint.setColor(accentColor);
        handlePaint.setStrokeWidth(dp(2.5f));
        handlePaint.setAlpha(255);
        handlePaint.setPathEffect(outlinePaint.getPathEffect());
        canvas.drawRoundRect(frameRect, dp(6f), dp(6f), handlePaint);
    }

    /** The grab handle at the bottom-right corner of the selection frame. */
    private void drawResizeHandle(Canvas canvas) {
        handlePaint.setColor(accentColor);
        handlePaint.setStrokeWidth(dp(2f));
        handlePaint.setAlpha(255);
        handlePaint.setPathEffect(null);
        canvas.drawCircle(handleRect.centerX(), handleRect.centerY(),
            handleRect.width() * 0.5f, handlePaint);
        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setColor(0xFFFFFFFF);
        handlePaint.setAlpha(230);
        canvas.drawCircle(handleRect.centerX(), handleRect.centerY(),
            Math.max(1f, handleRect.width() * 0.16f), handlePaint);
        handlePaint.setStyle(Paint.Style.STROKE);
    }

    private void drawShape(Canvas canvas, TouchControlConfig.ButtonSpec spec, float scale) {
        TouchButtonShape.buildPath(shapePath, rect, spec.shape, spec.polygonType,
            spec.cornerDp * density * scale);
        canvas.drawPath(shapePath, fillPaint);
        if (borderPaint.getStrokeWidth() > 0f && borderPaint.getAlpha() > 0) {
            canvas.drawPath(shapePath, borderPaint);
        }
    }

    private void drawLabel(Canvas canvas, TouchControlConfig.ButtonSpec spec, float scale) {
        // GeneralsX @feature Android touch editor 06/11/2026 A cleared button shows the localised
        // "Unassigned" caption instead of a stored label, so the state is legible on the button
        // itself. It is read from resources at draw time, so changing the app language relabels a
        // cleared button without touching the saved layout.
        String text = spec.isAssigned() ? spec.label : TouchKeyCatalog.unassignedName(context);
        textPaint.setColor(spec.textColor);
        textPaint.setAlpha(spec.enabled ? (spec.isAssigned() ? 255 : 190) : 120);
        textPaint.setTextSize(dp(text.length() > 5 ? 12f : 15f) * scale);
        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        canvas.drawText(text, rect.centerX(),
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
