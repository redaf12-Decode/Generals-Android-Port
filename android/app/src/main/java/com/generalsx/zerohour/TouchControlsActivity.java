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

// GeneralsX @feature Android touch controls 16/10/2026 Landscape editor for
// the hotkey overlay shown above the running game.
//
// The editor is a full-screen canvas of the phone's display with the buttons
// laid out on it exactly as the overlay will draw them: tap to select, drag to
// move, Edit for the key/action, the sliders for size and transparency. The
// shaded strip at the bottom is a guide -- it is where the game's own command
// panel sits, so buttons placed there would be covered by it.
//
// Two deliberate differences from the reference build's editor:
//
//  * its chrome is the launcher's modern Material UI (theme colours, UiKit app
//    bar and buttons) instead of hard-coded dark greys, so the editor follows
//    the Interface tab's Theme and Accent choices like every other screen;
//  * the "Pan speed" slider is gone. It wrote GeneralsXTouch.ini, which no
//    native code in either tree reads (verified by grep for the file name and
//    for PanSensitivity), so it changed nothing -- see
//    TouchControlConfig.panSensitivity. Everything else -- the button cap, the
//    shape set, the modifier keys, the shrunken drag limits, the profile
//    export/import format -- is the reference behaviour verbatim.

package com.generalsx.zerohour;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;

public class TouchControlsActivity extends Activity {
    private TouchControlConfig config;
    private EditorCanvas editor;
    private TextView selectedText;
    private TextView sizeValue;
    private TextView opacityValue;
    private SeekBar sizeSeek;
    private SeekBar opacitySeek;
    private int selectedIndex = -1;

