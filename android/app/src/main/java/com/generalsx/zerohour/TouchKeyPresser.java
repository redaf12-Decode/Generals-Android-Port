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

// GeneralsX @feature Android touch editor 05/11/2026 Turns a finger on a button into keyboard
// events, and is the only place that decides when a key goes down and when it comes back up.
//
// Why this is its own class: "no stuck keys" is a correctness property, not a UI detail. A key
// that goes down and never comes up holds a game binding for the rest of the session -- a stuck
// Ctrl turns every later tap into a Ctrl-click. So the rule is kept in one place, with one owner:
//
//  * Every key this presses is remembered, per button and with a reference count, so a key shared
//    by two buttons (Ctrl on two different combinations) is not released while the other still
//    holds it.
//  * The three activation modes are real, not a delay dressed up as one:
//      - Tap:    a short KeyDown/KeyUp pair. The short hold is the point -- it is what a keypress
//                is; a zero-length press would be dropped by the engine's per-frame polling.
//      - Hold:   KeyDown now, KeyUp when the finger actually lifts. Nothing is scheduled.
//      - Toggle: KeyDown on the first press, KeyUp on the next.
//  * Modifiers are pressed before the primary key and released after it, in reverse order, so the
//    engine sees "Ctrl is down" when it resolves the key. Keyboard::update latches the modifier
//    and folds it into that frame's events -- see the audit report, section 3.
//  * releaseAll() exists and is called on every way a gesture can be interrupted: the editor
//    closing, the activity pausing, the window losing focus, a button being switched off. There
//    is no path out of this class that leaves a key down.
//
// The engine's own Alt+Tab suppression (Keyboard.cpp:151) is respected, not worked around: an
// Alt+Tab button presses real keys and the game ignores the Tab, which is what the PC client does
// too. TouchKeyCatalog.isSwallowedByEngine() is what the editor warns with.

package com.generalsx.zerohour;

