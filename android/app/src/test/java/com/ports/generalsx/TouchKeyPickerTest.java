// GeneralsX @build Android touch editor 05/11/2026
//
// The two pieces the keyboard-selection menu and the launcher preview are built on. Both are pure
// geometry, so they are tested directly -- a tap resolving to the wrong key, or the preview's
// inverse disagreeing with its forward transform, are exactly the failures that are invisible in a
// screenshot and obvious in a phone's hand.

package com.ports.generalsx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.RectF;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class TouchKeyPickerTest {

    /** Every catalog key is in the grid exactly once: no key is unreachable, none is duplicated. */
    @Test
    public void everyCatalogKeyIsInTheGridOnce() {
        TouchKeyPicker picker = new TouchKeyPicker();
        assertEquals(TouchKeyCatalog.all().size(), picker.count());
        boolean[] seen = new boolean[picker.count()];
        RectF cell = new RectF();
        picker.layout(500f, 40f);
        for (int i = 0; i < picker.count(); ++i) {
            picker.cellBounds(i, cell);
            int hit = picker.hitTest(cell.centerX(), cell.centerY(), 400f);
            assertEquals("cell " + i + " maps back to itself", i, hit);
            assertFalse("two keys share a cell", seen[hit]);
            seen[hit] = true;
        }
    }

    /** The grid is grouped in the catalog's own order: letters, then numbers, then the rest. */
    @Test
    public void theGroupsRunInCatalogOrder() {
        TouchKeyPicker picker = new TouchKeyPicker();
        picker.layout(500f, 40f);
        RectF previous = new RectF();
        RectF cell = new RectF();
        int lastGroup = -1;
        for (int i = 0; i < picker.count(); ++i) {
            TouchKeyCatalog.Entry entry = picker.entry(i);
            assertTrue("a group never appears after a later one", entry.group >= lastGroup);
            picker.cellBounds(i, cell);
            if (entry.group != lastGroup) {
                if (lastGroup >= 0) {
                    assertTrue("a new group starts on a later row",
                        cell.top >= previous.top);
                }
                lastGroup = entry.group;
            }
            previous.set(cell);
        }
        assertEquals("the last group is the function keys",
            TouchKeyCatalog.GROUP_FUNCTION, lastGroup);
    }

    /** A point outside the grid, or past the last key, resolves to nothing. */
    @Test
    public void hitTestRejectsGapsAndTheOutside() {
        TouchKeyPicker picker = new TouchKeyPicker();
        picker.layout(500f, 40f);
        assertEquals(-1, picker.hitTest(-1f, 10f, 400f));
        assertEquals(-1, picker.hitTest(501f, 10f, 400f));
        assertEquals(-1, picker.hitTest(10f, -1f, 400f));
        // Past the end of the content: rows beyond the catalog hold no entry.
        assertEquals(-1, picker.hitTest(10f, picker.rowCount() * 40f + 20f, 400f));
    }

    /** The scroll is clamped to the content: it never shows blank space above or below. */
    @Test
    public void theScrollIsClampedToTheContent() {
        TouchKeyPicker picker = new TouchKeyPicker();
        picker.layout(500f, 40f);
        float viewport = 100f;
        picker.scrollBy(-500f, viewport);
        assertEquals("cannot scroll above the first row", 0f, picker.scroll(), 0.0001f);
        picker.scrollBy(10000f, viewport);
        assertEquals("cannot scroll past the last row",
            picker.contentHeight() - viewport, picker.scroll(), 0.0001f);
    }

    /** The preview keeps the game screen's aspect ratio, centred, and its inverse round-trips. */
    @Test
    public void thePreviewLetterboxesAndItsInverseRoundTrips() {
        TouchPreviewViewport preview = new TouchPreviewViewport();
        // A 2:1 game screen into a 1:1 area: the width is the limiting ratio, so it letterboxes
        // vertically.
        preview.update(1000, 1000, 2000, 1000);
        assertEquals(0.5f, preview.scale(), 0.0001f);
        assertEquals(0f, preview.offsetX(), 0.0001f);
        assertEquals("the game screen is centred vertically", 250f, preview.offsetY(), 0.0001f);
        assertEquals(500f, preview.toPreviewX(1000f), 0.0001f);
        assertEquals("500 game pixels down is 250 into the drawn screen plus the letterbox",
            500f, preview.toPreviewY(500f), 0.0001f);
        assertEquals(1000f, preview.toGameX(preview.toPreviewX(1000f)), 0.0001f);
        assertEquals(500f, preview.toGameY(preview.toPreviewY(500f)), 0.0001f);
    }

    /** A degenerate size maps 1:1 instead of dividing by zero, so the first frame is safe. */
    @Test
    public void aDegenerateSizeMapsOneToOne() {
        TouchPreviewViewport preview = new TouchPreviewViewport();
        preview.update(0, 0, 2000, 1000);
        assertEquals(1f, preview.scale(), 0.0001f);
        assertEquals(0f, preview.offsetX(), 0.0001f);
        assertEquals(7f, preview.toGameX(7f), 0.0001f);
        preview.update(1000, 500, 0, 0);
        assertEquals(1f, preview.scale(), 0.0001f);
        assertEquals(7f, preview.toGameY(7f), 0.0001f);
    }
}
