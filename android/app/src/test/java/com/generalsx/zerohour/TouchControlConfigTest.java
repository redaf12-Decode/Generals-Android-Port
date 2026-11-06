// GeneralsX @build Android touch editor 05/11/2026
//
// The layout model and its storage. Two properties are tested hardest because they are what the
// feature promises a player who already has a layout: an older document loads unchanged, and a
// layout that cannot be trusted is refused whole rather than half-applied.

package com.generalsx.zerohour;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class TouchControlConfigTest {

    @Test
    public void defaultsAreTheShippedLayout() {
        TouchControlConfig config = TouchControlConfig.defaults();
        assertEquals(10, config.buttons.size());
        for (TouchControlConfig.ButtonSpec spec : config.buttons) {
            assertTrue("default key is selectable: " + spec.label,
                TouchKeyCatalog.isSelectable(spec.keyCode));
            assertTrue(spec.x >= TouchControlConfig.MIN_X && spec.x <= TouchControlConfig.MAX_X);
            assertTrue(spec.y >= TouchControlConfig.MIN_Y && spec.y <= TouchControlConfig.MAX_Y);
        }
    }

    /** A v1 document -- the reference's -- has none of the added fields, and must still load. */
    @Test
    public void version1DocumentLoadsWithV1Defaults() throws Exception {
        String v1 = "{\"format\":\"GeneralsZHTouchControls\",\"version\":1,"
            + "\"enabled\":true,\"buttonScale\":1.0,\"buttonOpacity\":0.8,\"buttons\":["
            + "{\"label\":\"Q\",\"key\":" + KeyEvent.KEYCODE_Q + ",\"modifiers\":0,"
            + "\"x\":0.2,\"y\":0.7,\"widthDp\":52,\"heightDp\":52,\"shape\":1,"
            + "\"fillColor\":\"#FF16212C\",\"borderColor\":\"#FFDCB556\",\"textColor\":\"#FFFFFFFF\""
            + "}]}";
        TouchControlConfig config = TouchControlConfig.fromJsonString(v1);
        assertEquals(1, config.buttons.size());
        TouchControlConfig.ButtonSpec spec = config.buttons.get(0);
        assertEquals("Q", spec.label);
        assertEquals(KeyEvent.KEYCODE_Q, spec.keyCode);
        assertTrue("absent enabled means enabled", spec.enabled);
        assertEquals("absent mode means Tap", TouchControlConfig.MODE_TAP, spec.activationMode);
        assertEquals("absent polygon means hexagon", TouchButtonShape.POLY_HEXAGON, spec.polygonType);
        assertEquals(TouchButtonShape.DEFAULT_CORNER_DP, spec.cornerDp, 0.001f);
    }

    @Test
    public void roundTripPreservesEveryProperty() throws Exception {
        TouchControlConfig config = TouchControlConfig.defaults();
        TouchControlConfig.ButtonSpec spec = config.buttons.get(0);
        spec.setShape(TouchButtonShape.POLYGON);
        spec.polygonType = TouchButtonShape.POLY_OCTAGON;
        spec.cornerDp = 22f;
        spec.borderWidthDp = 4f;
        spec.opacity = 0.5f;
        spec.activationMode = TouchControlConfig.MODE_TOGGLE;
        spec.modifiers = TouchKeyCatalog.MOD_CTRL | TouchKeyCatalog.MOD_SHIFT;
        spec.setSize(80f, 70f);

        TouchControlConfig reloaded = TouchControlConfig.fromJsonString(config.toJsonString());
        TouchControlConfig.ButtonSpec back = reloaded.buttons.get(0);
        assertEquals(spec.shape, back.shape);
        assertEquals(spec.polygonType, back.polygonType);
        assertEquals(spec.cornerDp, back.cornerDp, 0.001f);
        assertEquals(spec.borderWidthDp, back.borderWidthDp, 0.001f);
        assertEquals(spec.opacity, back.opacity, 0.001f);
        assertEquals(spec.activationMode, back.activationMode);
        assertEquals(spec.modifiers, back.modifiers);
        assertEquals(spec.widthDp, back.widthDp, 0.001f);
        assertEquals(spec.heightDp, back.heightDp, 0.001f);
    }

    /** A stored value outside the model's bounds is clamped, not rejected and not kept. */
    @Test
    public void outOfRangeValuesAreClamped() throws Exception {
        String json = "{\"format\":\"GeneralsZHTouchControls\",\"version\":3,\"buttons\":["
            + "{\"label\":\"A\",\"key\":" + KeyEvent.KEYCODE_A + ",\"x\":9.0,\"y\":-3.0,"
            + "\"widthDp\":9000,\"heightDp\":1,\"shape\":3,\"polygonType\":99,"
            + "\"cornerDp\":9999,\"borderWidthDp\":-4,\"opacity\":5.0,\"activationMode\":42}]}";
        TouchControlConfig config = TouchControlConfig.fromJsonString(json);
        TouchControlConfig.ButtonSpec spec = config.buttons.get(0);
        assertEquals(TouchControlConfig.MAX_X, spec.x, 0.0001f);
        assertEquals(TouchControlConfig.MIN_Y, spec.y, 0.0001f);
        // A circle is one side, so the huge width and the tiny height both land on the smaller.
        assertEquals("a circle takes the shorter side", TouchControlConfig.MIN_SIZE_DP,
            spec.widthDp, 0.001f);
        assertEquals(TouchControlConfig.MIN_SIZE_DP, spec.heightDp, 0.001f);
        assertEquals(TouchButtonShape.POLY_HEXAGON, spec.polygonType);
        assertEquals(TouchButtonShape.MAX_CORNER_DP, spec.cornerDp, 0.001f);
        assertEquals(TouchControlConfig.MIN_BORDER_DP, spec.borderWidthDp, 0.001f);
        assertEquals(1f, spec.opacity, 0.001f);
        assertEquals(TouchControlConfig.MODE_TAP, spec.activationMode);
    }

    /** A key the picker refuses is not a button: the entry is dropped, not injected later. */
    @Test
    public void refusedKeyEntryIsDropped() throws Exception {
        String json = "{\"format\":\"GeneralsZHTouchControls\",\"version\":3,\"buttons\":["
            + "{\"label\":\"Home\",\"key\":" + KeyEvent.KEYCODE_HOME + ",\"x\":0.5,\"y\":0.5},"
            + "{\"label\":\"A\",\"key\":" + KeyEvent.KEYCODE_A + ",\"x\":0.5,\"y\":0.5}]}";
        TouchControlConfig config = TouchControlConfig.fromJsonString(json);
        assertEquals(1, config.buttons.size());
        assertEquals(KeyEvent.KEYCODE_A, config.buttons.get(0).keyCode);
    }

    @Test
    public void documentWithNoValidButtonsIsRefused() {
        String json = "{\"format\":\"GeneralsZHTouchControls\",\"version\":3,\"buttons\":["
            + "{\"label\":\"Home\",\"key\":" + KeyEvent.KEYCODE_HOME + ",\"x\":0.5,\"y\":0.5}]}";
        try {
            TouchControlConfig.fromJsonString(json);
            org.junit.Assert.fail("should have refused the document");
        } catch (Exception expected) {
            assertNotNull(expected.getMessage());
        }
    }

    /**
     * GeneralsX @feature Android touch editor 06/11/2026 An unassigned key is a legitimate state,
     * not a corrupt entry: a button the player cleared is written, read back and kept -- dropped
     * only if its key is one the catalog actively refuses. This is what makes a cleared mapping
     * survive Save and a later reopen instead of the button vanishing or re-defaulting.
     */
    @Test
    public void anUnassignedKeyIsKeptAcrossTheRoundTrip() throws Exception {
        String json = "{\"format\":\"GeneralsZHTouchControls\",\"version\":5,\"buttons\":["
            + "{\"label\":\"Cleared\",\"key\":" + KeyEvent.KEYCODE_UNKNOWN + ",\"x\":0.3,\"y\":0.4,"
            + "\"widthDp\":70,\"heightDp\":50,\"shape\":0}]}";
        TouchControlConfig config = TouchControlConfig.fromJsonString(json);
        assertEquals("the cleared button was kept", 1, config.buttons.size());
        TouchControlConfig.ButtonSpec spec = config.buttons.get(0);
        assertFalse(spec.isAssigned());
        assertEquals(KeyEvent.KEYCODE_UNKNOWN, spec.keyCode);
        assertEquals(0.3f, spec.x, 0.0001f);
        assertEquals(70f, spec.widthDp, 0.001f);
        assertEquals("the label it was saved with is kept too", "Cleared", spec.label);

        TouchControlConfig reloaded = TouchControlConfig.fromJsonString(config.toJsonString());
        assertFalse("and it survives a write and a read", reloaded.buttons.get(0).isAssigned());
        assertEquals(1, reloaded.buttons.size());
    }

    /** A refused key is still refused, and a document of only refused keys is still refused. */
    @Test
    public void aRefusedKeyIsStillNotAnUnassignedKey() throws Exception {
        String json = "{\"format\":\"GeneralsZHTouchControls\",\"version\":5,\"buttons\":["
            + "{\"label\":\"Home\",\"key\":" + KeyEvent.KEYCODE_HOME + ",\"x\":0.5,\"y\":0.5},"
            + "{\"label\":\"-\",\"key\":" + KeyEvent.KEYCODE_UNKNOWN + ",\"x\":0.2,\"y\":0.2}]}";
        TouchControlConfig config = TouchControlConfig.fromJsonString(json);
        assertEquals("the Home entry is dropped, the cleared one is kept",
            1, config.buttons.size());
        assertFalse(config.buttons.get(0).isAssigned());
    }

    @Test
    public void aForeignDocumentIsRefused() {
        try {
            TouchControlConfig.fromJsonString("{\"format\":\"SomethingElse\",\"buttons\":[]}");
            org.junit.Assert.fail("should have refused the document");
        } catch (Exception expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void aNewerVersionIsRefusedRatherThanHalfRead() {
        String json = "{\"format\":\"GeneralsZHTouchControls\",\"version\":999,\"buttons\":["
            + "{\"label\":\"A\",\"key\":" + KeyEvent.KEYCODE_A + ",\"x\":0.5,\"y\":0.5}]}";
        try {
            TouchControlConfig.fromJsonString(json);
            org.junit.Assert.fail("should have refused a newer version");
        } catch (Exception expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void buttonLimitIsEnforced() {
        StringBuilder buttons = new StringBuilder();
        for (int i = 0; i < TouchControlConfig.MAX_BUTTONS + 5; ++i) {
            if (i > 0) {
                buttons.append(',');
            }
            buttons.append("{\"label\":\"A\",\"key\":").append(KeyEvent.KEYCODE_A)
                .append(",\"x\":0.5,\"y\":0.5}");
        }
        try {
            TouchControlConfig.fromJsonString("{\"format\":\"GeneralsZHTouchControls\","
                + "\"version\":3,\"buttons\":[" + buttons + "]}");
            org.junit.Assert.fail("should have refused too many buttons");
        } catch (Exception expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void copyIsDeepAndIndependent() {
        TouchControlConfig source = TouchControlConfig.defaults();
        TouchControlConfig copy = TouchControlConfig.copyOf(source);
        copy.buttons.get(0).label = "changed";
        copy.buttons.get(0).x = 0.99f;
        assertFalse("the source is untouched", "changed".equals(source.buttons.get(0).label));
        assertFalse(source.buttons.get(0).x == 0.99f);
    }

    @Test
    public void drawOrderIsStableByZOrder() {
        TouchControlConfig config = TouchControlConfig.defaults();
        config.buttons.get(0).zOrder = 99;
        List<TouchControlConfig.ButtonSpec> ordered = config.inDrawOrder();
        assertEquals("the highest zOrder is drawn last", "E", ordered.get(9).label);
        assertEquals(10, ordered.size());
    }

    /** Switching to a square takes one side and keeps the assigned key and the position. */
    @Test
    public void changingShapeKeepsKeyAndPosition() {
        TouchControlConfig.ButtonSpec spec = TouchControlConfig.defaults().buttons.get(0);
        spec.setSize(80f, 40f);
        int key = spec.keyCode;
        float x = spec.x;
        float y = spec.y;
        spec.setShape(TouchButtonShape.SQUARE);
        assertEquals(key, spec.keyCode);
        assertEquals(x, spec.x, 0.0001f);
        assertEquals(y, spec.y, 0.0001f);
        assertEquals(40f, spec.widthDp, 0.001f);
        assertEquals(40f, spec.heightDp, 0.001f);
    }
}
