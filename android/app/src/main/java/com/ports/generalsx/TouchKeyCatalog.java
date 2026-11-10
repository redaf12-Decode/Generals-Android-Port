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

// GeneralsX @feature Android touch editor 05/11/2026 The keys an on-screen button may press.
//
// One rule decides what belongs here: the button presses a real keyboard key through
// SDLActivity.onNativeKeyDown/Up, so a key is only useful if the engine's own keyboard path
// resolves it. Every entry below was checked against SDL 3.4.2's Android keycode table
// (src/video/android/SDL_androidkeyboard.c) and this tree's
// SDL3Keyboard::translateScanCodeToKeyVal -- see
// docs/WORKDIR/reports/TOUCH_EDITOR_AUDIT_20261105.md section 3 for the full chain and the table.
//
// Android keycodes are NOT SDL scancodes and are not stable across platforms; the translation
// happens once, in SDL, and nothing here assumes the two numbering schemes agree. What is stored
// in a layout is the Android keycode, because that is what SDLActivity.onNativeKeyDown takes.
//
// Modifiers are stored separately from the primary key (TouchControlConfig.ButtonSpec.modifiers),
// never folded into a combined keycode: the engine composes the combination itself --
// Keyboard::update latches KEY_LCTRL/KEY_LALT/KEY_LSHIFT into m_modifiers and BitSets them into
// that frame's events, which is what getModifierFlags() reports to the command translator.

package com.ports.generalsx;

