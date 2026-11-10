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

// GeneralsX @feature Android touch editor 06/11/2026 The touch editor's background image.
//
// The editor draws on a picture of the game rather than a flat dark panel, so the page reads as the
// battlefield the layout will appear over. Three things matter and none of them is "just set it as
// the window background":
//
//  * Proportion. A plain ImageView scales the image to the view, so a 1024x768 picture on a tall
//    phone is stretched vertically and the composition distorts. This view CENTRE-CROPS: it scales
//    by the larger of the two ratios and crops the overflow, so the picture keeps its aspect ratio
//    and fills the view with no empty bands. It never scales a pixel up by more than 1:1 unless the
//    view is genuinely larger than the source, which is the one case where there is nothing better
//    to do.
//  * Readability. The picture is bright enough in places to swallow white text and to hide a
//    button's own border, so a translucent dark scrim is painted over it. Its opacity is a stored
//    setting (the editor's Dim slider) with a sane default, so the page is legible out of the box
//    and can be darkened further by a player who wants it.
//  * Touch. The background is a plain View that does not consume touches, and it sits at the back
//    of the editor's root, so a press passes straight through to the preview canvas and the
//    controls. Nothing here is focusable or clickable.
//
// The image is bundled in res/drawable-nodpi (touch_editor_background.jpg) and decoded once, on
// demand; there is no runtime download anywhere in this class.
//
// GeneralsX @bugfix Android touch editor 07/11/2026 The picture is confined to the button layout
// area. The view used to be the whole window's first child, so the picture sat behind the header,
// the inspector, the sliders and the footer as well as behind the buttons. It is now attached
// inside the preview canvas only (TouchControlsActivity.buildLayout), and setCropToBounds(true)
// makes it clip and fill exactly that rectangle -- the picture can never bleed into the settings
// column beside it, whatever shape the canvas takes after a rotation or a fold.

package com.ports.generalsx;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

final class TouchEditorBackgroundView extends View {

    /** The colour used when no picture decoded. */
    private static final int FALLBACK_COLOR = Color.rgb(12, 16, 20);

    /** The default scrim: enough to read white text and see a cyan/yellow border over the picture. */
    static final int DEFAULT_DIM_PERCENT = 62;
    static final int MIN_DIM_PERCENT = 0;
    static final int MAX_DIM_PERCENT = 92;

    private final Bitmap image;
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint scrimPaint = new Paint();
    private final Rect source = new Rect();
    private final Rect destination = new Rect();

    private int dimPercent = DEFAULT_DIM_PERCENT;

    /**
     * GeneralsX @bugfix Android touch editor 07/11/2026 Whether the picture is clipped and filled
     * to this view's own bounds. True inside the button layout area, where the picture must stop at
     * the canvas' edge. The default false draws without a clip, which is what a bare view (a test's,
     * or any future full-bleed host) wants.
     */
    private boolean cropToBounds;

    TouchEditorBackgroundView(Context context) {
        super(context);
        // A background must never take a touch from the preview or the controls above it.
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        image = decode(context);
    }

    /**
     * Clips the picture to this view's rectangle and paints the whole rectangle, so the image can
     * never extend past the button layout's edge. Off by default (the picture fills whatever area
     * the view is given, which for a full-page background is the page).
     */
    void setCropToBounds(boolean crop) {
        if (cropToBounds != crop) {
            cropToBounds = crop;
            invalidate();
        }
    }

    /** The bundled picture, or null when it is missing -- the view then draws only the scrim. */
    private static Bitmap decode(Context context) {
        try {
            return BitmapFactory.decodeResource(context.getResources(),
                R.drawable.touch_editor_background);
        } catch (Exception e) {
            return null;
        }
    }

    /** True when the bundled image decoded, for a test and for a defensive fallback colour. */
    boolean hasImage() {
        return image != null;
    }

    /** Whether the picture is clipped to this view's bounds, for a test of the layout area. */
    boolean cropToBoundsForTest() {
        return cropToBounds;
    }

    /** The scrim's opacity as a percentage; clamped, and the whole point of the Dim control. */
    void setDimPercent(int percent) {
        int clamped = TouchControlConfig.clamp(percent, MIN_DIM_PERCENT, MAX_DIM_PERCENT);
        if (clamped != dimPercent) {
            dimPercent = clamped;
            invalidate();
        }
    }

    int dimPercent() {
        return dimPercent;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        int checkpoint = canvas.save();
        if (cropToBounds) {
            // Hard clip: even a source/destination pair that would overflow the view cannot paint
            // outside the button layout's rectangle.
            canvas.clipRect(0, 0, width, height);
        }
        if (image != null) {
            centreCrop(width, height);
            canvas.drawBitmap(image, source, destination, bitmapPaint);
        } else {
            canvas.drawColor(FALLBACK_COLOR);
        }
        scrimPaint.setColor(Color.BLACK);
        scrimPaint.setAlpha(Math.round(255f * dimPercent / 100f));
        canvas.drawRect(0, 0, width, height, scrimPaint);
        canvas.restoreToCount(checkpoint);
    }

    /**
     * Computes the centre-crop rectangles: the largest part of the source that has the view's
     * aspect ratio, drawn into the whole view. Scaling by max() (rather than min()) is what makes it
     * fill the view; taking the centred sub-rectangle of the other axis is what keeps the picture
     * undistorted. With no upscale when the source is already large enough to cover the view, and a
     * 1:1-ish fallback when it is not, so a small source on a big screen is blurry but never
     * stretched.
     */
    private void centreCrop(int width, int height) {
        int imageWidth = image.getWidth();
        int imageHeight = image.getHeight();
        float viewAspect = width / (float) height;
        float imageAspect = imageWidth / (float) imageHeight;

        if (imageAspect > viewAspect) {
            // The picture is wider than the view: crop the left and right.
            int cropWidth = Math.round(imageHeight * viewAspect);
            int left = (imageWidth - cropWidth) / 2;
            source.set(left, 0, left + cropWidth, imageHeight);
        } else {
            // The picture is taller than the view: crop the top and bottom.
            int cropHeight = Math.round(imageWidth / viewAspect);
            int top = (imageHeight - cropHeight) / 2;
            source.set(0, top, imageWidth, top + cropHeight);
        }
        destination.set(0, 0, width, height);
    }
}
