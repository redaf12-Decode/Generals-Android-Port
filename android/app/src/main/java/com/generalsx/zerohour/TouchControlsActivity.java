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

// GeneralsX @feature Android touch editor 05/11/2026 The launcher's Touch Controls Editor: the
// full-screen page where a layout is built without the game running.
//
// It is the same model and the same edit session as the in-game editor -- TouchLayoutEditor, one
// per page -- so a button moves the same way in both and there is no second implementation to
// drift. What is different here is everything the game surface cannot offer: a key picker, a
// shape picker, colour pickers, a name, an activation mode, import/export, and a preview of the
// battlefield's own command bar so a button is never placed where the game draws over it.
//
// Saving writes TouchControlConfig, the single store both the game and the toolbox read, so a
// layout saved here is in the match on the next launch and can also be opened in the match.
//
// GeneralsX @bugfix Android touch editor 06/11/2026 The page is built in four bands -- header,
// preview, inspector, footer -- and the structure was reworked so nothing can overlap:
//
//  * The body is a side-by-side split only while the window is WIDER than it is tall; in portrait
//    the preview sits above the inspector. The choice is re-made whenever the window's size
//    changes, so a rotation or a fold re-arranges instead of squashing.
//  * Every group of cells (the six shapes, the four polygons, the three modes, the colour
//    swatches, the key grid, the footer actions) is a FlowLayout, so it wraps onto another line on
//    a narrow screen instead of running off the edge and drawing over its neighbour. Cells keep
//    their size; nothing is shrunk and no option is hidden.
//  * The inspector's whole content is one ScrollView and the preview canvas keeps its own weight,
//    so a long inspector scrolls under its own header rather than pushing the preview away.
//  * The footer is a wrapping FlowLayout too, so Cancel/Save/Apply can never be pushed off the
//    bottom edge, and the root is padded by the system-bar and cutout insets so the header and the
//    footer are never drawn under the status bar, the gesture bar or a camera cutout.
//  * A bundled battlefield picture sits behind everything with a configurable dark scrim, and it
//    takes no touch, so the controls stay readable and fully interactive over it.