    private static final int REQUEST_EXPORT_CONTROLS = 7101;
    private static final int REQUEST_IMPORT_CONTROLS = 7102;

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        // The same language + theme pipeline as every other launcher screen:
        // this activity builds Material dialogs, which need the launcher theme
        // rather than the game surface's plain fullscreen one.
        super.attachBaseContext(ThemeHelper.wrap(LocaleHelper.wrap(newBase)));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // GeneralsX @feature Codex 28/08/2026 Allow either landscape rotation
        // for cabled controllers: a cable on one side must not make the
        // editor unusable.
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        ThemeHelper.applyAccentTheme(this);
        setTitle(R.string.touch_editor_title);
        config = TouchControlConfig.load(this);
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.backgroundColor(this));
        setContentView(root);
        InsetUtil.applySafeInsets(root);

        // App bar: the same component the launcher's pages use, with the
        // trailing action that finishes the editor (Save).
        UiKit.appBar(root,
            getString(R.string.setup_card_touch_controls),
            getString(R.string.touch_editor_title),
            R.drawable.ic_gzh_check, getString(R.string.touch_save_button),
            this::saveAndFinish);

        int gap = UiKit.dim(this, R.dimen.gzh_gutter);
        // Status row: what is selected, or the one-line instruction for how to
        // select something.
        selectedText = new TextView(this);
        selectedText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
            UiKit.dim(this, R.dimen.gzh_text_body));
        selectedText.setTextColor(UiKit.color(this, R.color.gzh_on_surface_variant));
        selectedText.setPadding(gap, UiKit.dp(this, 4), gap, UiKit.dp(this, 4));
        root.addView(selectedText, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Tool row. Scrolls horizontally rather than clipping on a narrow
        // phone: seven actions do not fit one line in portrait-shaped
        // landscape windows.
        LinearLayout actionsRow = new LinearLayout(this);
        actionsRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        actionsRow.setPadding(gap, 0, gap, 0);
        addToolButton(actionsRow, R.string.touch_add_button, this::addNewButton);
        addToolButton(actionsRow, R.string.touch_edit_button, this::editSelectedButton);
        addToolButton(actionsRow, R.string.touch_delete_button, this::deleteSelectedButton);
        addToolButton(actionsRow, R.string.touch_reset_button, this::resetDefaults);
        addToolButton(actionsRow, R.string.touch_export_button, this::exportControls);
        addToolButton(actionsRow, R.string.touch_import_button, this::importControls);
        HorizontalScrollView actionsScroll = new HorizontalScrollView(this);
        actionsScroll.setHorizontalScrollBarEnabled(false);
        actionsScroll.addView(actionsRow, new HorizontalScrollView.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(actionsScroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        editor = new EditorCanvas();
        root.addView(editor, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout sliders = new LinearLayout(this);
        sliders.setOrientation(LinearLayout.HORIZONTAL);
        sliders.setPadding(gap, 0, gap, UiKit.dp(this, 4));
        sizeValue = addSlider(sliders, R.string.touch_button_size, 65, 175,
            Math.round(config.buttonScale * 100f), value -> {
                config.buttonScale = value / 100f;
                editor.invalidate();
            });
        sizeSeek = (SeekBar)sizeValue.getTag();
        opacityValue = addSlider(sliders, R.string.touch_button_opacity, 25, 100,
            Math.round(config.buttonOpacity * 100f), value -> {
                config.buttonOpacity = value / 100f;
                editor.invalidate();
            });
        opacitySeek = (SeekBar)opacityValue.getTag();
        root.addView(sliders, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        refreshLabels();
    }

    private interface SliderValueListener { void onValue(int value); }

    private TextView addSlider(LinearLayout row, int titleRes, int min, int max,
                               int initial, SliderValueListener listener) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiKit.dp(this, 8), 0, UiKit.dp(this, 8), 0);
        TextView label = new TextView(this);
        label.setTextColor(UiKit.color(this, R.color.gzh_on_surface));
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
            UiKit.dim(this, R.dimen.gzh_text_body));
        box.addView(label);
        SeekBar seek = new SeekBar(this);
        seek.setMin(min);
        seek.setMax(max);
        seek.setProgress(initial);
        seek.setProgressTintList(UiKit.accentTint(this));
        seek.setThumbTintList(UiKit.accentTint(this));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                listener.onValue(value);
                label.setText(getString(titleRes, value));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        box.addView(seek);
        row.addView(box, new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        label.setTag(seek);  // so the caller can drive the slider back on reset/import
        label.setText(getString(titleRes, initial));
        return label;
    }

    /** A compact tonal action for the tool row (same palette as UiKit.BTN_TONAL). */
    private void addToolButton(LinearLayout row, int textRes, Runnable action) {
        MaterialButton button = new MaterialButton(this);
        button.setText(textRes);
        button.setAllCaps(false);
        button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f);
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setCornerRadius(UiKit.dp(this, 20));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setPadding(UiKit.dp(this, 14), 0, UiKit.dp(this, 14), 0);
        button.setBackgroundTintList(UiKit.tint(this, R.color.gzh_surface_container_high));
        button.setTextColor(UiKit.color(this, R.color.gzh_on_surface));
        button.setRippleColor(UiKit.accentRippleTint(this));
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(UiKit.dim(this, R.dimen.gzh_item_gap_tight));
        row.addView(button, lp);
    }

    private void addNewButton() {
        if (config.buttons.size() >= TouchControlConfig.MAX_BUTTONS) {
            Toast.makeText(this, R.string.touch_too_many_buttons, Toast.LENGTH_SHORT).show();
            return;
        }
        // GeneralsX @bugfix Android port 28/08/2026 Spawn at the canvas
        // centre: the old (0, 0.5) anchor drew a fresh button HALF OFF the
        // left edge (x is a fraction of the width, so 0 centered the rect on
        // the border), and it stayed there until the user happened to drag
        // it back. The editor's drag clamp (x >= 0.025) only applies to
        // drags, never to this initial placement.
        config.buttons.add(new TouchControlConfig.ButtonSpec("E", android.view.KeyEvent.KEYCODE_E,
            0, 0.5f, 0.5f));
        selectedIndex = config.buttons.size() - 1;
        editor.invalidate();
        refreshLabels();
        editSelectedButton();
    }

    private void deleteSelectedButton() {
        if (selectedIndex < 0 || selectedIndex >= config.buttons.size()) return;
        config.buttons.remove(selectedIndex);
        selectedIndex = config.buttons.isEmpty() ? -1 : Math.min(selectedIndex, config.buttons.size() - 1);
        editor.invalidate();
        refreshLabels();
    }

    private void editSelectedButton() {
        if (selectedIndex < 0 || selectedIndex >= config.buttons.size()) {
            Toast.makeText(this, R.string.touch_select_button_first, Toast.LENGTH_SHORT).show();
            return;
        }
        TouchControlConfig.ButtonSpec spec = config.buttons.get(selectedIndex);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = UiKit.dp(this, 20);
        form.setPadding(pad, UiKit.dp(this, 8), pad, 0);

        EditText label = new EditText(this);
        label.setHint(R.string.touch_button_label_hint);
        label.setText(spec.label);
        label.setSelectAllOnFocus(true);
        form.addView(label);

        Spinner keySpinner = new Spinner(this);
        String[] names = TouchControlConfig.keyNames();
        int[] codes = TouchControlConfig.keyCodes();
        keySpinner.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item, names));
        int selectedKey = 0;
        for (int i = 0; i < codes.length; ++i) if (codes[i] == spec.keyCode) selectedKey = i;
        keySpinner.setSelection(selectedKey);
        form.addView(keySpinner);

        LinearLayout modifiers = new LinearLayout(this);
        CheckBox ctrl = new CheckBox(this); ctrl.setText(R.string.touch_modifier_ctrl);
        ctrl.setChecked((spec.modifiers & TouchControlConfig.MOD_CTRL) != 0); modifiers.addView(ctrl);
        CheckBox shift = new CheckBox(this); shift.setText(R.string.touch_modifier_shift);
        shift.setChecked((spec.modifiers & TouchControlConfig.MOD_SHIFT) != 0); modifiers.addView(shift);
        CheckBox alt = new CheckBox(this); alt.setText(R.string.touch_modifier_alt);
        alt.setChecked((spec.modifiers & TouchControlConfig.MOD_ALT) != 0); modifiers.addView(alt);
        form.addView(modifiers);

        TextView widthLabel = new TextView(this);
        widthLabel.setText(getString(R.string.touch_width_dp, Math.round(spec.widthDp))); form.addView(widthLabel);
        SeekBar widthSeek = new SeekBar(this); widthSeek.setMax(192);
        widthSeek.setProgress(Math.round(spec.widthDp - 28f)); form.addView(widthSeek);
        TextView heightLabel = new TextView(this);
        heightLabel.setText(getString(R.string.touch_height_dp, Math.round(spec.heightDp))); form.addView(heightLabel);
        SeekBar heightSeek = new SeekBar(this); heightSeek.setMax(192);
        heightSeek.setProgress(Math.round(spec.heightDp - 28f)); form.addView(heightSeek);

        String[] shapeNames = { getString(R.string.touch_shape_rectangle), getString(R.string.touch_shape_rounded),
            getString(R.string.touch_shape_square), getString(R.string.touch_shape_circle),
            getString(R.string.touch_shape_oval) };
        Spinner shapeSpinner = new Spinner(this);
        shapeSpinner.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item, shapeNames));
        shapeSpinner.setSelection(spec.shape);
        form.addView(shapeSpinner);
        shapeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                boolean square = position == TouchControlConfig.SHAPE_SQUARE || position == TouchControlConfig.SHAPE_CIRCLE;
                heightSeek.setEnabled(!square);
                if (square) {
                    heightSeek.setProgress(widthSeek.getProgress());
                    heightLabel.setText(getString(R.string.touch_height_dp, 28 + widthSeek.getProgress()));
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        // Colors are picked visually only -- border stays accent-driven, fill
        // and text use the same swatch-grid picker (no typed #RRGGBB field).
        final int[] selectedFillColor = {spec.fillColor};
        final int[] selectedTextColor = {spec.textColor};

        LinearLayout textColorRow = new LinearLayout(this);
        textColorRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView textColorLabel = new TextView(this);
        textColorLabel.setText(R.string.touch_text_color);
        textColorLabel.setTextColor(UiKit.color(this, R.color.gzh_on_surface));
        textColorLabel.setPadding(0, UiKit.dp(this, 8), UiKit.dp(this, 12), UiKit.dp(this, 8));
        textColorRow.addView(textColorLabel, new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        ImageButton textColorPicker = new ImageButton(this);
        textColorPicker.setContentDescription(getString(R.string.touch_text_color_picker));
        textColorPicker.setImageResource(android.R.drawable.ic_menu_edit);
        textColorPicker.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 8), UiKit.dp(this, 8), UiKit.dp(this, 8));
        updateColorPickerIcon(textColorPicker, selectedTextColor[0]);
        textColorPicker.setOnClickListener(v -> showColorPicker(textColorPicker, selectedTextColor));
        textColorRow.addView(textColorPicker, new LinearLayout.LayoutParams(
            UiKit.dp(this, 52), UiKit.dp(this, 52)));
        form.addView(textColorRow);

        LinearLayout fillColorRow = new LinearLayout(this);
        fillColorRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView fillColorLabel = new TextView(this);
        fillColorLabel.setText(R.string.touch_fill_color);
        fillColorLabel.setTextColor(UiKit.color(this, R.color.gzh_on_surface));
        fillColorLabel.setPadding(0, UiKit.dp(this, 8), UiKit.dp(this, 12), UiKit.dp(this, 8));
        fillColorRow.addView(fillColorLabel, new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        ImageButton fillColorPicker = new ImageButton(this);
        fillColorPicker.setContentDescription(getString(R.string.touch_fill_color_picker));
        fillColorPicker.setImageResource(android.R.drawable.ic_menu_edit);
        fillColorPicker.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 8), UiKit.dp(this, 8), UiKit.dp(this, 8));
        updateColorPickerIcon(fillColorPicker, selectedFillColor[0]);
        fillColorPicker.setOnClickListener(v -> showColorPicker(fillColorPicker, selectedFillColor));
        fillColorRow.addView(fillColorPicker, new LinearLayout.LayoutParams(
            UiKit.dp(this, 52), UiKit.dp(this, 52)));
        form.addView(fillColorRow);

        SeekBar.OnSeekBarChangeListener sizeListener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                widthLabel.setText(getString(R.string.touch_width_dp, 28 + widthSeek.getProgress()));
                heightLabel.setText(getString(R.string.touch_height_dp, 28 + heightSeek.getProgress()));
                if (shapeSpinner.getSelectedItemPosition() == TouchControlConfig.SHAPE_SQUARE
                    || shapeSpinner.getSelectedItemPosition() == TouchControlConfig.SHAPE_CIRCLE) {
                    heightSeek.setProgress(widthSeek.getProgress());
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        };
        widthSeek.setOnSeekBarChangeListener(sizeListener);
        heightSeek.setOnSeekBarChangeListener(sizeListener);

        android.widget.ScrollView formScroll = new android.widget.ScrollView(this);
        formScroll.addView(form);

        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.touch_edit_dialog_title)
            .setView(formScroll)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.touch_apply_button, (dialog, which) -> {
                String newLabel = label.getText().toString().trim();
                if (newLabel.isEmpty()) newLabel = names[keySpinner.getSelectedItemPosition()];
                if (newLabel.length() > 8) newLabel = newLabel.substring(0, 8);
                spec.label = newLabel;
                spec.keyCode = codes[keySpinner.getSelectedItemPosition()];
                spec.modifiers = (ctrl.isChecked() ? TouchControlConfig.MOD_CTRL : 0)
                    | (shift.isChecked() ? TouchControlConfig.MOD_SHIFT : 0)
                    | (alt.isChecked() ? TouchControlConfig.MOD_ALT : 0);
                spec.widthDp = 28f + widthSeek.getProgress();
                spec.heightDp = 28f + heightSeek.getProgress();
                spec.shape = shapeSpinner.getSelectedItemPosition();
                if (spec.shape == TouchControlConfig.SHAPE_SQUARE || spec.shape == TouchControlConfig.SHAPE_CIRCLE) {
                    spec.heightDp = spec.widthDp;
                }
                spec.fillColor = selectedFillColor[0];
                // Border color is theme-controlled; keep the stored field for
                // profile compatibility with the reference build's exports.
                spec.borderColor = ThemeHelper.accentColor(this);
                spec.textColor = selectedTextColor[0];
                editor.invalidate();
                refreshLabels();
            })
            .show();
    }

    private void updateColorPickerIcon(ImageButton button, int color) {
        GradientDrawable swatch = new GradientDrawable();
        swatch.setShape(GradientDrawable.OVAL);
        swatch.setColor(color);
        swatch.setStroke(UiKit.dp(this, 2), ThemeHelper.accentColor(this));
        button.setBackground(swatch);
    }

    private void showColorPicker(ImageButton button, int[] selectedColor) {
        final int[] palette = {
            Color.WHITE, Color.BLACK, Color.LTGRAY, Color.DKGRAY,
            Color.RED, Color.rgb(255, 152, 0), Color.YELLOW,
            Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA,
            ThemeHelper.accentColor(this)
        };
        final int[] pending = {selectedColor[0]};
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(4);
        grid.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 12), UiKit.dp(this, 16), UiKit.dp(this, 4));
        for (int color : palette) {
            ImageButton swatch = new ImageButton(this);
            swatch.setContentDescription(String.format("#%06X", color & 0xFFFFFF));
            swatch.setPadding(UiKit.dp(this, 6), UiKit.dp(this, 6), UiKit.dp(this, 6), UiKit.dp(this, 6));
            updateColorPickerIcon(swatch, color);
            swatch.setOnClickListener(v -> {
                pending[0] = color;
                button.setBackground(((ImageButton) v).getBackground());
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = UiKit.dp(this, 52); lp.height = UiKit.dp(this, 52);
            lp.setMargins(UiKit.dp(this, 4), UiKit.dp(this, 4), UiKit.dp(this, 4), UiKit.dp(this, 4));
            grid.addView(swatch, lp);
        }
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.touch_color_picker_title)
            .setView(grid)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.touch_apply_button, (d, which) -> {
                selectedColor[0] = pending[0];
                updateColorPickerIcon(button, pending[0]);
            })
            .show();
    }

    private void resetDefaults() {
        // Reset is about the LAYOUT: keep the panel on/off choice the player
        // made on the Interface page instead of silently re-enabling the overlay.
        boolean enabled = config.enabled;
        config = TouchControlConfig.defaults();
        config.enabled = enabled;
        selectedIndex = -1;
        sizeSeek.setProgress(Math.round(config.buttonScale * 100f));
        opacitySeek.setProgress(Math.round(config.buttonOpacity * 100f));
        editor.invalidate();
        refreshLabels();
    }

    private void exportControls() {
        // enabled is not edited here (the Interface card owns it); carry the
        // current value into the export untouched.
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "GeneralsZH-Controls.json");
        startActivityForResult(intent, REQUEST_EXPORT_CONTROLS);
    }

    private void importControls() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        startActivityForResult(intent, REQUEST_IMPORT_CONTROLS);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            if (requestCode == REQUEST_EXPORT_CONTROLS) {
                config.exportTo(this, uri);
                Toast.makeText(this, R.string.touch_exported, Toast.LENGTH_SHORT).show();
            } else if (requestCode == REQUEST_IMPORT_CONTROLS) {
                config = TouchControlConfig.importFrom(this, uri);
                selectedIndex = -1;
                sizeSeek.setProgress(Math.round(config.buttonScale * 100f));
                opacitySeek.setProgress(Math.round(config.buttonOpacity * 100f));
                config.save(this);
                String gamePath = SetupActivity.getSavedGamePath(this);
                if (gamePath != null) TouchControlConfig.prepareForLaunch(this, new File(gamePath));
                editor.invalidate();
                refreshLabels();
                Toast.makeText(this, R.string.touch_imported, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.touch_transfer_failed,
                e.getMessage() == null ? "Invalid file" : e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void saveAndFinish() {
        // config.enabled is whatever load/import/reset left it -- the
        // Interface card's switch is the only editor of that flag.
        config.save(this);
        String gamePath = SetupActivity.getSavedGamePath(this);
        if (gamePath != null) TouchControlConfig.prepareForLaunch(this, new File(gamePath));
        Toast.makeText(this, R.string.touch_saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    private void refreshLabels() {
        if (selectedIndex >= 0 && selectedIndex < config.buttons.size()) {
            TouchControlConfig.ButtonSpec spec = config.buttons.get(selectedIndex);
            selectedText.setText(getString(R.string.touch_selected_button, spec.label));
        } else {
            selectedText.setText(R.string.touch_drag_help_short);
        }
        sizeValue.setText(getString(R.string.touch_button_size, Math.round(config.buttonScale * 100f)));
        opacityValue.setText(getString(R.string.touch_button_opacity, Math.round(config.buttonOpacity * 100f)));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * The editing surface: a stand-in for the phone's display, with the button
     * rectangles drawn exactly where the overlay will draw them and the game's
     * command-panel strip shaded at the bottom as a placement guide.
     *
     * The surface keeps a dark ground in both themes on purpose -- it is a
     * preview of the battlefield, not a launcher panel, and a white canvas
     * would misrepresent how a semi-transparent button looks in play.
     */
    private final class EditorCanvas extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ArrayList<RectF> rects = new ArrayList<>();
        private int dragPointer = -1;

        EditorCanvas() {
            super(TouchControlsActivity.this);
            text.setTextAlign(Paint.Align.CENTER);
            text.setFakeBoldText(true);
            setBackgroundColor(Color.rgb(18, 27, 36));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1f);
            paint.setColor(Color.argb(45, 255, 255, 255));
            for (int i = 1; i < 10; ++i) {
                float x = getWidth() * i / 10f;
                canvas.drawLine(x, 0, x, getHeight(), paint);
            }
            for (int i = 1; i < 6; ++i) {
                float y = getHeight() * i / 6f;
                canvas.drawLine(0, y, getWidth(), y, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(9, 13, 18));
            canvas.drawRect(0, getHeight() * 0.82f, getWidth(), getHeight(), paint);
            // GeneralsX @bugfix Android port 28/08/2026 Caption the shaded
            // strip: unexplained, it read as a rendering artifact ("the
            // editor draws a weird black bar").
            text.setColor(Color.argb(170, 255, 255, 255));
            text.setTextSize(dp(12));
            canvas.drawText(getString(R.string.touch_editor_panel_hint),
                getWidth() * 0.5f, getHeight() * 0.82f + dp(17), text);

            rects.clear();
            int alpha = Math.round(255f * config.buttonOpacity);
            for (int i = 0; i < config.buttons.size(); ++i) {
                TouchControlConfig.ButtonSpec spec = config.buttons.get(i);
                float width = spec.widthDp * config.buttonScale * getResources().getDisplayMetrics().density;
                float height = spec.heightDp * config.buttonScale * getResources().getDisplayMetrics().density;
                if (spec.shape == TouchControlConfig.SHAPE_SQUARE || spec.shape == TouchControlConfig.SHAPE_CIRCLE) {
                    float side = Math.min(width, height); width = height = side;
                }
                RectF rect = new RectF(spec.x * getWidth() - width / 2f,
                    spec.y * getHeight() - height / 2f,
                    spec.x * getWidth() + width / 2f,
                    spec.y * getHeight() + height / 2f);
                rects.add(rect);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(i == selectedIndex ? lighten(spec.fillColor, 0.16f) : spec.fillColor);
                paint.setAlpha(i == selectedIndex ? Math.min(255, alpha + 45) : alpha);
                drawShape(canvas, rect, spec, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(i == selectedIndex ? dp(3) : dp(1));
                // The frame is the interface accent, exactly as the overlay
                // draws it -- so the editor shows the same button the game will.
                paint.setColor(ThemeHelper.accentColor(TouchControlsActivity.this));
                paint.setAlpha(255);
                drawShape(canvas, rect, spec, paint);
                text.setColor(spec.textColor);
                text.setTextSize(dp(spec.label.length() > 5 ? 12 : 15) * config.buttonScale);
                Paint.FontMetrics metrics = text.getFontMetrics();
                canvas.drawText(spec.label, rect.centerX(),
                    rect.centerY() - (metrics.ascent + metrics.descent) / 2f, text);
            }
        }

        private void drawShape(Canvas canvas, RectF rect, TouchControlConfig.ButtonSpec spec, Paint paint) {
            switch (spec.shape) {
                case TouchControlConfig.SHAPE_CIRCLE:
                    canvas.drawCircle(rect.centerX(), rect.centerY(), Math.min(rect.width(), rect.height()) * 0.5f, paint); break;
                case TouchControlConfig.SHAPE_OVAL:
                    canvas.drawOval(rect, paint); break;
                case TouchControlConfig.SHAPE_SQUARE:
                    float side = Math.min(rect.width(), rect.height());
                    canvas.drawRect(rect.centerX()-side/2f, rect.centerY()-side/2f,
                        rect.centerX()+side/2f, rect.centerY()+side/2f, paint); break;
                case TouchControlConfig.SHAPE_RECTANGLE:
                    canvas.drawRect(rect, paint); break;
                default:
                    float radius = Math.min(dp(12), Math.min(rect.width(), rect.height()) * 0.25f);
                    canvas.drawRoundRect(rect, radius, radius, paint); break;
            }
        }

        private int lighten(int color, float amount) {
            int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
            return Color.argb(Color.alpha(color),
                r + Math.round((255-r)*amount), g + Math.round((255-g)*amount), b + Math.round((255-b)*amount));
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    selectedIndex = hit(event.getX(), event.getY());
                    dragPointer = event.getPointerId(0);
                    refreshLabels();
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (selectedIndex >= 0 && selectedIndex < config.buttons.size()) {
                        int index = event.findPointerIndex(dragPointer);
                        if (index >= 0) {
                            TouchControlConfig.ButtonSpec spec = config.buttons.get(selectedIndex);
                            // The same clamps the overlay's own geometry needs:
                            // a button may not be dragged off the edges, and the
                            // bottom strip stays clear of the command panel.
                            spec.x = TouchControlConfig.clamp(event.getX(index) / getWidth(), 0.025f, 0.975f);
                            spec.y = TouchControlConfig.clamp(event.getY(index) / getHeight(), 0.06f, 0.94f);
                            invalidate();
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragPointer = -1;
                    return true;
                default:
                    return true;
            }
        }

        private int hit(float x, float y) {
            for (int i = rects.size() - 1; i >= 0; --i) if (rects.get(i).contains(x, y)) return i;
            return -1;
        }
    }
}
