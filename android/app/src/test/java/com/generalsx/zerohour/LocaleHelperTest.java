// GeneralsX @build Android CI 06/11/2026
//
// LocaleHelper drives every localized screen in the launcher, and it is the one part of the
// launcher whose bugs are invisible in English: a tag with no resource folder silently falls back
// to English, and a wrong game-data token silently changes the game's text. Both are checked here
// against the real class and the real res/ tree.
//
// The "every offered language has a folder" test reads the project's own resource directories, so
// adding a tag to SUPPORTED_TAGS without adding values-<tag>/ fails here rather than on a phone.

package com.generalsx.zerohour;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
public class LocaleHelperTest {

    private static Context app() {
        return RuntimeEnvironment.getApplication();
    }

    /** The res/ directory this test module was compiled from, found from the test's own classpath. */
    private static File resDir() {
        File dir = new File("src/main/res");
        if (dir.isDirectory()) {
            return dir;
        }
        return new File("app/src/main/res");
    }

    @Test
    public void noSavedLanguageMeansSystemDefault() {
        assertEquals("", LocaleHelper.getSavedLanguageTag(app()));
    }

    @Test
    public void savedLanguageTagRoundTrips() {
        LocaleHelper.setSavedLanguageTag(app(), "ru");
        assertEquals("ru", LocaleHelper.getSavedLanguageTag(app()));

        LocaleHelper.setSavedLanguageTag(app(), "pt-BR");
        assertEquals("pt-BR", LocaleHelper.getSavedLanguageTag(app()));

        // Back to System Default: the empty tag is a real value, not "unset".
        LocaleHelper.setSavedLanguageTag(app(), "");
        assertEquals("", LocaleHelper.getSavedLanguageTag(app()));
    }

    @Test
    public void systemDefaultLeavesTheBaseContextAlone() {
        LocaleHelper.setSavedLanguageTag(app(), "");
        Context base = app();
        assertTrue("an empty tag must not wrap the context", LocaleHelper.wrap(base) == base);
    }

    @Test
    public void savedLanguageConfiguresTheContextLocale() {
        LocaleHelper.setSavedLanguageTag(app(), "de");
        Context wrapped = LocaleHelper.wrap(app());
        assertEquals("de", wrapped.getResources().getConfiguration().getLocales().get(0).getLanguage());
    }