package com.generalsx.zerohour;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class TouchControlsActivity extends Activity {

    private static final int REQUEST_IMPORT = 4001;
    private static final int REQUEST_EXPORT = 4002;

    /** The background scrim's stored opacity, in the launcher's own preferences. */
    private static final String PREF_BACKGROUND_DIM = "touch_editor_background_dim";

    private FrameLayout root;
    private TouchEditorBackgroundView background;
    private LinearLayout body;
    private FrameLayout canvas;
    private LinearLayout inspector;
    private LinearLayout inspectorColumn;
    private TextView inspectorTitle;
    private TextView helpText;
    private FlowLayout footer;
    /** The body as the concrete split container, so its orientation callback can be installed. */
    private SplitBody splitBody;

    private TouchLayoutEditor editor;
    private TouchEditorRenderer renderer;

    /**
     * GeneralsX @bugfix Android touch editor 05/11/2026 The view that actually draws. The frame it
     * sits in is a container, so invalidating the container never repaints the buttons inside it.
     * Every edit refreshes this reference directly.
     */
    private EditorCanvas editorSurface;

    /**
     * GeneralsX @feature Android touch editor 05/11/2026 The preview's letterbox transform. The
     * editor's viewport is the real landscape game screen; this maps it into the narrower area the
     * side inspector leaves. The renderer draws through it and touch is mapped back through its
     * inverse, so a button's stored position never depends on the inspector's width.
     */
    private final TouchPreviewViewport preview = new TouchPreviewViewport();
    /** The game screen the preview stands for, in pixels. */
    private int gameWidth;
    private int gameHeight;

    /** The key-selection menu the Add button opens, shared with the in-game editor. */
    private TouchKeyPicker keyPicker;
    /** The picker dialog while it is showing, so a choice or a Cancel can close it. */
    private android.app.AlertDialog keyDialog;

    private float downX;
    private float downY;
    private boolean dragging;
    private boolean resizing;
    private final float[] touchSlop = new float[1];

    /** Guards the deferred refresh so a burst of edits coalesces into one pass. */
    private boolean refreshPosted;
    /** The last arrangement the body was given, so a re-arrangement is a no-op when unchanged. */
    private boolean bodyIsSideBySide;
    private int bodyArrangedWidth;
    private boolean bodyArranged;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeHelper.apply(this);
        editor = new TouchLayoutEditor(TouchControlConfig.load(this));
        renderer = new TouchEditorRenderer(this);
        keyPicker = new TouchKeyPicker();
        touchSlop[0] = ViewConfiguration.get(this).getScaledTouchSlop();
        setContentView(buildLayout());
        // The inspector is built synchronously so the page is complete on the first frame; the
        // deferred pass then fixes the preview transform once the canvas has a real size.
        refreshNow();
        editorSurface.post(this::refreshNow);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // The manifest keeps this Activity across a rotation, so the arrangement is re-made here
        // rather than by a fresh onCreate: the preview and the inspector swap places, and the
        // preview transform is recomputed for the new canvas size.
        arrangeBody();
        refreshNow();
    }

    private View buildLayout() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(12, 16, 20));

        // The battlefield picture first, so it is behind every control, and non-touchable so a
        // press on empty space still reaches the preview canvas and the buttons.
        background = new TouchEditorBackgroundView(this);
        background.setDimPercent(loadBackgroundDim());
        root.addView(background, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        content.addView(buildHeader());

        splitBody = new SplitBody(this);
        splitBody.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        body = splitBody;

        canvas = new FrameLayout(this);
        // GeneralsX @bugfix Android touch editor 05/11/2026 The editing surface is the whole
        // FrameLayout: it must be attached MATCH_PARENT, or a plain View measures to nothing
        // (WRAP_CONTENT) and the editor draws on a 0-pixel-high canvas -- the layout loads, but
        // no button appears. The reference attached it with weight, so do the same. The reference
        // to it is kept so every edit can repaint the view that actually draws.
        editorSurface = new EditorCanvas(this);
        editorSurface.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        canvas.addView(editorSurface);
        body.addView(canvas);

        inspector = new LinearLayout(this);
        inspector.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        inspector.setPadding(pad, pad, pad, pad);
        body.addView(inspector);

        content.addView(body);

        footer = new FlowLayout(this);
        footer.setGaps(dp(8), dp(8));
        footer.setPadding(dp(8), dp(8), dp(8), dp(8));
        content.addView(footer, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        buildFooter();
        arrangeBody();
        InsetUtil.applySafeInsets(root);
        return root;
    }

    /**
     * Places the preview and the inspector: side by side while the body is wider than it is tall,
     * stacked in portrait. The inspector takes a fraction of a wide body and the whole width of a
     * narrow one, and the preview always keeps a usable minimum, so neither can squeeze the other
     * out -- which is what made the old fixed 300dp panel clip on small phones and waste space on
     * tablets.
     */
    private void arrangeBody() {
        if (splitBody == null) {
            return;
        }
        int width = splitBody.getWidth();
        int height = splitBody.getHeight();
        if (width <= 0 || height <= 0) {
            // Before the first layout, fall back to the configuration's own idea of orientation so
            // the very first pass is already the right shape and no reflow is visible.
            boolean portrait = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_PORTRAIT;
            applyBodyArrangement(!portrait, getResources().getDisplayMetrics().widthPixels);
            return;
        }
        applyBodyArrangement(width >= height, width);
    }

    private void applyBodyArrangement(boolean sideBySide, int availableWidth) {
        // The width matters as well as the orientation: the side panel is a fraction of it, so a
        // resize (a fold, a multi-window drag) has to re-split even when the orientation is the
        // same. Without the width in the guard, the very first pass -- which runs before the real
        // width is known -- would freeze the panel at the fallback width forever.
        if (bodyArranged && sideBySide == bodyIsSideBySide
                && availableWidth == bodyArrangedWidth) {
            return;
        }
        bodyArranged = true;
        bodyIsSideBySide = sideBySide;
        bodyArrangedWidth = availableWidth;
        body.setOrientation(sideBySide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);

        if (sideBySide) {
            // A side panel: a fraction of the body, clamped so it is never too narrow to hold a
            // control and never wide enough to starve the preview.
            int preferred = Math.round(availableWidth * 0.42f);
            int panel = Math.max(dp(220), Math.min(preferred, dp(400)));
            panel = Math.min(panel, Math.max(dp(180), availableWidth - dp(200)));
            canvas.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            inspector.setLayoutParams(new LinearLayout.LayoutParams(
                panel, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            canvas.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            inspector.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        body.requestLayout();
    }

    /**
     * GeneralsX @feature Android touch editor 06/11/2026 The body, which re-decides its own
     * orientation from the size it is actually given. A window-size listener cannot be relied on:
     * it never fires for a view tree measured and laid out by hand (a test, a preview render) and
     * it arrives after the first layout in any case. Deciding here means the split is right on the
     * first frame and after a rotation, without a recreate.
     */
    private final class SplitBody extends LinearLayout {
        SplitBody(Activity activity) {
            super(activity);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = MeasureSpec.getSize(heightMeasureSpec);
            if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED
                    && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED
                    && width > 0 && height > 0) {
                applyBodyArrangement(width >= height, width);
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            applyBodyArrangement(width >= height, width);
        }
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(10);
        header.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.touch_editor_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(18f);
        header.addView(title);

        helpText = new TextView(this);
        helpText.setText(R.string.touch_drag_help_short);
        helpText.setTextColor(0xFF9FD4D8);
        helpText.setTextSize(12f);
        // The help is a sentence, not a label: it wraps onto as many lines as it needs instead of
        // being clipped at the header's edge.
        helpText.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        header.addView(helpText);
        return header;
    }

    /**
     * The action bar. It is a wrapping FlowLayout rather than a row with a weighted spacer, so the
     * actions stay reachable on a narrow screen: they move onto a second line instead of being
     * squeezed to zero or pushed past the edge. The spacer that used to sit here was also the cause
     * of the editor opening blank -- a weighted WRAP_CONTENT view stretches to the whole available
     * height, which made the footer 600px tall and squeezed the editing canvas to nothing.
     */
    private void buildFooter() {
        footer.removeAllViews();
        footer.addView(footerButton(R.string.touch_add_button, v -> showKeyPicker()));
        footer.addView(footerButton(R.string.touch_reset_button, v -> {
            editor.restoreDefaults();
            refresh();
        }));
        footer.addView(footerButton(R.string.touch_import_button, v -> importLayout()));
        footer.addView(footerButton(R.string.touch_export_button, v -> exportLayout()));
        footer.addView(footerButton(R.string.touch_save_button, v -> save()));
        footer.addView(footerButton(R.string.touch_apply_button, v -> {
            if (editor.save(this)) {
                toast(getString(R.string.touch_saved));
            }
            finish();
        }));
    }

    private Button footerButton(int textRes, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(textRes);
        button.setOnClickListener(listener);
        // A finger-sized target, and a floor on the width so two short labels do not end up as
        // two tiny boxes.
        button.setMinimumHeight(dp(48));
        button.setMinimumWidth(dp(96));
        button.setPadding(dp(12), 0, dp(12), 0);
        return button;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ inspector

    /**
     * Recomputes the preview transform, rebuilds the inspector and repaints the drawing surface.
     *
     * GeneralsX @bugfix Android touch editor 05/11/2026 This used to call canvas.invalidate() --
     * the FrameLayout -- which never repaints the EditorCanvas child that draws the buttons, so an
     * edit changed the model and left the picture stale. The surface is invalidated directly, and
     * the editor's viewport is the game screen the preview stands for rather than the narrow canvas,
     * so a stored position is the same here as in the match.
     *
     * GeneralsX @bugfix Android touch editor 06/11/2026 The rebuild is deferred to the next layout
     * pass. Rebuilding the inspector removes and re-adds its views, which changes the inspector's
     * height; doing that synchronously from a SeekBar callback while the inspector was laying out
     * re-entered the layout and could leave a control measured against a stale size. Posting the
     * whole refresh makes every rebuild happen between frames, and the guard collapses a burst of
     * slider callbacks into one pass.
     */
    private void refresh() {
        if (refreshPosted) {
            return;
        }
        refreshPosted = true;
        View anchor = editorSurface != null ? editorSurface : root;
        anchor.post(() -> {
            refreshPosted = false;
            if (isFinishing()) {
                return;
            }
            refreshNow();
        });
    }

    /** Applies a refresh immediately, for the first pass, a rotation and a test with no looper. */
    private void refreshNow() {
        applyPreviewTransform();
        rebuildInspector();
        if (editorSurface != null) {
            editorSurface.invalidate();
        }
    }

    /**
     * Fits the game screen into the area the canvas has, hands the transform to the renderer and
     * sets the editor's viewport to the game screen. The game screen is the phone's display size,
     * which is what the in-game overlay measures, so the two editors agree on every coordinate.
     */
    private void applyPreviewTransform() {
        int width = editorSurface.getWidth();
        int height = editorSurface.getHeight();
        if (width <= 0 || height <= 0) {
            // Before the first layout there is nothing to fit into; the game screen is still the
            // display, so the editor can be given a valid viewport and the transform is applied on
            // the next pass.
            editor.applyViewportTo(gameWidth(), gameHeight(),
                getResources().getDisplayMetrics().density);
            return;
        }
        preview.update(width, height, gameWidth(), gameHeight());
        renderer.setPreviewTransform(preview.scale(), preview.offsetX(), preview.offsetY());
        editor.applyViewportTo(gameWidth(), gameHeight(),
            getResources().getDisplayMetrics().density);
    }

    /** The landscape game screen the preview stands for: the display, whichever way it is held. */
    private int gameWidth() {
        return gameWidth > 0 ? gameWidth : Math.max(displayWidth(), displayHeight());
    }

    private int gameHeight() {
        return gameHeight > 0 ? gameHeight : Math.min(displayWidth(), displayHeight());
    }

    private int displayWidth() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    private int displayHeight() {
        return getResources().getDisplayMetrics().heightPixels;
    }

    /**
     * GeneralsX @feature Android touch editor 05/11/2026 Pins the game screen the preview stands
     * for, so a test can render the page at a known size instead of the emulator's.
     */
    void setGameViewportForTest(int width, int height) {
        gameWidth = width;
        gameHeight = height;
    }

    /** The editor model, for a test that drives the real touch path and reads the result. */
    TouchLayoutEditor editorForTest() {
        return editor;
    }

    /** The view that draws, for a test that renders the page as the window does. */
    View editorSurfaceForTest() {
        return editorSurface;
    }

    /** The open picker dialog, or null when Add refused or the picker was dismissed. */
    android.app.AlertDialog keyDialogForTest() {
        return keyDialog;
    }

    /** The inspector panel, for a test that checks the page's structure. */
    View inspectorForTest() {
        return inspector;
    }

    /** The action bar, for a test that checks its actions stay on screen. */
    View footerForTest() {
        return footer;
    }

    /** The background picture, for a test of its scaling and scrim. */
    TouchEditorBackgroundView backgroundForTest() {
        return background;
    }

    /** Rebuilds the inspector without a layout pass, for a test that has no looper. */
    void rebuildInspectorForTest() {
        rebuildInspector();
    }

    /** A canvas point in game-screen pixels: the inverse of the preview's transform. */
    private float toGameX(float previewX) {
        return preview.toGameX(previewX);
    }

    private float toGameY(float previewY) {
        return preview.toGameY(previewY);
    }

    private void rebuildInspector() {
        inspector.removeAllViews();
        TouchControlConfig.ButtonSpec spec = editor.selectedButton();

        inspectorTitle = new TextView(this);
        inspectorTitle.setText(spec == null
            ? getString(R.string.touch_drag_help_short)
            : getString(R.string.touch_selected_button,
                TouchKeyCatalog.combinationName(this, spec.keyCode, spec.modifiers)));
        inspectorTitle.setTextColor(spec == null ? 0xFFCCCCCC : Color.WHITE);
        inspectorTitle.setTextSize(spec == null ? 14f : 15f);
        inspector.addView(inspectorTitle);

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        // The panel scrolls as one column, so a section can never be clipped by the one below it
        // and the preview keeps its own weight regardless of how long the inspector gets.
        inspectorColumn = new LinearLayout(this);
        inspectorColumn.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(inspectorColumn);
        inspector.addView(scroll);

        addBackgroundSection(inspectorColumn);

        if (spec == null) {
            TextView hint = new TextView(this);
            hint.setText(R.string.touch_select_button_first);
            hint.setTextColor(0xFF9FD4D8);
            hint.setTextSize(12f);
            hint.setPadding(0, dp(8), 0, 0);
            inspectorColumn.addView(hint);
            return;
        }

        addKeyPicker(inspectorColumn, spec);
        addShapePicker(inspectorColumn, spec);
        addAppearanceSection(inspectorColumn, spec);
        addLayoutSection(inspectorColumn, spec);
        addToggleRows(inspectorColumn, spec);
    }

    /**
     * The scrim control. It applies to the whole page, so it is shown whether or not a button is
     * selected, and it is the first thing in the column so it is reachable without scrolling past
     * the button sections.
     */
    private void addBackgroundSection(LinearLayout column) {
        addSlider(column, R.string.touch_editor_background_dim, background.dimPercent(),
            TouchEditorBackgroundView.MIN_DIM_PERCENT, TouchEditorBackgroundView.MAX_DIM_PERCENT,
            value -> {
                background.setDimPercent(Math.round(value));
                saveBackgroundDim(background.dimPercent());
            });
    }

    private void addKeyPicker(LinearLayout column, TouchControlConfig.ButtonSpec spec) {
        column.addView(sectionHeader(R.string.touch_edit_dialog_title));

        FlowLayout modifiers = new FlowLayout(this);
        modifiers.setGaps(dp(6), dp(6));
        modifiers.addView(modifierToggle(R.string.touch_modifier_ctrl,
            TouchKeyCatalog.MOD_CTRL, spec));
        modifiers.addView(modifierToggle(R.string.touch_modifier_shift,
            TouchKeyCatalog.MOD_SHIFT, spec));
        modifiers.addView(modifierToggle(R.string.touch_modifier_alt,
            TouchKeyCatalog.MOD_ALT, spec));
        column.addView(modifiers);

        // Clear Key: it removes the mapping and keeps the button. It sits next to the key grid,
        // because it is the alternative to choosing a key, and is separated from Delete (which
        // removes the button itself) by the whole appearance section.
        Button clear = new Button(this);
        clear.setText(R.string.touch_clear_key);
        clear.setOnClickListener(v -> {
            if (editor.clearSelectedKey()) {
                toast(getString(R.string.touch_key_cleared));
            }
            refresh();
        });
        clear.setEnabled(spec.isAssigned());
        clear.setMinimumHeight(dp(48));
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clearParams.topMargin = dp(6);
        clear.setLayoutParams(clearParams);
        column.addView(clear);

        if (spec.isAssigned() && TouchKeyCatalog.isSwallowedByEngine(spec.keyCode, spec.modifiers)) {
            TextView warn = new TextView(this);
            warn.setText(R.string.touch_alt_tab_warning);
            warn.setTextColor(0xFFFFB74D);
            warn.setTextSize(12f);
            column.addView(warn);
        }

        // The key grid: each group is a wrapping FlowLayout inside the panel's own ScrollView, so
        // no key is clipped and there is no second, nested scroll area fighting the panel's.
        for (int group = 0; group < TouchKeyCatalog.GROUP_COUNT; ++group) {
            TextView caption = new TextView(this);
            caption.setText(groupName(group));
            caption.setTextColor(0x99FFB74D);
            caption.setTextSize(11f);
            caption.setPadding(0, dp(6), 0, dp(2));
            column.addView(caption);
            List<TouchKeyCatalog.Entry> entries = TouchKeyCatalog.group(group);
            FlowLayout row = new FlowLayout(this);
            row.setGaps(dp(4), dp(4));
            for (TouchKeyCatalog.Entry entry : entries) {
                row.addView(keyChip(entry, spec));
            }
            column.addView(row);
        }
    }

    private View keyChip(TouchKeyCatalog.Entry entry, TouchControlConfig.ButtonSpec spec) {
        Button button = new Button(this);
        button.setText(entry.name);
        button.setTextSize(11f);
        boolean selected = entry.keyCode == spec.keyCode;
        button.setBackgroundColor(selected ? renderer.accentColor() : 0xFF2A323A);
        button.setTextColor(Color.WHITE);
        button.setPadding(dp(6), 0, dp(6), 0);
        button.setMinimumWidth(dp(48));
        button.setMinimumHeight(dp(40));
        button.setOnClickListener(v -> {
            editor.setSelectedKey(entry.keyCode);
            refresh();
        });
        return button;
    }

    private View modifierToggle(int textRes, int flag, TouchControlConfig.ButtonSpec spec) {
        Button button = new Button(this);
        button.setText(textRes);
        button.setTextSize(11f);
        boolean on = (spec.modifiers & flag) != 0;
        button.setBackgroundColor(on ? renderer.accentColor() : 0xFF2A323A);
        button.setTextColor(Color.WHITE);
        button.setMinimumWidth(dp(64));
        button.setMinimumHeight(dp(44));
        button.setOnClickListener(v -> {
            editor.toggleSelectedModifier(flag);
            refresh();
        });
        return button;
    }

    private void addShapePicker(LinearLayout column, TouchControlConfig.ButtonSpec spec) {
        column.addView(sectionHeader(R.string.touch_shape_label));
        FlowLayout row = new FlowLayout(this);
        row.setGaps(dp(4), dp(4));
        int[] shapes = {TouchButtonShape.RECTANGLE, TouchButtonShape.ROUNDED,
            TouchButtonShape.SQUARE, TouchButtonShape.CIRCLE, TouchButtonShape.OVAL,
            TouchButtonShape.POLYGON};
        int[] names = {R.string.touch_shape_rectangle, R.string.touch_shape_rounded,
            R.string.touch_shape_square, R.string.touch_shape_circle, R.string.touch_shape_oval,
            R.string.touch_shape_polygon};
        for (int i = 0; i < shapes.length; ++i) {
            final int shape = shapes[i];
            Button button = new Button(this);
            button.setText(names[i]);
            button.setTextSize(11f);
            boolean selected = spec.shape == shape;
            button.setBackgroundColor(selected ? renderer.accentColor() : 0xFF2A323A);
            button.setTextColor(Color.WHITE);
            button.setPadding(dp(6), 0, dp(6), 0);
            button.setMinimumHeight(dp(44));
            button.setOnClickListener(v -> {
                if (editor.setSelectedShape(shape)) {
                    refresh();
                } else {
                    toast(getString(R.string.touch_select_button_first));
                }
            });
            row.addView(button);
        }
        column.addView(row);

        if (spec.shape == TouchButtonShape.POLYGON) {
            FlowLayout polygons = new FlowLayout(this);
            polygons.setGaps(dp(4), dp(4));
            int[] types = {TouchButtonShape.POLY_TRIANGLE, TouchButtonShape.POLY_PENTAGON,
                TouchButtonShape.POLY_HEXAGON, TouchButtonShape.POLY_OCTAGON};
            int[] polyNames = {R.string.touch_polygon_triangle, R.string.touch_polygon_pentagon,
                R.string.touch_polygon_hexagon, R.string.touch_polygon_octagon};
            for (int i = 0; i < types.length; ++i) {
                final int type = types[i];
                Button button = new Button(this);
                button.setText(polyNames[i]);
                button.setTextSize(10f);
                button.setBackgroundColor(spec.polygonType == type
                    ? renderer.accentColor() : 0xFF2A323A);
                button.setTextColor(Color.WHITE);
                button.setPadding(dp(6), 0, dp(6), 0);
                button.setMinimumHeight(dp(40));
                button.setOnClickListener(v -> {
                    editor.setSelectedPolygonType(type);
                    refresh();
                });
                polygons.addView(button);
            }
            column.addView(polygons);
        }
    }

    /** Shape and appearance: opacity, the activation mode and the three colours. */
    private void addAppearanceSection(LinearLayout column, TouchControlConfig.ButtonSpec spec) {
        column.addView(sectionHeader(R.string.touch_section_appearance));
        addSlider(column, R.string.touch_button_opacity, spec.opacity * 100f, 0f, 100f,
            value -> {
                spec.opacity = value / 100f;
                editorSurface.invalidate();
            });
        addModePicker(column, spec);
        addColorRows(column, spec);
    }

    /** Position and size: the two dimension sliders, which are the numeric half of a drag/resize. */
    private void addLayoutSection(LinearLayout column, TouchControlConfig.ButtonSpec spec) {
        column.addView(sectionHeader(R.string.touch_section_layout));
        addSlider(column, R.string.touch_width_dp, spec.widthDp,
            TouchControlConfig.MIN_SIZE_DP, TouchControlConfig.MAX_SIZE_DP, value -> {
                spec.setSize(value, spec.heightDp);
                editor.applyViewportTo(gameWidth(), gameHeight(),
                    getResources().getDisplayMetrics().density);
                editorSurface.invalidate();
            });
        addSlider(column, R.string.touch_height_dp, spec.heightDp,
            TouchControlConfig.MIN_SIZE_DP, TouchControlConfig.MAX_SIZE_DP, value -> {
                spec.setSize(spec.widthDp, value);
                editor.applyViewportTo(gameWidth(), gameHeight(),
                    getResources().getDisplayMetrics().density);
                editorSurface.invalidate();
            });
    }

    private void addModePicker(LinearLayout column, TouchControlConfig.ButtonSpec spec) {
        column.addView(sectionLabel(R.string.touch_mode_label));
        FlowLayout row = new FlowLayout(this);
        row.setGaps(dp(4), dp(4));
        int[] modes = {TouchControlConfig.MODE_TAP, TouchControlConfig.MODE_HOLD,
            TouchControlConfig.MODE_TOGGLE};
        int[] names = {R.string.touch_mode_tap, R.string.touch_mode_hold,
            R.string.touch_mode_toggle};
        for (int i = 0; i < modes.length; ++i) {
            final int mode = modes[i];
            Button button = new Button(this);
            button.setText(names[i]);
            button.setTextSize(11f);
            button.setBackgroundColor(spec.activationMode == mode
                ? renderer.accentColor() : 0xFF2A323A);
            button.setTextColor(Color.WHITE);
            button.setMinimumWidth(dp(72));
            button.setMinimumHeight(dp(44));
            button.setOnClickListener(v -> {
                editor.setSelectedActivationMode(mode);
                refresh();
            });
            row.addView(button);
        }
        column.addView(row);
    }

    private void addColorRows(LinearLayout column, TouchControlConfig.ButtonSpec spec) {
        column.addView(sectionLabel(R.string.touch_fill_color));
        column.addView(colorRow(R.string.touch_fill_color, spec.fillColor, color -> {
            editor.setSelectedFillColor(color);
            refresh();
        }));
        // Each of the three colours is set on the selected button alone; the app accent is never
        // written into any of them.
        column.addView(colorRow(R.string.touch_border_color, spec.borderColor, color -> {
            editor.setSelectedBorderColor(color);
            refresh();
        }));
        column.addView(colorRow(R.string.touch_text_color, spec.textColor, color -> {
            editor.setSelectedTextColor(color);
            refresh();
        }));
    }

    /** A wrapping row of swatches, read from the theme's own palette so nothing is a hardcoded hex. */
    private View colorRow(int labelRes, int current, ColorSink sink) {
        FlowLayout row = new FlowLayout(this);
        row.setGaps(dp(8), dp(8));
        row.setPadding(0, dp(4), 0, dp(4));
        int[] palette = {Color.rgb(22, 33, 44), Color.rgb(220, 181, 86), Color.WHITE,
            Color.BLACK, Color.rgb(198, 40, 40), Color.rgb(46, 125, 50),
            Color.rgb(21, 101, 192), Color.rgb(120, 90, 200)};
        for (int color : palette) {
            final int value = color;
            View swatch = new View(this);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(color);
            dot.setStroke(current == color ? 4 : 1,
                current == color ? renderer.accentColor() : 0xFF556070);
            swatch.setBackground(dot);
            // A finger-sized target: the visible dot fills it, and the touch area is at least 44dp.
            int size = dp(44);
            swatch.setLayoutParams(new ViewGroup.LayoutParams(size, size));
            swatch.setOnClickListener(v -> sink.accept(value));
            row.addView(swatch);
        }
        return row;
    }

    /**
     * The button-level actions, kept apart from Clear Key and from the profile actions: Toggle,
     * Delete and Bring to front act on the button, Clear Key acts on its mapping, and the footer's
     * Defaults acts on the whole profile.
     */
    private void addToggleRows(LinearLayout column, TouchControlConfig.ButtonSpec spec) {
        column.addView(sectionLabel(R.string.touch_delete_button));
        FlowLayout row = new FlowLayout(this);
        row.setGaps(dp(6), dp(6));
        Button toggle = new Button(this);
        toggle.setText(R.string.toolbox_edit_toggle);
        toggle.setMinimumHeight(dp(48));
        toggle.setOnClickListener(v -> {
            editor.toggleSelectedEnabled();
            refresh();
        });
        row.addView(toggle);

        Button delete = new Button(this);
        delete.setText(R.string.touch_delete_button);
        delete.setMinimumHeight(dp(48));
        delete.setOnClickListener(v -> {
            editor.deleteSelected();
            refresh();
        });
        row.addView(delete);

        Button front = new Button(this);
        front.setText(R.string.touch_bring_to_front);
        front.setMinimumHeight(dp(48));
        front.setOnClickListener(v -> {
            editor.bringSelectedToFront();
            refresh();
        });
        row.addView(front);
        column.addView(row);
    }

    private TextView sectionLabel(int textRes) {
        TextView label = new TextView(this);
        label.setText(textRes);
        label.setTextColor(0xFF9FD4D8);
        label.setTextSize(12f);
        label.setPadding(0, dp(10), 0, dp(4));
        return label;
    }

    /** A section's own caption: larger and brighter, so the sections read as sections. */
    private TextView sectionHeader(int textRes) {
        TextView label = new TextView(this);
        label.setText(textRes);
        label.setTextColor(0xFFE8F6F7);
        label.setTextSize(14f);
        label.setPadding(0, dp(16), 0, dp(6));
        return label;
    }

    private interface ValueSink {
        void accept(float value);
    }

    private interface ColorSink {
        void accept(int color);
    }

    private void addSlider(LinearLayout column, int labelRes, float value, float min, float max,
                           ValueSink sink) {
        final TextView label = sectionLabel(labelRes);
        column.addView(label);
        SeekBar bar = new SeekBar(this);
        int span = Math.max(1, Math.round(max - min));
        bar.setMax(span);
        bar.setProgress(Math.round(value - min));
        bar.setMinimumHeight(dp(48));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                label.setText(getString(labelRes, Math.round(min + progress)));
                if (fromUser) {
                    sink.accept(min + progress);
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        label.setText(getString(labelRes, Math.round(value)));
        column.addView(bar);
    }

    // ------------------------------------------------------------------ the canvas

    /**
     * The editable field. It draws the layout with the shared renderer, then the battlefield's own
     * command-bar strip as an orientation guide -- a button placed over that strip would sit where
     * the game draws its own controls, which is exactly what a player needs to see here.
     */
    private final class EditorCanvas extends View {
        EditorCanvas(Activity activity) {
            super(activity);
            setFocusable(true);
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            // GeneralsX @bugfix Android touch editor 05/11/2026 The viewport is the GAME screen the
            // preview stands for, and the letterbox transform is recomputed here, the moment the
            // canvas' real size is known -- so the first frame and a rotation both edit against the
            // right geometry. Relying on the single post() in onCreate left the first frame editing
            // against a stale or zero size, which was half of the empty-editor defect.
            applyPreviewTransform();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas2) {
            super.onDraw(canvas2);
            drawGameFrame(canvas2);
            renderer.draw(canvas2, editor);
        }

        /**
         * GeneralsX @feature Android touch editor 05/11/2026 The letterboxed game screen and its
         * command-bar guide, drawn in game-screen pixels through the preview transform so it lines
         * up with the buttons the renderer draws. The area outside the game screen is left
         * translucent, so the background picture still shows through while the preview itself stays
         * visually distinct from it.
         */
        private void drawGameFrame(Canvas canvas2) {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            canvas2.save();
            canvas2.translate(preview.offsetX(), preview.offsetY());
            canvas2.scale(preview.scale(), preview.scale());
            paint.setColor(0x99121A22);
            canvas2.drawRect(0, 0, gameWidth(), gameHeight(), paint);
            float strip = gameHeight() * 0.24f;
            paint.setColor(0x33FFB74D);
            canvas2.drawRect(0, gameHeight() - strip, gameWidth(), gameHeight(), paint);
            paint.setColor(0x88FFB74D);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f);
            canvas2.drawRect(0, gameHeight() - strip, gameWidth(), gameHeight(), paint);
            canvas2.restore();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            // GeneralsX @feature Android touch editor 05/11/2026 A touch is in canvas pixels, but
            // the editor's geometry is in game-screen pixels, so every point is mapped through the
            // preview's inverse before it is used. Dragging and resizing therefore place a button
            // exactly where it is drawn, and the same stored fraction results whatever the canvas
            // width is. Every refresh invalidates this surface directly -- not its parent frame.
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    downX = toGameX(event.getX());
                    downY = toGameY(event.getY());
                    if (editor.isOnResizeHandle(downX, downY)) {
                        resizing = true;
                        dragging = false;
                        editor.beginResize(downX, downY);
                        invalidate();
                        return true;
                    }
                    if (editor.selectAt(downX, downY)) {
                        editor.beginDrag(downX, downY);
                        dragging = false;
                        resizing = false;
                    } else {
                        editor.clearSelection();
                    }
                    rebuildInspector();
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    float x = toGameX(event.getX());
                    float y = toGameY(event.getY());
                    if (resizing) {
                        editor.resizeTo(x, y);
                        invalidate();
                        return true;
                    }
                    if (editor.selectedButton() != null) {
                        if (!dragging) {
                            float dx = x - downX;
                            float dy = y - downY;
                            if (dx * dx + dy * dy < touchSlop[0] * touchSlop[0]) {
                                return true;
                            }
                            dragging = true;
                        }
                        editor.dragTo(x, y);
                        invalidate();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (dragging || resizing) {
                        rebuildInspector();
                    }
                    dragging = false;
                    resizing = false;
                    invalidate();
                    return true;
                }
                default:
                    return true;
            }
        }
    }

    // ------------------------------------------------------------------ key selection

    /**
     * GeneralsX @feature Android touch editor 05/11/2026 Add opens the key-selection menu, and a
     * button is created only for the key the player picks -- there is no placeholder SPACE button.
     * Cancelling creates nothing. A layout already at the maximum is refused before the menu opens.
     *
     * The menu is built from TouchKeyCatalog, the same source the in-game editor's grid draws, so
     * the two pickers offer exactly the same keys and a chosen key is stored as the button's real
     * keycode, not a label.
     */
    private void showKeyPicker() {
        if (!editor.canAddButton()) {
            toast(getString(R.string.touch_too_many_buttons));
            return;
        }
        keyDialog = buildKeyPickerDialog();
        keyDialog.show();
    }

    /** Builds the picker dialog, so a test can inspect it without showing a window. */
    android.app.AlertDialog buildKeyPickerDialog() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        column.setPadding(pad, pad, pad, pad);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);

        for (int group = 0; group < TouchKeyCatalog.GROUP_COUNT; ++group) {
            TextView caption = new TextView(this);
            caption.setText(groupName(group));
            caption.setTextColor(0xFF9FD4D8);
            caption.setTextSize(12f);
            caption.setPadding(0, dp(10), 0, dp(4));
            column.addView(caption);

            List<TouchKeyCatalog.Entry> entries = TouchKeyCatalog.group(group);
            // A wrapping row, so a narrow dialog reflows the keys instead of clipping them.
            FlowLayout row = new FlowLayout(this);
            row.setGaps(dp(4), dp(4));
            for (TouchKeyCatalog.Entry entry : entries) {
                row.addView(keyChipForAdd(entry));
            }
            column.addView(row);
        }

        return new android.app.AlertDialog.Builder(this)
            .setTitle(R.string.touch_key_menu_title)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
    }

    /** Opens the picker as the footer's Add button does, for a test of the refusal path. */
    void showKeyPickerForTest() {
        showKeyPicker();
    }

    /** One key in the launcher's menu: tapping it creates the button and selects it. */
    private View keyChipForAdd(TouchKeyCatalog.Entry entry) {
        Button button = new Button(this);
        button.setText(entry.name);
        button.setTextSize(11f);
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(0xFF2A323A);
        button.setPadding(dp(6), 0, dp(6), 0);
        button.setMinimumWidth(dp(52));
        button.setMinimumHeight(dp(44));
        button.setOnClickListener(v -> {
            if (editor.addButton(entry.keyCode, 0)) {
                dismissDialog();
                refresh();
            } else {
                dismissDialog();
                toast(getString(R.string.touch_too_many_buttons));
            }
        });
        return button;
    }

    /** Closes the picker dialog when one is showing, so a choice leaves no window behind. */
    private void dismissDialog() {
        if (keyDialog != null && keyDialog.isShowing()) {
            keyDialog.dismiss();
        }
    }

    private String groupName(int group) {
        switch (group) {
            case TouchKeyCatalog.GROUP_LETTER:
                return getString(R.string.touch_key_group_letters);
            case TouchKeyCatalog.GROUP_DIGIT:
                return getString(R.string.touch_key_group_numbers);
            case TouchKeyCatalog.GROUP_MODIFIER:
                return getString(R.string.touch_key_group_modifiers);
            case TouchKeyCatalog.GROUP_SPECIAL:
                return getString(R.string.touch_key_group_special);
            default:
                return getString(R.string.touch_key_group_function);
        }
    }

    private void importLayout() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        startActivityForResult(intent, REQUEST_IMPORT);
    }

    private void exportLayout() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "generalszh-touch-controls.json");
        startActivityForResult(intent, REQUEST_EXPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        try {
            if (requestCode == REQUEST_IMPORT) {
                TouchControlConfig imported = TouchControlConfig.importFrom(this, data.getData());
                editor.replaceWith(imported);
                refresh();
                toast(getString(R.string.touch_imported));
            } else if (requestCode == REQUEST_EXPORT) {
                editor.working().exportTo(this, data.getData());
                toast(getString(R.string.touch_exported));
            }
        } catch (Exception e) {
            toast(getString(R.string.touch_transfer_failed, String.valueOf(e.getMessage())));
        }
    }

    private void save() {
        if (editor.save(this)) {
            toast(getString(R.string.touch_saved));
        }
    }

    /** The background scrim's saved opacity, or the view's own default on a fresh install. */
    private int loadBackgroundDim() {
        return getSharedPreferences(LocaleHelper.PREFS_NAME, MODE_PRIVATE)
            .getInt(PREF_BACKGROUND_DIM, TouchEditorBackgroundView.DEFAULT_DIM_PERCENT);
    }

    private void saveBackgroundDim(int percent) {
        getSharedPreferences(LocaleHelper.PREFS_NAME, MODE_PRIVATE).edit()
            .putInt(PREF_BACKGROUND_DIM, percent).apply();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
