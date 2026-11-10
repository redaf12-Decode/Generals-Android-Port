// GeneralsX @build Android touch editor 05/11/2026
//
// The button editor's key list is an acceptance criterion, so it is asserted rather than assumed:
// every key the task names is offered by the picker, the combinations the task names compose the
// way the editor shows them, and the keys the picker must refuse stay refused. A layout is
// untrusted input, so isSelectable() is the guard that keeps a corrupt document from injecting a
// key the picker deliberately withholds.

package com.ports.generalsx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class TouchKeyCatalogTest {

    /** Letters and digits: A-Z and 0-9, all selectable. */
    @Test
    public void lettersAndDigitsAreAllOffered() {
        for (int code = KeyEvent.KEYCODE_A; code <= KeyEvent.KEYCODE_Z; ++code) {
            assertTrue("letter " + code, TouchKeyCatalog.isSelectable(code));
        }
        for (int code = KeyEvent.KEYCODE_0; code <= KeyEvent.KEYCODE_9; ++code) {
            assertTrue("digit " + code, TouchKeyCatalog.isSelectable(code));
        }
        assertEquals("A", TouchKeyCatalog.displayName(KeyEvent.KEYCODE_A));
        assertEquals("7", TouchKeyCatalog.displayName(KeyEvent.KEYCODE_7));
    }

    /** Every modifier the task names, sided and unsided, is offered. */
    @Test
    public void everyNamedModifierIsOffered() {
        int[] modifiers = {KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT};
        for (int code : modifiers) {
            assertTrue("modifier " + code, TouchKeyCatalog.isSelectable(code));
            assertTrue("modifier " + code + " is recognised as one",
                TouchKeyCatalog.isModifierKey(code));
        }
        // The unsided names the editor shows resolve to a physical side, which is what a press
        // injects: Ctrl is the left Ctrl, as on a PC keyboard.
        assertEquals(KeyEvent.KEYCODE_CTRL_LEFT, TouchKeyCatalog.modifierKeyCode(TouchKeyCatalog.MOD_CTRL));
        assertEquals(KeyEvent.KEYCODE_SHIFT_LEFT, TouchKeyCatalog.modifierKeyCode(TouchKeyCatalog.MOD_SHIFT));
        assertEquals(KeyEvent.KEYCODE_ALT_LEFT, TouchKeyCatalog.modifierKeyCode(TouchKeyCatalog.MOD_ALT));
    }

    /** The named special keys and F1-F12 are all offered. */
    @Test
    public void namedSpecialKeysAndFunctionKeysAreOffered() {
        int[] specials = {KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL,
            KeyEvent.KEYCODE_INSERT, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT};
        for (int code : specials) {
            assertTrue("special " + code, TouchKeyCatalog.isSelectable(code));
        }
        for (int code = KeyEvent.KEYCODE_F1; code <= KeyEvent.KEYCODE_F12; ++code) {
            assertTrue("function " + code, TouchKeyCatalog.isSelectable(code));
        }
        assertEquals("F12", TouchKeyCatalog.displayName(KeyEvent.KEYCODE_F12));
    }

    /** The combinations the task names compose the way the editor shows them. */
    @Test
    public void namedCombinationsCompose() {
        assertEquals("Ctrl+A", TouchKeyCatalog.combinationName(KeyEvent.KEYCODE_A,
            TouchKeyCatalog.MOD_CTRL));
        assertEquals("Ctrl+C", TouchKeyCatalog.combinationName(KeyEvent.KEYCODE_C,
            TouchKeyCatalog.MOD_CTRL));
        assertEquals("Ctrl+V", TouchKeyCatalog.combinationName(KeyEvent.KEYCODE_V,
            TouchKeyCatalog.MOD_CTRL));
        assertEquals("Alt+ENTER", TouchKeyCatalog.combinationName(KeyEvent.KEYCODE_ENTER,
            TouchKeyCatalog.MOD_ALT));
        assertEquals("Shift+TAB", TouchKeyCatalog.combinationName(KeyEvent.KEYCODE_TAB,
            TouchKeyCatalog.MOD_SHIFT));
        // No modifier means the key alone, with no leading "+".
        assertEquals("A", TouchKeyCatalog.combinationName(KeyEvent.KEYCODE_A, 0));
    }

    /** Alt+Tab is composed but flagged, because the engine swallows the Tab while Alt is down. */
    @Test
    public void altTabIsComposedAndFlaggedAsSwallowed() {
        assertEquals("Alt+TAB",
            TouchKeyCatalog.combinationName(KeyEvent.KEYCODE_TAB, TouchKeyCatalog.MOD_ALT));
        assertTrue(TouchKeyCatalog.isSwallowedByEngine(KeyEvent.KEYCODE_TAB,
            TouchKeyCatalog.MOD_ALT));
        assertFalse("a plain Tab is not swallowed",
            TouchKeyCatalog.isSwallowedByEngine(KeyEvent.KEYCODE_TAB, 0));
        assertFalse("Shift+Tab is not swallowed",
            TouchKeyCatalog.isSwallowedByEngine(KeyEvent.KEYCODE_TAB,
                TouchKeyCatalog.MOD_SHIFT));
    }

    /**
     * The keys the picker must refuse stay refused, whatever a stored document says. Home and
     * Recents, Power, the volume rocker, mute and the call keys belong to the OS, and Back is
     * mapped to Escape by SDL already, so nothing is lost by refusing it.
     */
    @Test
    public void unsafeKeysStayRefused() {
        int[] refused = {KeyEvent.KEYCODE_UNKNOWN, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_CALL, KeyEvent.KEYCODE_ENDCALL, KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_MUTE,
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_APP_SWITCH};
        for (int code : refused) {
            assertFalse("refused " + code, TouchKeyCatalog.isSelectable(code));
        }
    }

    /** The picker's groups partition the list: every entry belongs to exactly one of them. */
    @Test
    public void groupsPartitionTheCatalog() {
        int counted = 0;
        for (int group = 0; group < TouchKeyCatalog.GROUP_COUNT; ++group) {
            counted += TouchKeyCatalog.group(group).size();
        }
        assertEquals("no entry is missing from a group and none is in two",
            TouchKeyCatalog.all().size(), counted);
        assertTrue("the letter tab is not empty", TouchKeyCatalog.group(
            TouchKeyCatalog.GROUP_LETTER).size() >= 26);
    }
}
