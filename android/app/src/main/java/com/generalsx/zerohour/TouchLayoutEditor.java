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

// GeneralsX @feature Android touch editor 05/11/2026 The edit session: which button is selected,
// where a finger drag puts it, how a resize handle changes it, and what save() would write.
//
// This class has no View and draws nothing, so the arithmetic is testable without a screen. Both
// editors use it -- the launcher's full-screen one and the in-game one -- so a button moves the
// same way in both.
//
// Three rules are built in, and each one has a test:
//
//  * An edit never changes what a button DOES. Moving and resizing rewrite x/y/widthDp/heightDp
//    and nothing else, so a button keeps pressing the key it always did.
//  * Edits happen on a copy. Only save() writes, so Cancel, Back, closing the editor and any
//    interruption leave the stored layout untouched.
//  * Every value is validated where it is produced, so a button cannot store a NaN or a
//    coordinate outside the screen fraction 0..1 -- whatever a finger does, including a drag past
//    the edge. What is deliberately NOT clamped is the button's own box: a button may hang off
//    the screen, sit in a corner, lie over the HUD or overlap another button, and it is never
//    nudged back into a "safe" area. The fraction is the position; the box is drawn from it.
//
// Dragging is absolute, not incremental: beginDrag() records where inside the button the finger
// landed and dragTo() places the centre from the finger's current position minus that offset. An
// incremental `centre += delta` accumulates rounding and, worse, drifts whenever a clamp trims a
// step the finger has already made -- the button then lags behind the finger for the rest of the
// gesture. Absolute placement cannot drift, and a clamp only limits how far it can go.

package com.generalsx.zerohour;

import android.content.Context;
import android.view.KeyEvent;

import java.util.List;

final class TouchLayoutEditor {

    /** Touch radius of the resize handle, in dp. Comfortably above a finger's slop. */
    static final float HANDLE_DP = 34f;

    /** What the editor needs to know about the screen it is editing on. */
    static final class Viewport {
        int width;
        int height;
        float density = 1f;
        float scale = 1f;

        void set(int width, int height, float density, float scale) {
            this.width = width;
            this.height = height;
            this.density = density > 0f ? density : 1f;
            this.scale = scale > 0f ? scale : 1f;
        }
    }

    private final Viewport viewport = new Viewport();
    private TouchControlConfig working;
    private TouchControlConfig baseline;
    private int selected = -1;

    /**
     * GeneralsX @feature Android touch editor 05/11/2026 The bottom strip a new button must stay
     * out of, in pixels: the in-game editor's toolbar sits there, and a button dropped underneath
     * it would be invisible and uneditable. The host sets it (0 when it has no toolbar), so the
     * placement rule itself lives here, once, for both entry points.
     */
    private float reservedBottomPx;

    /** Where the finger grabbed the button, relative to its centre, in pixels. */
    private float grabDx;
    private float grabDy;
    /** The edge a resize is anchored to, in pixels: the corner opposite the handle. */
    private float resizeAnchorX;
    private float resizeAnchorY;

    TouchLayoutEditor(TouchControlConfig source) {
        this.working = TouchControlConfig.copyOf(source);
        this.baseline = TouchControlConfig.copyOf(source);
        for (int i = 0; i < working.buttons.size(); ++i) {
            working.buttons.get(i).zOrder = i;
        }
    }

    Viewport viewport() {
        return viewport;
    }

    TouchControlConfig working() {
        return working;
    }

    List<TouchControlConfig.ButtonSpec> buttons() {
        return working.buttons;
    }

    int selectedIndex() {
        return selected;
    }

    TouchControlConfig.ButtonSpec selectedButton() {
        if (selected < 0 || selected >= working.buttons.size()) {
            return null;
        }
        return working.buttons.get(selected);
    }

