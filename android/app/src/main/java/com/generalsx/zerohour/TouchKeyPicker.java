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

// GeneralsX @feature Android touch editor 05/11/2026 The key-selection menu that Add (+) opens.
//
// It is one model for both hosts: the launcher shows it as a dialog, the in-game editor draws it
// above the toolbar, and both read their keys from TouchKeyCatalog -- there is deliberately no
// second keyboard list here. What this class adds is the geometry of a scrolling grid and the
// "which cell was tapped" test, so the in-game menu's hit testing is testable without a screen.
//
// Each group starts on its own row and one blank row separates two groups, so Letters, Numbers,
// Modifiers, Special keys and Function keys read as five blocks in the same order in both editors.
// Rows and columns are precomputed once, because the catalog is a constant.

package com.generalsx.zerohour;

import android.graphics.RectF;

import java.util.List;

final class TouchKeyPicker {

    /** The grid's shape. Five columns matches the launcher's chip rows. */
    static final int COLUMNS = 5;

    /** A blank row drawn between two groups, so the categories stay readable in the grid. */
    private static final int GROUP_GAP_ROWS = 1;

    private final List<TouchKeyCatalog.Entry> entries = TouchKeyCatalog.all();
    /** The grid row and column of each entry, precomputed once: the catalog never changes. */
    private final int[] rows = new int[entries.size()];
    private final int[] columns = new int[entries.size()];
    private final int rowCount;

    private float cellWidth;
    private float cellHeight;
    private float scroll;

    TouchKeyPicker() {
        int row = 0;
        for (int group = 0; group < TouchKeyCatalog.GROUP_COUNT; ++group) {
            if (group > 0) {
                row += GROUP_GAP_ROWS;
            }
            int column = 0;
            for (int i = 0; i < entries.size(); ++i) {
                if (entries.get(i).group != group) {
                    continue;
                }
                rows[i] = row;
                columns[i] = column;
                if (++column >= COLUMNS) {
                    column = 0;
                    ++row;
                }
            }
            if (column != 0) {
                // The group ended mid-row; the next one starts on the row after it.
                ++row;
            }
        }
        rowCount = row;
    }

    int count() {
        return entries.size();
    }

    int rowCount() {
        return rowCount;
    }

    TouchKeyCatalog.Entry entry(int index) {
        return entries.get(index);
    }

    /** Lays the grid into the area the host gives it. Called every frame the menu is drawn. */
    void layout(float width, float cellHeightPx) {
        this.cellWidth = width / COLUMNS;
        this.cellHeight = cellHeightPx;
    }

    float contentHeight() {
        return rowCount * cellHeight;
    }

    float scroll() {
        return scroll;
    }

    float maxScroll(float viewportHeight) {
        return Math.max(0f, contentHeight() - viewportHeight);
    }

    void setScroll(float value, float viewportHeight) {
        scroll = TouchControlConfig.clamp(value, 0f, maxScroll(viewportHeight));
    }

    void scrollBy(float delta, float viewportHeight) {
        setScroll(scroll + delta, viewportHeight);
    }

    /** The cell a grid position occupies, in content coordinates (before the scroll is applied). */
    void cellBounds(int index, RectF out) {
        out.set(columns[index] * cellWidth, rows[index] * cellHeight,
            (columns[index] + 1) * cellWidth, (rows[index] + 1) * cellHeight);
    }

    /**
     * The entry under a point in the menu's own area, with the scroll applied. -1 when the point is
     * on a gap, past the last key, or outside the grid. This is what makes a tap in the in-game
     * menu resolve to exactly one key and never to a button underneath it.
     */
    int hitTest(float x, float y, float viewportHeight) {
        if (cellWidth <= 0f || cellHeight <= 0f) {
            return -1;
        }
        // A cast truncates toward zero, so a point just left of or above the grid (x in
        // (-cellWidth, 0)) would otherwise land in column or row 0 -- a tap on the margin would
        // silently choose the first key. Reject the negative side explicitly.
        float contentY = y + scroll;
        if (x < 0f || contentY < 0f) {
            return -1;
        }
        int column = (int) (x / cellWidth);
        if (column < 0 || column >= COLUMNS) {
            return -1;
        }
        int row = (int) (contentY / cellHeight);
        if (row < 0 || row >= rowCount) {
            return -1;
        }
        for (int i = 0; i < entries.size(); ++i) {
            if (rows[i] == row && columns[i] == column) {
                return i;
            }
        }
        return -1;
    }
}
