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

// GeneralsX @feature Android floating toolbox 18/10/2026 The bar, in the launcher: the same
// ToolboxIconRenderer the game overlay draws with, at the configured style, size and opacity,
// so the card in Setup previews the EXACT icon the battlefield will show and the transparency
// slider below moves it as it is dragged. Dimmed while the toolbox is off -- the game builds
// no icon at all then, and a full-strength preview would promise something that does not
// exist -- but still showing what turning the switch on would give.

package com.generalsx.zerohour;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.View;

final class ToolboxIconPreviewView extends View {

    /**
     * Tag on the preview: a card built in code has no resource id, so the test finds it the
     * same way it finds SetupActivity.TOOLBOX_CARD_TAG.
     */
    static final String PREVIEW_TAG = "gx_toolbox_preview";

    /**
     * How hard the preview is dimmed while the toolbox is off. This view-level alpha answers
     * "is the toolbox on"; the rendered alpha inside the drawing answers "what transparency
     * did the player choose". Two different states -- they are deliberately not the same
     * number, so no test and no player can mistake one for the other.
     */
    static final float DISABLED_PREVIEW_ALPHA = 0.4f;

    /** Breathing room so the accent stroke never touches the view's own measuring edge. */
    private static final float PADDING_DP = 4f;

    private final ToolboxIconRenderer renderer;
    private final RectF rect = new RectF();
    private ToolboxConfig config;

    ToolboxIconPreviewView(Context context, int accent) {
        super(context);
        renderer = new ToolboxIconRenderer(context, accent);
        setWillNotDraw(false);
        setTag(PREVIEW_TAG);
        setContentDescription(context.getString(R.string.toolbox_icon_description));
    }

    /** The card owns one ToolboxConfig for the whole form; every control writes through it. */
    void setConfig(ToolboxConfig config) {
        this.config = config;
        invalidate();
    }

    /** Redraw after the card's controls changed the stored settings. */
    void refresh() {
        invalidate();
    }

    /** Off means the game builds no icon: dim the preview, but keep showing the choice. */
    void setToolboxEnabled(boolean enabled) {
        setAlpha(enabled ? 1f : DISABLED_PREVIEW_ALPHA);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        float side = sidePx();
        int size = (int) Math.ceil(side + dp(PADDING_DP) * 2f);
        setMeasuredDimension(resolveSize(size, widthSpec), resolveSize(size, heightSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (config == null) {
            return;
        }
        float side = sidePx();
        rect.set((getWidth() - side) / 2f, (getHeight() - side) / 2f,
            (getWidth() + side) / 2f, (getHeight() + side) / 2f);
        renderer.draw(canvas, rect, config.iconStyle, config.iconOpacity);
    }

    /** The configured side in pixels -- the value the overlay lays its iconRect out with. */
    private float sidePx() {
        return dp(previewedSideDp());
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    // ------------------------------------------------------------- test-facing

    /** What this preview is drawing, so SetupShellLayoutTest can assert the card follows the file. */
    int previewedStyle() {
        return config == null
            ? ToolboxConfig.STYLE_ROUNDED : ToolboxConfig.clampStyle(config.iconStyle);
    }

    float previewedSideDp() {
        return config == null
            ? ToolboxConfig.SIZE_MEDIUM_DP : ToolboxConfig.clampSize(config.iconSizeDp);
    }

    int previewedAlpha() {
        return ToolboxIconRenderer.alphaFor(
            config == null ? ToolboxConfig.DEFAULT_OPACITY : config.iconOpacity);
    }
}