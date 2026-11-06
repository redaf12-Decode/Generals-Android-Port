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

package com.generalsx.zerohour;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

final class TouchEditorBackgroundView extends View {

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

    TouchEditorBackgroundView(Context context) {
        super(context);
        // A background must never take a touch from the preview or the controls above it.
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        image = decode(context);
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
        if (image != null) {
            centreCrop(width, height);
            canvas.drawBitmap(image, source, destination, bitmapPaint);
        } else {
            canvas.drawColor(Color.rgb(12, 16, 20));
        }
        scrimPaint.setColor(Color.BLACK);
        scrimPaint.setAlpha(Math.round(255f * dimPercent / 100f));
        canvas.drawRect(0, 0, width, height, scrimPaint);
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