import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class TouchKeyPresser {

    /** Where the events go. The real one is SDLActivity.onNativeKeyDown/Up; a test records them. */
    interface Sink {
        void keyDown(int keyCode);

        void keyUp(int keyCode);
    }

    /** Deferred work, so a Tap's release can be scheduled and a test can run it by hand. */
    interface Scheduler {
        void postDelayed(Runnable task, long delayMs);

        void cancel(Runnable task);
    }

    /**
     * How long a Tap holds the key. Long enough for the engine's per-frame keyboard poll to see
     * the down and the up in different frames, short enough that it reads as a tap. This is the
     * reference's value.
     */
    static final long TAP_HOLD_MS = 42L;

    /** The modifier press order, and the reverse of the release order. */
    private static final int[] MODIFIER_ORDER = {
        TouchKeyCatalog.MOD_CTRL, TouchKeyCatalog.MOD_SHIFT, TouchKeyCatalog.MOD_ALT
    };

    private final Sink sink;
    private final Scheduler scheduler;

    /** keyCode -> how many buttons currently hold it down. */
    private final Map<Integer, Integer> heldRefCount = new HashMap<>();
    /** Button index -> the keys that button is holding, in press order. */
    private final Map<Integer, List<Integer>> heldByButton = new HashMap<>();
    /** Buttons latched on by Toggle. */
    private final List<Integer> toggledOn = new ArrayList<>();
    /** The pending Tap release per button, so a second press can cancel the first's timer. */
    private final Map<Integer, Runnable> pendingTapRelease = new HashMap<>();

    TouchKeyPresser(Sink sink, Scheduler scheduler) {
        this.sink = sink;
        this.scheduler = scheduler;
    }

    /** The keycodes a button presses, modifiers first. */
    static int[] keysFor(TouchControlConfig.ButtonSpec spec) {
        List<Integer> keys = new ArrayList<>(4);
        for (int flag : MODIFIER_ORDER) {
            if ((spec.modifiers & flag) != 0) {
                keys.add(TouchKeyCatalog.modifierKeyCode(flag));
            }
        }
        // A button whose primary key IS a modifier presses that key on its own; the flags above
        // are a separate thing, so a standalone "R-Ctrl" button needs no special case here.
        if (spec.keyCode != KeyEvent.KEYCODE_UNKNOWN) {
            keys.add(spec.keyCode);
        }
        int[] result = new int[keys.size()];
        for (int i = 0; i < result.length; ++i) {
            result[i] = keys.get(i);
        }
        return result;
    }

    /** A finger went down on a button. */
    void press(int buttonIndex, TouchControlConfig.ButtonSpec spec) {
        switch (spec.activationMode) {
            case TouchControlConfig.MODE_HOLD:
                pressKeys(buttonIndex, keysFor(spec));
                break;
            case TouchControlConfig.MODE_TOGGLE:
                if (toggledOn.contains(buttonIndex)) {
                    toggledOn.remove((Integer) buttonIndex);
                    releaseButton(buttonIndex);
                } else {
                    toggledOn.add(buttonIndex);
                    pressKeys(buttonIndex, keysFor(spec));
                }
                break;
            case TouchControlConfig.MODE_TAP:
            default: {
                // A press while a previous Tap is still held: let the first finish, then tap
                // again, rather than stacking two unmatched downs.
                cancelPendingTap(buttonIndex);
                final int index = buttonIndex;
                pressKeys(buttonIndex, keysFor(spec));
                Runnable release = () -> {
                    pendingTapRelease.remove(index);
                    releaseButton(index);
                };
                pendingTapRelease.put(buttonIndex, release);
                scheduler.postDelayed(release, TAP_HOLD_MS);
                break;
            }
        }
    }

    /** The finger lifted off a button. A Tap has already scheduled its own release. */
    void release(int buttonIndex, TouchControlConfig.ButtonSpec spec) {
        switch (spec.activationMode) {
            case TouchControlConfig.MODE_HOLD:
                releaseButton(buttonIndex);
                break;
            case TouchControlConfig.MODE_TOGGLE:
                // Stays down until pressed again -- that is what Toggle means.
                break;
            case TouchControlConfig.MODE_TAP:
            default:
                break;
        }
    }

    /** True while this button is holding a key down, for the view to draw the pressed state. */
    boolean isButtonDown(int buttonIndex) {
        return heldByButton.containsKey(buttonIndex);
    }

    /**
     * Releases everything: a button switched off, the editor closing, the activity pausing, the
     * window losing focus. Safe to call at any time, including when nothing is held.
     */
    void releaseAll() {
        for (Runnable task : new ArrayList<>(pendingTapRelease.values())) {
            scheduler.cancel(task);
        }
        pendingTapRelease.clear();
        toggledOn.clear();
        for (Integer index : new ArrayList<>(heldByButton.keySet())) {
            releaseButton(index);
        }
        heldByButton.clear();
        heldRefCount.clear();
    }

    private void cancelPendingTap(int buttonIndex) {
        Runnable task = pendingTapRelease.remove(buttonIndex);
        if (task != null) {
            scheduler.cancel(task);
        }
    }

    private void pressKeys(int buttonIndex, int[] keyCodes) {
        List<Integer> held = heldByButton.get(buttonIndex);
        if (held == null) {
            held = new ArrayList<>(keyCodes.length);
            heldByButton.put(buttonIndex, held);
        }
        for (int keyCode : keyCodes) {
            Integer count = heldRefCount.get(keyCode);
            int current = count == null ? 0 : count;
            if (current == 0) {
                // Only the first holder sends the event, so two buttons sharing Ctrl do not send
                // it twice -- the engine would see a second down with no up for it.
                sink.keyDown(keyCode);
            }
            heldRefCount.put(keyCode, current + 1);
            held.add(keyCode);
        }
    }

    private void releaseButton(int buttonIndex) {
        List<Integer> held = heldByButton.remove(buttonIndex);
        if (held == null) {
            return;
        }
        // Reverse order: the primary key comes up before the modifiers that were held for it.
        for (int i = held.size() - 1; i >= 0; --i) {
            int keyCode = held.get(i);
            Integer count = heldRefCount.get(keyCode);
            int current = count == null ? 0 : count;
            if (current <= 1) {
                heldRefCount.remove(keyCode);
                sink.keyUp(keyCode);
            } else {
                heldRefCount.put(keyCode, current - 1);
            }
        }
    }
}