import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class TouchKeyCatalog {

    /** Groups are the picker's tabs, in this order. */
    static final int GROUP_LETTER = 0;
    static final int GROUP_DIGIT = 1;
    static final int GROUP_MODIFIER = 2;
    static final int GROUP_SPECIAL = 3;
    static final int GROUP_FUNCTION = 4;
    static final int GROUP_COUNT = 5;

    /** One selectable key. */
    static final class Entry {
        final int keyCode;
        final String name;
        final int group;

        Entry(int keyCode, String name, int group) {
            this.keyCode = keyCode;
            this.name = name;
            this.group = group;
        }
    }

    /**
     * Modifier flags stored per button. Deliberately a small bitmask rather than "which keycode
     * was picked", so a layout means the same thing whatever the side: MOD_CTRL is "Ctrl", and
     * modifierKeyCode() resolves it to a physical side at press time.
     */
    static final int MOD_CTRL = 1;
    static final int MOD_SHIFT = 2;
    static final int MOD_ALT = 4;
    static final int MOD_MASK = MOD_CTRL | MOD_SHIFT | MOD_ALT;

    /**
     * GeneralsX @bugfix Android touch editor 05/11/2026 Keys the picker must not offer as a
     * button's primary key, because injecting them through onNativeKeyDown skips the system
     * handling they exist for and would fight the OS rather than the game: Home and Recents
     * (the launcher), Power, the volume rocker, mute, and the call keys. Escape covers "the key
     * that opens the pause menu" -- SDL maps the phone's Back gesture to KEY_ESC already
     * (SDL3Keyboard.cpp), so nothing is lost by refusing KEYCODE_BACK here.
     */
    private static final int[] UNSAFE_STANDALONE = {
        KeyEvent.KEYCODE_HOME,        // 3
        KeyEvent.KEYCODE_BACK,        // 4
        KeyEvent.KEYCODE_CALL,        // 5
        KeyEvent.KEYCODE_ENDCALL,     // 6
        KeyEvent.KEYCODE_VOLUME_UP,   // 24
        KeyEvent.KEYCODE_VOLUME_DOWN, // 25
        KeyEvent.KEYCODE_POWER,       // 26
        KeyEvent.KEYCODE_MUTE,        // 91
        KeyEvent.KEYCODE_MENU,        // 82
        KeyEvent.KEYCODE_APP_SWITCH,  // 187
        KeyEvent.KEYCODE_SOFT_LEFT,   // 1
        KeyEvent.KEYCODE_SOFT_RIGHT,  // 2
    };

    private static final List<Entry> ALL = build();

    private static List<Entry> build() {
        ArrayList<Entry> list = new ArrayList<>();
        for (int code = KeyEvent.KEYCODE_A; code <= KeyEvent.KEYCODE_Z; ++code) {
            list.add(new Entry(code, String.valueOf((char) ('A' + code - KeyEvent.KEYCODE_A)),
                GROUP_LETTER));
        }
        for (int code = KeyEvent.KEYCODE_0; code <= KeyEvent.KEYCODE_9; ++code) {
            list.add(new Entry(code, String.valueOf((char) ('0' + code - KeyEvent.KEYCODE_0)),
                GROUP_DIGIT));
        }
        // The nine modifier entries the editor offers. The sided ones are what a player picks
        // when a game shortcut distinguishes them; the unsided ones are the common case and are
        // resolved to a physical side by modifierKeyCode().
        list.add(new Entry(KeyEvent.KEYCODE_CTRL_LEFT, "Ctrl", GROUP_MODIFIER));
        list.add(new Entry(KeyEvent.KEYCODE_CTRL_LEFT, "L-Ctrl", GROUP_MODIFIER));
        list.add(new Entry(KeyEvent.KEYCODE_CTRL_RIGHT, "R-Ctrl", GROUP_MODIFIER));
        list.add(new Entry(KeyEvent.KEYCODE_ALT_LEFT, "Alt", GROUP_MODIFIER));
        list.add(new Entry(KeyEvent.KEYCODE_ALT_LEFT, "L-Alt", GROUP_MODIFIER));
        list.add(new Entry(KeyEvent.KEYCODE_ALT_RIGHT, "R-Alt", GROUP_MODIFIER));
        list.add(new Entry(KeyEvent.KEYCODE_SHIFT_LEFT, "Shift", GROUP_MODIFIER));
        list.add(new Entry(KeyEvent.KEYCODE_SHIFT_LEFT, "L-Shift", GROUP_MODIFIER));
        list.add(new Entry(KeyEvent.KEYCODE_SHIFT_RIGHT, "R-Shift", GROUP_MODIFIER));

        list.add(new Entry(KeyEvent.KEYCODE_TAB, "TAB", GROUP_SPECIAL));
        list.add(new Entry(KeyEvent.KEYCODE_SPACE, "SPACE", GROUP_SPECIAL));
        list.add(new Entry(KeyEvent.KEYCODE_ENTER, "ENTER", GROUP_SPECIAL));
        list.add(new Entry(KeyEvent.KEYCODE_ESCAPE, "ESC", GROUP_SPECIAL));
        // Android KEYCODE_DEL is the backspace key; KEYCODE_FORWARD_DEL is Delete. Getting these
        // the wrong way round is the classic port bug -- the names below are what each key does.
        list.add(new Entry(KeyEvent.KEYCODE_DEL, "BACKSP", GROUP_SPECIAL));
        list.add(new Entry(KeyEvent.KEYCODE_FORWARD_DEL, "DEL", GROUP_SPECIAL));
        list.add(new Entry(KeyEvent.KEYCODE_INSERT, "INS", GROUP_SPECIAL));
        list.add(new Entry(KeyEvent.KEYCODE_MOVE_HOME, "HOME", GROUP_SPECIAL));
        list.add(new Entry(KeyEvent.KEYCODE_MOVE_END, "END", GROUP_SPECIAL));
        list.add(new Entry(KeyEvent.KEYCODE_PAGE_UP, "PGUP", GROUP_SPECIAL));
        list.add(new Entry(KeyEvent.KEYCODE_PAGE_DOWN, "PGDN", GROUP_SPECIAL));
        list.add(new Entry(KeyEvent.KEYCODE_DPAD_UP, "UP", GROUP_SPECIAL));
        list.add(new Entry(KeyEvent.KEYCODE_DPAD_DOWN, "DOWN", GROUP_SPECIAL));
        list.add(new Entry(KeyEvent.KEYCODE_DPAD_LEFT, "LEFT", GROUP_SPECIAL));
        list.add(new Entry(KeyEvent.KEYCODE_DPAD_RIGHT, "RIGHT", GROUP_SPECIAL));

        for (int code = KeyEvent.KEYCODE_F1; code <= KeyEvent.KEYCODE_F12; ++code) {
            list.add(new Entry(code, "F" + (code - KeyEvent.KEYCODE_F1 + 1), GROUP_FUNCTION));
        }
        return Collections.unmodifiableList(list);
    }

    private TouchKeyCatalog() {
    }

    static List<Entry> all() {
        return ALL;
    }

    static List<Entry> group(int group) {
        ArrayList<Entry> result = new ArrayList<>();
        for (Entry entry : ALL) {
            if (entry.group == group) {
                result.add(entry);
            }
        }
        return result;
    }

    /** The key's short display name, or a readable fallback for a code we do not list. */
    static String displayName(int keyCode) {
        for (Entry entry : ALL) {
            if (entry.keyCode == keyCode) {
                return entry.name;
            }
        }
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            return "?";
        }
        return "K" + keyCode;
    }

    /**
     * GeneralsX @feature Android touch editor 06/11/2026 True when a button presses no key. This is
     * the one place the "cleared" state is defined: it is KeyEvent.KEYCODE_UNKNOWN, which is what
     * SDLActivity.onNativeKeyDown never receives and what TouchKeyPresser skips, so an unassigned
     * button cannot send input and cannot fall back to a default key.
     */
    static boolean isUnassigned(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_UNKNOWN;
    }

    /**
     * GeneralsX @feature Android touch editor 06/11/2026 The label shown for a button whose key was
     * cleared, read from the app's own resources so it is translated like every other label. It is
     * resolved at draw time rather than stored, so a layout cleared in one language still reads
     * correctly after the language is changed.
     */
    static String unassignedName(android.content.Context context) {
        return context.getString(R.string.touch_key_unassigned);
    }

    /**
     * GeneralsX @bugfix Android touch editor 05/11/2026 A stored layout is untrusted input: it
     * may have been hand-edited, or written by a build whose key list differed. A code that is
     * not in the catalog is replaced by the caller's fallback rather than injected, so a corrupt
     * layout cannot press a key the picker deliberately refuses.
     */
    static boolean isSelectable(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            return false;
        }
        for (int unsafe : UNSAFE_STANDALONE) {
            if (unsafe == keyCode) {
                return false;
            }
        }
        return true;
    }

    /**
     * The physical key that realises a stored modifier flag. The unsided flag resolves to the
     * left key, which is what a PC keyboard's "Ctrl" is; a button whose primary key is itself a
     * modifier uses that exact keycode instead of this.
     */
    static int modifierKeyCode(int singleFlag) {
        switch (singleFlag) {
            case MOD_CTRL: return KeyEvent.KEYCODE_CTRL_LEFT;
            case MOD_SHIFT: return KeyEvent.KEYCODE_SHIFT_LEFT;
            case MOD_ALT: return KeyEvent.KEYCODE_ALT_LEFT;
            default: return KeyEvent.KEYCODE_UNKNOWN;
        }
    }

    /**
     * True when the keycode is one of the modifier keys. Such a button presses exactly the key
     * it names (so "R-Ctrl" is possible on its own) rather than a key plus a modifier.
     */
    static boolean isModifierKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_CTRL_LEFT || keyCode == KeyEvent.KEYCODE_CTRL_RIGHT
            || keyCode == KeyEvent.KEYCODE_ALT_LEFT || keyCode == KeyEvent.KEYCODE_ALT_RIGHT
            || keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT;
    }

    /** "Ctrl+Shift+S" as the editor shows it; empty modifier mask gives just the key. */
    static String combinationName(int keyCode, int modifiers) {
        StringBuilder text = new StringBuilder();
        if ((modifiers & MOD_CTRL) != 0) {
            text.append("Ctrl+");
        }
        if ((modifiers & MOD_SHIFT) != 0) {
            text.append("Shift+");
        }
        if ((modifiers & MOD_ALT) != 0) {
            text.append("Alt+");
        }
        return text.append(displayName(keyCode)).toString();
    }

    /**
     * GeneralsX @feature Android touch editor 06/11/2026 The combination as shown to the player,
     * with an unassigned button named by its translated caption rather than the "?" placeholder
     * displayName() falls back to. Both editors use this so a cleared button reads the same in
     * either one.
     */
    static String combinationName(android.content.Context context, int keyCode, int modifiers) {
        return isUnassigned(keyCode) ? unassignedName(context) : combinationName(keyCode, modifiers);
    }

    /**
     * GeneralsX @bugfix Android touch editor 05/11/2026 The engine deliberately swallows a TAB
     * while an Alt key is down -- Keyboard.cpp marks it STATUS_USED so that Alt+Tab does not reach
     * the game. A button configured Alt+Tab therefore presses real keys and the game ignores the
     * Tab, which is engine behaviour and not something the overlay can or should undo. The editor
     * uses this to warn instead of implying the combination works.
     */
    static boolean isSwallowedByEngine(int keyCode, int modifiers) {
        return keyCode == KeyEvent.KEYCODE_TAB && (modifiers & MOD_ALT) != 0;
    }
}