    /** True when the working copy differs from what was loaded. */
    boolean isDirty() {
        if (working.enabled != baseline.enabled
                || Math.abs(working.buttonScale - baseline.buttonScale) > 0.0001f
                || Math.abs(working.buttonOpacity - baseline.buttonOpacity) > 0.0001f
                || working.buttons.size() != baseline.buttons.size()) {
            return true;
        }
        for (int i = 0; i < working.buttons.size(); ++i) {
            if (differs(working.buttons.get(i), baseline.buttons.get(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean differs(TouchControlConfig.ButtonSpec a,
                                   TouchControlConfig.ButtonSpec b) {
        return a.enabled != b.enabled
            || a.keyCode != b.keyCode
            || a.modifiers != b.modifiers
            || Math.abs(a.x - b.x) > 0.0001f
            || Math.abs(a.y - b.y) > 0.0001f
            || Math.abs(a.widthDp - b.widthDp) > 0.0001f
            || Math.abs(a.heightDp - b.heightDp) > 0.0001f
            || a.shape != b.shape
            || a.polygonType != b.polygonType
            || Math.abs(a.cornerDp - b.cornerDp) > 0.0001f
            || Math.abs(a.borderWidthDp - b.borderWidthDp) > 0.0001f
            || Math.abs(a.opacity - b.opacity) > 0.0001f
            || a.activationMode != b.activationMode
            || a.zOrder != b.zOrder
            || a.fillColor != b.fillColor
            || a.borderColor != b.borderColor
            || a.textColor != b.textColor
            || !a.label.equals(b.label);
    }

    // ------------------------------------------------------------------ geometry

    /** Pixel width and height of a button, after density and scale. */
    float pixelWidth(TouchControlConfig.ButtonSpec spec) {
        return spec.widthDp * viewport.density * viewport.scale;
    }

    float pixelHeight(TouchControlConfig.ButtonSpec spec) {
        return spec.heightDp * viewport.density * viewport.scale;
    }

    float centerX(TouchControlConfig.ButtonSpec spec) {
        return spec.x * viewport.width;
    }

    float centerY(TouchControlConfig.ButtonSpec spec) {
        return spec.y * viewport.height;
    }

    /** Writes the button's drawn box into out: centre from the fractions, size from the dp. */
    void bounds(TouchControlConfig.ButtonSpec spec, android.graphics.RectF out) {
        float width = pixelWidth(spec);
        float height = pixelHeight(spec);
        if (TouchButtonShape.isFixedAspect(spec.shape)) {
            float side = Math.min(width, height);
            width = side;
            height = side;
        }
        float cx = centerX(spec);
        float cy = centerY(spec);
        out.set(cx - width * 0.5f, cy - height * 0.5f, cx + width * 0.5f, cy + height * 0.5f);
    }

    private float cornerRadiusPx(TouchControlConfig.ButtonSpec spec) {
        return spec.cornerDp * viewport.density * viewport.scale;
    }

    /** The button under the point, topmost first. -1 when none. */
    int hitTest(float px, float py) {
        android.graphics.RectF rect = new android.graphics.RectF();
        List<TouchControlConfig.ButtonSpec> ordered = working.inDrawOrder();
        for (int i = ordered.size() - 1; i >= 0; --i) {
            TouchControlConfig.ButtonSpec spec = ordered.get(i);
            bounds(spec, rect);
            if (TouchButtonShape.contains(spec.shape, spec.polygonType, cornerRadiusPx(spec), rect,
                    px, py)) {
                return working.buttons.indexOf(spec);
            }
        }
        return -1;
    }

    /**
     * GeneralsX @bugfix Android touch editor 05/11/2026 Is the point on the selected button's
     * resize handle? This must be asked BEFORE looking for a button under the finger: the shipped
     * layout's buttons touch, so a handle that overlaps the neighbour would otherwise be
     * unreachable -- the bug the reference branch hit and fixed in the same order.
     */
    boolean isOnResizeHandle(float px, float py) {
        TouchControlConfig.ButtonSpec spec = selectedButton();
        if (spec == null) {
            return false;
        }
        android.graphics.RectF rect = new android.graphics.RectF();
        bounds(spec, rect);
        float handle = HANDLE_DP * viewport.density;
        float left = rect.right - handle * 0.5f;
        float top = rect.bottom - handle * 0.5f;
        return px >= left && px <= left + handle && py >= top && py <= top + handle;
    }

    /** The handle's box, for the view to draw and the touch test to share. */
    void resizeHandle(android.graphics.RectF out) {
        TouchControlConfig.ButtonSpec spec = selectedButton();
        if (spec == null) {
            out.setEmpty();
            return;
        }
        android.graphics.RectF rect = new android.graphics.RectF();
        bounds(spec, rect);
        float handle = HANDLE_DP * viewport.density;
        float left = rect.right - handle * 0.5f;
        float top = rect.bottom - handle * 0.5f;
        out.set(left, top, left + handle, top + handle);
    }

    // ------------------------------------------------------------------ selection

    /** Selects the button under the point, or clears the selection when there is none. */
    boolean selectAt(float px, float py) {
        int hit = hitTest(px, py);
        selected = hit;
        return hit >= 0;
    }

    void clearSelection() {
        selected = -1;
    }

    void select(int index) {
        selected = (index >= 0 && index < working.buttons.size()) ? index : -1;
    }

    // ------------------------------------------------------------------ drag

    /**
     * Starts a move. Records the finger's offset inside the button so dragTo() can place it
     * absolutely -- see the class comment for why this is not an accumulating delta.
     */
    boolean beginDrag(float px, float py) {
        TouchControlConfig.ButtonSpec spec = selectedButton();
        if (spec == null) {
            return false;
        }
        grabDx = px - centerX(spec);
        grabDy = py - centerY(spec);
        return true;
    }

    /** Places the button so the grabbed point stays under the finger. Always clamped. */
    void dragTo(float px, float py) {
        TouchControlConfig.ButtonSpec spec = selectedButton();
        if (spec == null) {
            return;
        }
        setCenterPixels(spec, px - grabDx, py - grabDy);
    }

    /** A nudge in pixels, used by the editor's arrow rows. */
    void moveSelectedBy(float dxPx, float dyPx) {
        TouchControlConfig.ButtonSpec spec = selectedButton();
        if (spec == null) {
            return;
        }
        setCenterPixels(spec, centerX(spec) + dxPx, centerY(spec) + dyPx);
    }

    /**
     * GeneralsX @feature Android touch editor 05/11/2026 Places a button's centre from a pixel
     * point. The only rule left is that the stored fraction is finite and inside 0..1: there is no
     * inset, no reserved strip and no "keep the whole button on screen" clamp, because the editor
     * is expected to let a button sit in a corner, over the HUD, or half past the edge. A button
     * may therefore hang off the screen on purpose -- it stays recoverable through the editor,
     * which draws it wherever its centre says.
     */
    private void setCenterPixels(TouchControlConfig.ButtonSpec spec, float cx, float cy) {
        spec.x = TouchControlConfig.clamp(safeFraction(cx, viewport.width), TouchControlConfig.MIN_X,
            TouchControlConfig.MAX_X);
        spec.y = TouchControlConfig.clamp(safeFraction(cy, viewport.height), TouchControlConfig.MIN_Y,
            TouchControlConfig.MAX_Y);
    }

    /** A pixel coordinate as a screen fraction, guarding a zero-sized viewport and a NaN. */
    private static float safeFraction(float pixels, int extent) {
        if (extent <= 0 || Float.isNaN(pixels) || Float.isInfinite(pixels)) {
            return 0.5f;
        }
        return pixels / extent;
    }

    // ------------------------------------------------------------------ resize

    /** Starts a resize. The corner opposite the handle stays put. */
    boolean beginResize(float px, float py) {
        TouchControlConfig.ButtonSpec spec = selectedButton();
        if (spec == null) {
            return false;
        }
        android.graphics.RectF rect = new android.graphics.RectF();
        bounds(spec, rect);
        resizeAnchorX = rect.left;
        resizeAnchorY = rect.top;
        return true;
    }

    /**
     * Resizes from the anchored corner to the finger. Width and height are independent unless the
     * shape needs a square, in which case the smaller of the two is used for both -- so a circle
     * stays a circle while it is dragged, rather than turning into an oval mid-gesture.
     */
    void resizeTo(float px, float py) {
        TouchControlConfig.ButtonSpec spec = selectedButton();
        if (spec == null) {
            return;
        }
        float unit = viewport.density * viewport.scale;
        if (unit <= 0f) {
            return;
        }
        float widthDp = (px - resizeAnchorX) / unit;
        float heightDp = (py - resizeAnchorY) / unit;
        spec.setSize(widthDp, heightDp);
        // Keep the anchored corner where it was: the centre moves by half the size change. No
        // on-screen clamp here either -- resizing at a corner may push the button past the edge.
        float cx = resizeAnchorX + pixelWidth(spec) * 0.5f;
        float cy = resizeAnchorY + pixelHeight(spec) * 0.5f;
        setCenterPixels(spec, cx, cy);
    }

    /** Grows or shrinks both dimensions by a delta in dp, for the toolbar's size buttons. */
    void resizeSelectedByDp(float deltaDp) {
        TouchControlConfig.ButtonSpec spec = selectedButton();
        if (spec == null) {
            return;
        }
        float width = spec.widthDp + deltaDp;
        float height = spec.heightDp + deltaDp;
        spec.setSize(width, height);
        setCenterPixels(spec, centerX(spec), centerY(spec));
    }

    // ------------------------------------------------------------------ property edits

    /**
     * Changes the selected button's shape. Position, size (except the square/circle rule) and the
     * assigned key are untouched -- "preserve the assigned key and button position when changing
     * shapes" is a test.
     */
    boolean setSelectedShape(int shape) {
        TouchControlConfig.ButtonSpec spec = selectedButton();
        if (spec == null || !TouchButtonShape.isValidShape(shape)) {
            return false;
        }
        spec.setShape(shape);
        setCenterPixels(spec, centerX(spec), centerY(spec));
        return true;
    }

    boolean setSelectedPolygonType(int polygonType) {
        TouchControlConfig.ButtonSpec spec = selectedButton();
        if (spec == null || !TouchButtonShape.isValidPolygon(polygonType)) {
            return false;
        }
        spec.polygonType = polygonType;
        return true;
    }

    boolean setSelectedCornerDp(float cornerDp) {
        TouchControlConfig.ButtonSpec spec = selectedButton();
        if (spec == null) {
            return false;
        }
        spec.cornerDp = TouchControlConfig.clamp(cornerDp, TouchButtonShape.MIN_CORNER_DP,
            TouchButtonShape.MAX_CORNER_DP);
        return true;
    }

    boolean setSelectedBorderWidthDp(float borderWidthDp) {
        TouchControlConfig.ButtonSpec spec = selectedButton();
        if (spec == null) {
            return false;
        }
        spec.borderWidthDp = TouchControlConfig.clamp(borderWidthDp,
            TouchControlConfig.MIN_BORDER_DP, TouchControlConfig.MAX_BORDER_DP);
        return true;
    }

    boolean setSelectedOpacity(float opacity) {
        TouchControlConfig.ButtonSpec spec = selectedButton();
        if (spec == null) {
            return false;
        }
        spec.opacity = TouchControlConfig.clamp(opacity, 0f, 1f);
        return true;
    }

    boolean setSelectedActivationMode(int mode) {
        TouchControlConfig.ButtonSpec spec = selectedButton();
        if (spec == null || !TouchControlConfig.isValidMode(mode)) {
            return false;
        }
        spec.activationMode = mode;
        return true;
    }

    boolean setSelectedLabel(String label) {
        TouchControlConfig.ButtonSpec spec = selectedButton();
        if (spec == null) {
            return false;
        }
        if (label == null || label.trim().isEmpty()) {
            // An unassigned button has no key name to fall back to, so its label is left alone
            // rather than replaced by the "?" placeholder displayName() returns.
            if (!spec.isAssigned()) {
                return false;
            }
            spec.label = TouchKeyCatalog.displayName(spec.keyCode);
        } else {
            spec.label = label.trim();
        }
        return true;
    }

    /**
     * Assigns the key. The modifiers are preserved, so picking a new primary key keeps a
     * Ctrl+... combination the player already set.
     */
    boolean setSelectedKey(int keyCode) {
        TouchControlConfig.ButtonSpec spec = selectedButton();
        if (spec == null || !TouchKeyCatalog.isSelectable(keyCode)) {
            return false;
        }
        spec.keyCode = keyCode;
        return true;
    }

    /**
     * GeneralsX @feature Android touch editor 06/11/2026 Clears the selected button's keyboard
     * mapping without removing the button. The button keeps its position, size, shape, colours,
     * opacity, activation mode and label -- only the key goes, and the modifiers with it, since a
     * modifier with no primary key would otherwise still press Ctrl on a press. The button stays in
     * the list, so it is still drawn, still hit-tested and still draggable in both editors, and it
     * can be given a new key later through setSelectedKey().
     *
     * This is deliberately NOT deleteSelected(): clearing is a property change, deleting removes
     * the button, and resetting the profile (restoreDefaults) replaces the whole layout. Returns
     * false when nothing is selected, or when the button is already unassigned so a second Clear
     * does not mark an unchanged session dirty.
     */
    boolean clearSelectedKey() {
        TouchControlConfig.ButtonSpec spec = selectedButton();
        if (spec == null || !spec.isAssigned()) {
            return false;
        }
        spec.keyCode = KeyEvent.KEYCODE_UNKNOWN;
        spec.modifiers = 0;
        return true;
    }

    boolean setSelectedModifiers(int modifiers) {
        TouchControlConfig.ButtonSpec spec = selectedButton();
        if (spec == null) {
            return false;
        }
        spec.modifiers = modifiers & TouchKeyCatalog.MOD_MASK;
        return true;
    }

    boolean toggleSelectedModifier(int singleFlag) {
        TouchControlConfig.ButtonSpec spec = selectedButton();
        if (spec == null) {
            return false;
        }
        spec.modifiers = (spec.modifiers ^ singleFlag) & TouchKeyCatalog.MOD_MASK;
        return true;
    }

    boolean setSelectedFillColor(int color) {
        TouchControlConfig.ButtonSpec spec = selectedButton();
        if (spec == null) {
            return false;
        }
        spec.fillColor = color;
        return true;
    }

    boolean setSelectedBorderColor(int color) {
        TouchControlConfig.ButtonSpec spec = selectedButton();
        if (spec == null) {
            return false;
        }
        spec.borderColor = color;
        return true;
    }

    boolean setSelectedTextColor(int color) {
        TouchControlConfig.ButtonSpec spec = selectedButton();
        if (spec == null) {
            return false;
        }
        spec.textColor = color;
        return true;
    }

    /** Switches the selected button on or off without touching its action or its geometry. */
    boolean toggleSelectedEnabled() {
        TouchControlConfig.ButtonSpec spec = selectedButton();
        if (spec == null) {
            return false;
        }
        spec.enabled = !spec.enabled;
        return true;
    }

    boolean deleteSelected() {
        TouchControlConfig.ButtonSpec spec = selectedButton();
        if (spec == null) {
            return false;
        }
        working.buttons.remove(spec);
        selected = -1;
        return true;
    }

    /** Brings the selected button to the front of the hit-test order. */
    boolean bringSelectedToFront() {
        TouchControlConfig.ButtonSpec spec = selectedButton();
        if (spec == null) {
            return false;
        }
        int highest = 0;
        for (TouchControlConfig.ButtonSpec other : working.buttons) {
            highest = Math.max(highest, other.zOrder);
        }
        spec.zOrder = highest + 1;
        return true;
    }

    /**
     * GeneralsX @feature Android touch editor 05/11/2026 Tells the editor that a strip at the
     * bottom of the viewport is the host's toolbar, so a new button is never placed under it. The
     * launcher's full-screen editor draws no toolbar over the canvas and leaves this at 0.
     */
    void setReservedBottomPx(float px) {
        reservedBottomPx = Math.max(0f, px);
    }

    /** True while another button may be created. The editor's Add row asks before opening a picker. */
    boolean canAddButton() {
        return working.buttons.size() < TouchControlConfig.MAX_BUTTONS;
    }

    /**
     * GeneralsX @feature Android touch editor 05/11/2026 Adds a button for a key the player has
     * chosen. The key is required: there is no default, so Add always opens the key picker first
     * and a placeholder SPACE button is never created. A key the catalog refuses, or a layout
     * already at the maximum, is refused here rather than creating something unusable.
     *
     * The new button is selected and placed in a free-ish spot, so it can be moved, resized and
     * customised at once. Returns false when nothing was created.
     */
    boolean addButton(int keyCode, int modifiers) {
        if (!canAddButton() || !TouchKeyCatalog.isSelectable(keyCode)) {
            return false;
        }
        TouchControlConfig.ButtonSpec template = working.buttons.isEmpty()
            ? TouchControlConfig.defaults().buttons.get(0) : working.buttons.get(0);
        TouchControlConfig.ButtonSpec spec = new TouchControlConfig.ButtonSpec(
            TouchKeyCatalog.displayName(keyCode), keyCode, modifiers, 0.5f, 0.6f,
            template.widthDp, template.heightDp, template.shape,
            template.fillColor, template.borderColor, template.textColor);
        int highest = 0;
        for (TouchControlConfig.ButtonSpec other : working.buttons) {
            highest = Math.max(highest, other.zOrder);
        }
        spec.zOrder = highest + 1;
        working.buttons.add(spec);
        selected = working.buttons.size() - 1;
        placeNewButton(spec);
        return true;
    }

    /**
     * GeneralsX @feature Android touch editor 05/11/2026 Places a freshly added button where it is
     * visible and selectable: inside the viewport, in the middle band, clear of the toolbar strip
     * when the host reports one, and -- when the screen has room -- not on top of an existing
     * button. This is a courtesy for the ONE button being created, not a layout policy: nothing
     * here is ever re-applied to a button the player has placed, and the player may drag it
     * straight back onto another button or into a corner. The search walks a coarse grid from the
     * centre outwards, so the result is deterministic and needs no randomness.
     */
    private void placeNewButton(TouchControlConfig.ButtonSpec spec) {
        float width = pixelWidth(spec);
        float height = pixelHeight(spec);
        float halfW = width * 0.5f;
        float halfH = height * 0.5f;
        // A new button keeps a hair of margin from the usable edges, so it never lands flush
        // against the screen edge or the toolbar, where its handle would be hard to grab.
        float margin = viewport.density * 8f;
        float minCx = Math.min(halfW + margin, viewport.width * 0.5f);
        float maxCx = Math.max(viewport.width - halfW - margin, viewport.width * 0.5f);
        float minCy = Math.min(halfH + margin, viewport.height * 0.5f);
        float maxCy = Math.max(viewport.height - reservedBottomPx - halfH - margin,
            viewport.height * 0.5f);
        if (minCx > maxCx) {
            minCx = maxCx = (minCx + maxCx) * 0.5f;
        }
        if (minCy > maxCy) {
            minCy = maxCy = (minCy + maxCy) * 0.5f;
        }
        float startX = TouchControlConfig.clamp(viewport.width * 0.5f, minCx, maxCx);
        float startY = TouchControlConfig.clamp(viewport.height * 0.42f, minCy, maxCy);
        float stepX = Math.max(viewport.density * 12f, width * 0.6f);
        float stepY = Math.max(viewport.density * 12f, height * 0.6f);
        // A bounded ring search: near the centre first, then outwards. Ten rings always cover a
        // phone-sized viewport at these steps.
        for (int ring = 0; ring <= 10; ++ring) {
            for (int gy = -ring; gy <= ring; ++gy) {
                for (int gx = -ring; gx <= ring; ++gx) {
                    if (ring > 0 && Math.abs(gx) != ring && Math.abs(gy) != ring) {
                        continue;
                    }
                    float cx = TouchControlConfig.clamp(startX + gx * stepX, minCx, maxCx);
                    float cy = TouchControlConfig.clamp(startY + gy * stepY, minCy, maxCy);
                    if (!overlapsAny(spec, cx, cy, width, height)) {
                        setCenterPixels(spec, cx, cy);
                        return;
                    }
                }
            }
        }
        // Everything is crowded: the centre is still a valid, visible spot.
        setCenterPixels(spec, startX, startY);
    }

    /**
     * Does a box of this size centred here overlap any live button other than <code>spec</code>?
     * Disabled buttons are not drawn in the match, so they are not in the way.
     */
    private boolean overlapsAny(TouchControlConfig.ButtonSpec spec, float cx, float cy,
                                float width, float height) {
        android.graphics.RectF rect = new android.graphics.RectF();
        for (TouchControlConfig.ButtonSpec other : working.buttons) {
            if (other == spec || !other.enabled) {
                continue;
            }
            bounds(other, rect);
            if (cx - width * 0.5f < rect.right && cx + width * 0.5f > rect.left
                && cy - height * 0.5f < rect.bottom && cy + height * 0.5f > rect.top) {
                return true;
            }
        }
        return false;
    }

    /** Puts the shipped layout into the working copy; nothing is stored until save(). */
    void restoreDefaults() {
        TouchControlConfig defaults = TouchControlConfig.defaults();
        working.buttons.clear();
        working.buttons.addAll(TouchControlConfig.copyButtons(defaults.buttons));
        for (int i = 0; i < working.buttons.size(); ++i) {
            working.buttons.get(i).zOrder = i;
        }
        working.buttonScale = defaults.buttonScale;
        working.buttonOpacity = defaults.buttonOpacity;
        working.enabled = defaults.enabled;
        selected = -1;
    }

    /**
     * Applies a viewport the host measured itself. The launcher's editor has no HotkeyOverlayView
     * to read a size from, so it passes its canvas' size here; the in-game editor's viewport is
     * already the live window and does not need this.
     *
     * GeneralsX @bugfix Android touch editor 05/11/2026 A call with no size yet (a host that
     * applies the viewport before its first layout) is ignored rather than applied as 1x1: a 1x1
     * viewport collapses every button onto the same pixel and is what made the editor look empty.
     * The last known-good size stays until the host reports a real one.
     */
    void applyViewportTo(int width, int height, float density) {
        if (width <= 0 || height <= 0) {
            return;
        }
        viewport.set(width, height, density, working.buttonScale);
        android.graphics.RectF rect = new android.graphics.RectF();
        for (TouchControlConfig.ButtonSpec spec : working.buttons) {
            bounds(spec, rect);
            setCenterPixels(spec, rect.centerX(), rect.centerY());
        }
    }

    /**
     * Replaces the working copy with a layout read from a file. The import is applied to the
     * working copy only -- the caller decides whether it is saved -- and the imported buttons
     * keep their own keys, shapes and positions.
     */
    void replaceWith(TouchControlConfig imported) {
        working.buttons.clear();
        working.buttons.addAll(TouchControlConfig.copyButtons(imported.buttons));
        working.buttonScale = imported.buttonScale;
        working.buttonOpacity = imported.buttonOpacity;
        working.enabled = imported.enabled;
        for (int i = 0; i < working.buttons.size(); ++i) {
            working.buttons.get(i).zOrder = i;
        }
        selected = -1;
        applyViewportTo(viewport.width, viewport.height, viewport.density);
    }

    /** Sets the global scale, re-clamping every button so none is pushed off the screen. */
    void setScale(float scale) {
        working.buttonScale = TouchControlConfig.clamp(scale, TouchControlConfig.MIN_SCALE,
            TouchControlConfig.MAX_SCALE);
        viewport.scale = working.buttonScale;
        android.graphics.RectF rect = new android.graphics.RectF();
        for (TouchControlConfig.ButtonSpec spec : working.buttons) {
            bounds(spec, rect);
            setCenterPixels(spec, rect.centerX(), rect.centerY());
        }
    }

    // ------------------------------------------------------------------ commit

    /** Writes the working copy. False when nothing changed, so a second Save is a no-op. */
    boolean save(Context context) {
        if (!isDirty()) {
            return false;
        }
        working.save(context);
        // What is stored is now the state "nothing has been edited" means.
        baseline = TouchControlConfig.copyOf(working);
        return true;
    }

    /** Throws the working copy away and starts again from what is stored. */
    void cancel(Context context) {
        TouchControlConfig stored = TouchControlConfig.load(context);
        working = TouchControlConfig.copyOf(stored);
        baseline = TouchControlConfig.copyOf(stored);
        selected = -1;
    }
}
