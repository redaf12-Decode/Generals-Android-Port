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

// GeneralsX @feature Android touch editor 05/11/2026 The launcher's preview of the battlefield.
//
// The full-screen editor has a side inspector, so the area left for the canvas is narrower than
// the phone's screen. If the canvas were used directly as the editor's viewport, a button saved at
// 80% across would be drawn 80% across the *narrow* area -- a different place from where the game
// draws it, and one that moves whenever the inspector changes width. So the editor's viewport is
// the real landscape game screen, and this class is the letterboxed mapping from that screen into
// whatever area the canvas actually has.
//
// It is a pure transform: the canvas draws inside translate(offset) * scale(scale), and a touch is
// mapped back with the exact inverse. Nothing here knows about buttons, so the stored normalized
// coordinates never depend on the preview's size -- which is the property the tests pin down.

package com.generalsx.zerohour;

final class TouchPreviewViewport {

    private float scale = 1f;
    private float offsetX;
    private float offsetY;

    /**
     * Fits a game screen of <code>gameWidth x gameHeight</code> into the available area, keeping
     * its aspect ratio and centring it. The scale is the smaller of the two ratios, so the whole
     * game screen is always inside the area (letterboxed, never cropped). A degenerate size --
     * before the first layout, or a zero-sized screen -- leaves a 1:1 mapping rather than dividing
     * by zero.
     */
    void update(int availableWidth, int availableHeight, int gameWidth, int gameHeight) {
        if (availableWidth <= 0 || availableHeight <= 0 || gameWidth <= 0 || gameHeight <= 0) {
            scale = 1f;
            offsetX = 0f;
            offsetY = 0f;
            return;
        }
        scale = Math.min(availableWidth / (float) gameWidth,
            availableHeight / (float) gameHeight);
        offsetX = (availableWidth - gameWidth * scale) * 0.5f;
        offsetY = (availableHeight - gameHeight * scale) * 0.5f;
    }

    float scale() {
        return scale;
    }

    float offsetX() {
        return offsetX;
    }

    float offsetY() {
        return offsetY;
    }

    /** A game-screen pixel to a canvas pixel: the transform the canvas draws inside. */
    float toPreviewX(float gameX) {
        return gameX * scale + offsetX;
    }

    float toPreviewY(float gameY) {
        return gameY * scale + offsetY;
    }

    /** A canvas pixel back to a game-screen pixel: the inverse, for touch. */
    float toGameX(float previewX) {
        return scale <= 0f ? previewX : (previewX - offsetX) / scale;
    }

    float toGameY(float previewY) {
        return scale <= 0f ? previewY : (previewY - offsetY) / scale;
    }
}
