/*
**	Command & Conquer Generals Zero Hour(tm)
**	Copyright 2025 Electronic Arts Inc.
**
**	This program is free software: you can redistribute it and/or modify
**	it under the terms of the GNU General Public License as published by
**	the Free Software Foundation, either version 3 of the License, or
**	(at your option) any later version.
**
**	This program is distributed in the hope that it will be useful,
**	but WITHOUT ANY WARRANTY; without even the implied warranty of
**	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
**	GNU General Public License for more details.
**
**	You should have received a copy of the GNU General Public License
**	along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

// GeneralsX @feature Android touch controls 16/10/2026 Transparent,
// touch-through hotkey layer drawn above SDL's game surface.
//
// Two rules make it safe to sit on top of the battlefield:
//
//  * Only a configured button consumes a touch. A press that lands anywhere
//    else returns false from onTouchEvent, so the view below (SDL's surface)
//    receives that same gesture and the engine's own touch path -- camera pan,
//    selection, pinch -- is untouched.
//  * A button presses the GAME'S OWN shortcut: real key events go to the
//    engine through SDLActivity.onNativeKeyDown/Up, exactly as a hardware
//    keyboard would. Nothing here reaches into engine internals.
//
// The buttons' geometry is resolved against the view's CURRENT size on every
// draw, so rotation and a different screen keep the layout proportional (the
// stored coordinates are fractions of the screen).

package com.generalsx.zerohour;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import org.libsdl.app.SDLActivity;

import java.util.ArrayList;

final class HotkeyOverlayView extends View {
    /**
     * How long a key is held down. SDL delivers a key-down and a key-up
     * separately, and the engine's own shortcut handling reacts to the
     * key-down, so the hold only has to outlive the event queue -- 42 ms is
     * about two frames at 60 Hz.
     */
    private static final long KEY_HOLD_MS = 42L;

    private final ArrayList<TouchControlConfig.ButtonSpec> buttons = new ArrayList<>();
    private final ArrayList<RectF> hitRects = new ArrayList<>();
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float scale;
    private final float opacity;
    private final int accentColor;

    private int activeButton = -1;
    private int activePointer = -1;

    private HotkeyOverlayView(GeneralsZHActivity activity, TouchControlConfig config) {
        super(activity);
        buttons.addAll(TouchControlConfig.copyButtons(config.buttons));
        scale = config.buttonScale;
        opacity = config.buttonOpacity;
        accentColor = ThemeHelper.accentColor(activity);
        setClickable(false);
        setFocusable(false);
        setWillNotDraw(false);

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1.5f));
        borderPaint.setColor(accentColor);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
        textPaint.setShadowLayer(dp(2), 0, dp(1), Color.BLACK);
    }

    /**
     * Attaches the overlay to a launching game surface, or does nothing when
     * the panel is switched off or has no buttons -- the caller can then treat
     * "no overlay" and "empty overlay" the same way.
     */
    static void attach(GeneralsZHActivity activity) {
        TouchControlConfig config = TouchControlConfig.load(activity);
        if (!config.enabled || config.buttons.isEmpty()) return;
        HotkeyOverlayView overlay = new HotkeyOverlayView(activity, config);
        activity.addContentView(overlay, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.bringToFront();
        overlay.setElevation(1000f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        rebuildHitRects();
        int baseAlpha = Math.round(255f * opacity);
        for (int i = 0; i < buttons.size(); ++i) {
            RectF rect = hitRects.get(i);
            TouchControlConfig.ButtonSpec spec = buttons.get(i);
            boolean pressed = i == activeButton;
            fillPaint.setColor(pressed ? lighten(spec.fillColor, 0.16f) : spec.fillColor);
            fillPaint.setAlpha(pressed ? Math.min(255, baseAlpha + 45) : baseAlpha);
            // The interface accent is the single source of truth for the button frame.
            // Keep fill/text colors configurable, but make the frame follow the app theme.
            borderPaint.setColor(accentColor);
            borderPaint.setAlpha(pressed ? 255 : Math.max(120, baseAlpha));
            drawShape(canvas, rect, spec, fillPaint);
            drawShape(canvas, rect, spec, borderPaint);

            textPaint.setColor(spec.textColor);
            float textSize = dp(spec.label.length() > 5 ? 12 : 15) * scale;
            textPaint.setTextSize(textSize);
            Paint.FontMetrics metrics = textPaint.getFontMetrics();
            float baseline = rect.centerY() - (metrics.ascent + metrics.descent) * 0.5f;
            canvas.drawText(spec.label, rect.centerX(), baseline, textPaint);
        }
    }

    private void rebuildHitRects() {
        hitRects.clear();
        for (TouchControlConfig.ButtonSpec spec : buttons) {
            float width = dp(spec.widthDp) * scale;
            float height = dp(spec.heightDp) * scale;
            if (spec.shape == TouchControlConfig.SHAPE_SQUARE || spec.shape == TouchControlConfig.SHAPE_CIRCLE) {
                float side = Math.min(width, height);
                width = height = side;
            }
            float cx = spec.x * getWidth();
            float cy = spec.y * getHeight();
            hitRects.add(new RectF(cx - width * 0.5f, cy - height * 0.5f,
                cx + width * 0.5f, cy + height * 0.5f));
        }
    }

    private int findButton(float x, float y) {
        if (hitRects.size() != buttons.size()) rebuildHitRects();
        for (int i = hitRects.size() - 1; i >= 0; --i) {
            if (hitRects.get(i).contains(x, y)) return i;
        }
        return -1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int hit = findButton(event.getX(), event.getY());
                if (hit < 0) return false; // Let SDL receive normal battlefield gestures.
                activeButton = hit;
                activePointer = event.getPointerId(0);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (activeButton < 0) return false;
                int index = event.findPointerIndex(activePointer);
                if (index >= 0 && !hitRects.get(activeButton).contains(event.getX(index), event.getY(index))) {
                    // Dragged off the button before lifting: cancel it, so a
                    // swipe that merely started on a button is not a press.
                    activeButton = -1;
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (activeButton >= 0) {
                    int hit = activeButton;
                    activeButton = -1;
                    activePointer = -1;
                    invalidate();
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    sendKey(buttons.get(hit));
                    performClick();
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                activeButton = -1;
                activePointer = -1;
                invalidate();
                return true;
            default:
                // A second finger while a button is held: keep consuming the
                // gesture; otherwise let it through to the battlefield.
                return activeButton >= 0;
        }
    }

    /** Presses the button's key (with its modifiers) through SDL, then releases it. */
    private void sendKey(TouchControlConfig.ButtonSpec spec) {
        final int[] modifiers = modifierKeyCodes(spec.modifiers);
        for (int modifier : modifiers) SDLActivity.onNativeKeyDown(modifier);
        SDLActivity.onNativeKeyDown(spec.keyCode);
        handler.postDelayed(() -> {
            SDLActivity.onNativeKeyUp(spec.keyCode);
            for (int i = modifiers.length - 1; i >= 0; --i) {
                SDLActivity.onNativeKeyUp(modifiers[i]);
            }
        }, KEY_HOLD_MS);
    }

    private int[] modifierKeyCodes(int mask) {
        int count = Integer.bitCount(mask & (TouchControlConfig.MOD_CTRL
            | TouchControlConfig.MOD_SHIFT | TouchControlConfig.MOD_ALT));
        int[] result = new int[count];
        int i = 0;
        if ((mask & TouchControlConfig.MOD_CTRL) != 0) result[i++] = KeyEvent.KEYCODE_CTRL_LEFT;
        if ((mask & TouchControlConfig.MOD_SHIFT) != 0) result[i++] = KeyEvent.KEYCODE_SHIFT_LEFT;
        if ((mask & TouchControlConfig.MOD_ALT) != 0) result[i] = KeyEvent.KEYCODE_ALT_LEFT;
        return result;
    }

    private void drawShape(Canvas canvas, RectF rect, TouchControlConfig.ButtonSpec spec, Paint paint) {
        switch (spec.shape) {
            case TouchControlConfig.SHAPE_CIRCLE:
                canvas.drawCircle(rect.centerX(), rect.centerY(), Math.min(rect.width(), rect.height()) * 0.5f, paint);
                break;
            case TouchControlConfig.SHAPE_OVAL:
                canvas.drawOval(rect, paint);
                break;
            case TouchControlConfig.SHAPE_SQUARE:
                float side = Math.min(rect.width(), rect.height());
                RectF square = new RectF(rect.centerX() - side / 2f, rect.centerY() - side / 2f,
                    rect.centerX() + side / 2f, rect.centerY() + side / 2f);
                canvas.drawRect(square, paint);
                break;
            case TouchControlConfig.SHAPE_RECTANGLE:
                canvas.drawRect(rect, paint);
                break;
            default:
                float radius = Math.min(dp(12) * scale, Math.min(rect.width(), rect.height()) * 0.25f);
                canvas.drawRoundRect(rect, radius, radius, paint);
                break;
        }
    }

    private int lighten(int color, float amount) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        r += Math.round((255 - r) * amount);
        g += Math.round((255 - g) * amount);
        b += Math.round((255 - b) * amount);
        return Color.argb(Color.alpha(color), r, g, b);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