    @Test
    public void arabicSelectsRightToLeftLayoutDirection() {
        LocaleHelper.setSavedLanguageTag(app(), "ar");
        Context wrapped = LocaleHelper.wrap(app());
        Configuration config = wrapped.getResources().getConfiguration();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            assertEquals("Arabic must resolve to an RTL layout direction",
                android.view.View.LAYOUT_DIRECTION_RTL, config.getLayoutDirection());
        }
    }

    @Test
    public void persianAndHebrewScriptsAreNotTreatedAsLtr() {
        LocaleHelper.setSavedLanguageTag(app(), "fa");
        Context wrapped = LocaleHelper.wrap(app());
        assertEquals(android.view.View.LAYOUT_DIRECTION_RTL,
            wrapped.getResources().getConfiguration().getLayoutDirection());
    }

    /**
     * Every language the picker offers, except English, must have a values-&lt;lang&gt;/ folder.
     * English is the un-suffixed values/, so it is the one tag that legitimately has none.
     */
    @Test
    public void everyOfferedLanguageHasAResourceFolder() {
        File res = resDir();
        assertTrue("could not locate the res/ directory from " + new File(".").getAbsolutePath(),
            res.isDirectory());
        File[] children = res.listFiles();
        assertNotNull(children);
        Set<String> folders = new HashSet<>();
        for (File child : children) {
            if (child.isDirectory() && child.getName().startsWith("values-")) {
                folders.add(child.getName().substring("values-".length()));
            }
        }
        assertTrue("no values-*/ folders found under " + res, folders.size() >= 10);

        for (String tag : LocaleHelper.SUPPORTED_TAGS) {
            if (tag.isEmpty() || "en".equals(tag)) {
                continue;
            }
            // Android's BCP-47 folder names use '+' for the script subtag: isv -> b+isv,
            // pt-BR -> pt-rBR.
            String folder = tag.contains("-") ? tag.replace("-", "-r") : tag;
            if ("isv".equals(tag)) {
                folder = "b+isv";
            }
            assertTrue("language '" + tag + "' is offered by the picker but has no values-"
                    + folder + "/ folder", folders.contains(folder));
        }
    }

    @Test
    public void everyOfferedLanguageHasADisplayName() {
        Context ctx = app();
        for (String tag : LocaleHelper.SUPPORTED_TAGS) {
            String name = LocaleHelper.displayNameFor(ctx, tag);
            assertNotNull("displayNameFor(" + tag + ")", name);
            assertFalse("displayNameFor(" + tag + ") is empty", name.trim().isEmpty());
        }
        assertEquals(ctx.getString(R.string.setup_language_system_default),
            LocaleHelper.displayNameFor(ctx, ""));
        assertEquals(ctx.getString(R.string.setup_language_system_default),
            LocaleHelper.displayNameFor(ctx, null));
    }

    /** An unknown tag is shown as itself rather than as nothing. */
    @Test
    public void unknownLanguageFallsBackToTheTagItself() {
        assertEquals("xx", LocaleHelper.displayNameFor(app(), "xx"));
    }

    @Test
    public void gameDataLanguageMappingCoversTheOfficialSkus() {
        assertEquals("german", LocaleHelper.gameDataLanguageFor("de"));
        assertEquals("spanish", LocaleHelper.gameDataLanguageFor("es"));
        assertEquals("chinese", LocaleHelper.gameDataLanguageFor("zh"));
        assertEquals("french", LocaleHelper.gameDataLanguageFor("fr"));
        assertEquals("korean", LocaleHelper.gameDataLanguageFor("ko"));
        assertEquals("polish", LocaleHelper.gameDataLanguageFor("pl"));
        assertEquals("brazilian", LocaleHelper.gameDataLanguageFor("pt-BR"));
        assertEquals("russian", LocaleHelper.gameDataLanguageFor("ru"));
    }

    /** English, Interslavic and the RTL languages have no official SKU folder of their own. */
    @Test
    public void languagesWithoutAnOfficialSkuHaveNoGameDataMapping() {
        assertNull(LocaleHelper.gameDataLanguageFor("en"));
        assertNull(LocaleHelper.gameDataLanguageFor("isv"));
        assertNull(LocaleHelper.gameDataLanguageFor("ar"));
        assertNull(LocaleHelper.gameDataLanguageFor("fa"));
        assertNull(LocaleHelper.gameDataLanguageFor(null));
    }

    @Test
    public void languagePackForCoversEveryBundledPack() {
        assertEquals("ukrainian", LocaleHelper.languagePackFor("uk"));
        assertEquals("arabic", LocaleHelper.languagePackFor("ar"));
        assertEquals("persian", LocaleHelper.languagePackFor("fa"));
        assertEquals("interslavic", LocaleHelper.languagePackFor("isv"));
        // and falls through to the SKU mapping
        assertEquals("german", LocaleHelper.languagePackFor("de"));
        assertNull(LocaleHelper.languagePackFor("en"));
    }

    @Test
    public void gameTextTokenIsSeparateFromTheLauncherLanguage() {
        assertFalse("a fresh install has not answered the game-text question",
            LocaleHelper.hasGameTextToken(app()));

        LocaleHelper.setGameTextToken(app(), "german");
        assertTrue(LocaleHelper.hasGameTextToken(app()));
        assertEquals("german", LocaleHelper.getGameTextToken(app()));

        // Choosing the launcher's own language must not touch the game's.
        LocaleHelper.setSavedLanguageTag(app(), "ru");
        assertEquals("german", LocaleHelper.getGameTextToken(app()));
        assertEquals("ru", LocaleHelper.getSavedLanguageTag(app()));

        // "English" is a real answer, not "unset": the token is the empty string.
        LocaleHelper.setGameTextToken(app(), "");
        assertTrue(LocaleHelper.hasGameTextToken(app()));
        assertEquals("", LocaleHelper.getGameTextToken(app()));

        LocaleHelper.setGameTextToken(app(), null);
        assertEquals("", LocaleHelper.getGameTextToken(app()));
    }

    @Test
    public void gameTextDisplayNamesAreReadable() {
        assertEquals("English", LocaleHelper.gameTextDisplayName("english"));
        assertEquals("Deutsch", LocaleHelper.gameTextDisplayName("german"));
        assertEquals("Русский", LocaleHelper.gameTextDisplayName("russian"));
        assertEquals("", LocaleHelper.gameTextDisplayName(null));
        assertEquals("", LocaleHelper.gameTextDisplayName(""));
        // A pack added after this file was written is still pickable.
        assertEquals("Esperanto", LocaleHelper.gameTextDisplayName("esperanto"));
    }
}
