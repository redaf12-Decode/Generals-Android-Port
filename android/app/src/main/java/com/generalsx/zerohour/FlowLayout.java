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

// GeneralsX @bugfix Android touch editor 06/11/2026 A row that wraps.
//
// The editor's inspector packs groups of equal-width cells into one horizontal LinearLayout: the
// six shape buttons, the four polygon buttons, the three mode buttons, the eight colour swatches,
// the six footer actions. On a phone held in portrait the inspector is only as wide as the screen,
// and a fixed row simply ran off the end -- the last cells were clipped, unreachable and drew over
// whatever was beside them. The fix is not to shrink the cells (the request is explicit about that)
// but to let the row wrap onto as many lines as it needs, which is what this ViewGroup does.
//
// It is a deliberately small layout: children keep their own measured width and are placed left to
// right until the next one would not fit, then a new line starts. Horizontal and vertical gaps are
// settable, and the whole thing honours the layout direction, so an Arabic (RTL) inspector reads
// right to left exactly as the rest of the screen does.

package com.generalsx.zerohour;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

final class FlowLayout extends ViewGroup {

    private int horizontalGap;
    private int verticalGap;
    FlowLayout(Context context) {
        super(context);
    }

    void setGaps(int horizontalPx, int verticalPx) {
        horizontalGap = Math.max(0, horizontalPx);
        verticalGap = Math.max(0, verticalPx);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int available = widthMode == MeasureSpec.UNSPECIFIED
            ? Integer.MAX_VALUE : Math.max(0, widthSize - getPaddingLeft() - getPaddingRight());

        int lineWidth = 0;
        int lineHeight = 0;
        int contentWidth = 0;
        int contentHeight = 0;
        boolean firstInLine = true;

        for (int i = 0; i < getChildCount(); ++i) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            measureChild(child, widthMeasureSpec, heightMeasureSpec);
            int childWidth = child.getMeasuredWidth();
            int childHeight = child.getMeasuredHeight();
            int needed = firstInLine ? childWidth : lineWidth + horizontalGap + childWidth;
            if (!firstInLine && needed > available) {
                contentWidth = Math.max(contentWidth, lineWidth);
                contentHeight += lineHeight + verticalGap;
                lineWidth = childWidth;
                lineHeight = childHeight;
            } else {
                lineWidth = needed;
                lineHeight = Math.max(lineHeight, childHeight);
            }
            firstInLine = false;
        }
        contentWidth = Math.max(contentWidth, lineWidth);
        contentHeight += lineHeight;

        int desiredWidth = contentWidth + getPaddingLeft() + getPaddingRight();
        int desiredHeight = contentHeight + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(
            widthMode == MeasureSpec.EXACTLY ? widthSize
                : resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int available = Math.max(0, getWidth() - getPaddingLeft() - getPaddingRight());
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        int y = getPaddingTop();
        int lineWidth = 0;
        int lineHeight = 0;
        int lineStart = 0;      // first child index of the current line
        boolean lineOpen = false;

        for (int i = 0; i < getChildCount(); ++i) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            int childWidth = child.getMeasuredWidth();
            int childHeight = child.getMeasuredHeight();
            if (lineOpen && lineWidth + horizontalGap + childWidth > available) {
                y = placeLine(lineStart, i, y, lineHeight, rtl);
                lineOpen = false;
                lineWidth = 0;
                lineHeight = 0;
            }
            if (!lineOpen) {
                lineStart = i;
                lineOpen = true;
                lineWidth = childWidth;
            } else {
                lineWidth += horizontalGap + childWidth;
            }
            lineHeight = Math.max(lineHeight, childHeight);
        }
        if (lineOpen) {
            placeLine(lineStart, getChildCount(), y, lineHeight, rtl);
        }
    }

    /**
     * Places the run [from, to) on one line and returns the top of the next line. The run is laid
     * out left to right, or right to left for an RTL layout, and each cell is vertically centred on
     * the line so a line of mixed heights still looks deliberate rather than ragged.
     */
    private int placeLine(int from, int to, int lineTop, int lineHeight, boolean rtl) {
        int width = 0;
        for (int i = from; i < to; ++i) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            width += child.getMeasuredWidth();
            if (i > from) {
                width += horizontalGap;
            }
        }
        int cursor = rtl ? getWidth() - getPaddingRight() - width : getPaddingLeft();
        for (int i = from; i < to; ++i) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            int childWidth = child.getMeasuredWidth();
            int childHeight = child.getMeasuredHeight();
            int childTop = lineTop + (lineHeight - childHeight) / 2;
            child.layout(cursor, childTop, cursor + childWidth, childTop + childHeight);
            cursor += childWidth + horizontalGap;
        }
        return lineTop + lineHeight + verticalGap;
    }
}
