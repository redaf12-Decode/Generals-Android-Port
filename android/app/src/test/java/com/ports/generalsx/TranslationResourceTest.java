// GeneralsX @build Android CI 06/11/2026
//
// The launcher ships thirteen languages and AGENTS.md requires a new string to reach all of them
// in the same commit. Two failures are possible and neither shows up in an English build: a
// locale missing a name (Android falls back to English silently, so a half-translated screen looks
// translated) and a locale whose translation dropped or reordered a printf argument (the screen
// shows "%d" or the wrong number).
//
// This reads res/values*/strings.xml directly, so it runs as a plain JVM test with no Android
// runtime and no emulator.

package com.ports.generalsx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TranslationResourceTest {

    private static final String BASE_LOCALE = "values";

    /**
     * The positional-argument form (%1$s) is the one the launcher's own strings use, so it has to
     * be matched or those strings would be checked for nothing. No space flag: "+25% Firepower"
     * is a percent sign followed by a word, not a conversion.
     */
    private static final Pattern PRINTF = Pattern.compile(
        "%((?:(\\d+)\\$)?[-+#0]*\\d*(?:\\.\\d+)?(?:hh|h|ll|l|L|I64)?[sSdiuoxXcCfFeEgGp])");

    private static final Pattern STRING_TAG =
        Pattern.compile("<string\\s+name=\"([^\"]+)\"[^>]*>(.*?)</string>", Pattern.DOTALL);
    private static File resDir() {
        File dir = new File("src/main/res");
        if (dir.isDirectory()) {
            return dir;
        }
        dir = new File("app/src/main/res");
        assertTrue("could not locate res/ from " + new File(".").getAbsolutePath(), dir.isDirectory());
        return dir;
    }

    /** Every values-* folder that holds a strings.xml, keyed by folder name. */
    private static Map<String, File> localeFiles() {
        File[] children = resDir().listFiles();
        assertTrue(children != null);
        Map<String, File> files = new TreeMap<>();
        for (File child : children) {
            if (child.isDirectory() && child.getName().startsWith("values")) {
                File strings = new File(child, "strings.xml");
                if (strings.isFile()) {
                    files.put(child.getName(), strings);
                }
            }
        }
        return files;
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static Map<String, String> strings(File file) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = STRING_TAG.matcher(read(file));
        while (m.find()) {
            out.put(m.group(1), m.group(2));
        }
        return out;
    }

    @Test
    public void everyLocaleParsesAsXmlAndHasStrings() throws IOException {
        Map<String, File> files = localeFiles();
        assertTrue("expected the base locale plus its translations", files.size() >= 13);
        for (Map.Entry<String, File> entry : files.entrySet()) {
            // A malformed file throws here, which is the failure aapt2 would report much later.
            javax.xml.parsers.DocumentBuilderFactory factory =
                javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            try {
                factory.newDocumentBuilder().parse(entry.getValue());
            } catch (Exception e) {
                throw new AssertionError(entry.getKey() + "/strings.xml is not valid XML: " + e, e);
            }
            assertFalse(entry.getKey() + " has no strings", strings(entry.getValue()).isEmpty());
        }
    }

    @Test
    public void everyLocaleDeclaresTheSameStringNames() throws IOException {
        Map<String, File> files = localeFiles();
        Map<String, String> base = strings(files.get(BASE_LOCALE));
        assertTrue("base locale is empty", base.size() > 100);

        for (Map.Entry<String, File> entry : files.entrySet()) {
            if (BASE_LOCALE.equals(entry.getKey())) {
                continue;
            }
            TreeSet<String> actual = new TreeSet<>(strings(entry.getValue()).keySet());
            TreeSet<String> missing = new TreeSet<>(base.keySet());
            missing.removeAll(actual);
            TreeSet<String> extra = new TreeSet<>(actual);
            extra.removeAll(base.keySet());

            assertEquals(entry.getKey() + " is missing these strings (they would silently fall back"
                + " to English): " + missing, new TreeSet<String>(), missing);
            assertEquals(entry.getKey() + " declares strings the base locale does not: " + extra,
                new TreeSet<String>(), extra);
        }
    }

    /** A translated value that is empty renders as nothing where English shows a sentence. */
    @Test
    public void noTranslationIsEmptyWhereEnglishHasText() throws IOException {
        Map<String, File> files = localeFiles();
        Map<String, String> base = strings(files.get(BASE_LOCALE));

        for (Map.Entry<String, File> entry : files.entrySet()) {
            Map<String, String> translated = strings(entry.getValue());
            for (Map.Entry<String, String> string : translated.entrySet()) {
                String english = base.get(string.getKey());
                if (english == null || english.trim().isEmpty()) {
                    continue;
                }
                assertFalse(entry.getKey() + "/" + string.getKey() + " is empty but English is not",
                    string.getValue().trim().isEmpty());
            }
        }
    }

    /** A dropped or reordered %s/%d is a crash or a wrong number on screen. */
    @Test
    public void printfArgumentsMatchTheBaseLocale() throws IOException {
        Map<String, File> files = localeFiles();
        Map<String, String> base = strings(files.get(BASE_LOCALE));

        for (Map.Entry<String, File> entry : files.entrySet()) {
            if (BASE_LOCALE.equals(entry.getKey())) {
                continue;
            }
            for (Map.Entry<String, String> string : strings(entry.getValue()).entrySet()) {
                String english = base.get(string.getKey());
                if (english == null) {
                    continue;
                }
                String expected = printfSignature(english);
                if (expected.isEmpty()) {
                    continue;
                }
                assertEquals(entry.getKey() + "/" + string.getKey()
                    + " changed its printf arguments", expected, printfSignature(string.getValue()));
            }
        }
    }

    /**
     * A comparable signature for a string's printf arguments, or "" when it has none.
     *
     * Positional arguments (%1$s) are meant to be reorderable -- that is why they exist, and Korean
     * and Chinese legitimately move %2$s and %3$s to follow their own word order. So positional
     * arguments contribute their number and conversion as a sorted set, not in the order they
     * appear; plain arguments have no number and keep their order, which is what makes a dropped
     * one detectable.
     */
    private static String printfSignature(String value) {
        TreeSet<String> positional = new TreeSet<>();
        List<String> ordered = new ArrayList<>();
        Matcher m = PRINTF.matcher(value);
        while (m.find()) {
            if (m.group(2) == null) {
                ordered.add(m.group(1));
            } else {
                positional.add(m.group(2) + m.group(1));
            }
        }
        if (positional.isEmpty() && ordered.isEmpty()) {
            return "";
        }
        return positional + "|" + ordered;
    }

    /** A raw apostrophe is an aapt2 error, and every locale must escape it, not just values/. */
    @Test
    public void apostrophesAreEscapedInEveryLocale() throws IOException {
        for (Map.Entry<String, File> entry : localeFiles().entrySet()) {
            String xml = read(entry.getValue());
            Matcher m = STRING_TAG.matcher(xml);
            while (m.find()) {
                String value = m.group(2);
                for (int i = 0; i < value.length(); i++) {
                    if (value.charAt(i) != '\'') {
                        continue;
                    }
                    boolean escaped = i > 0 && value.charAt(i - 1) == '\\';
                    assertTrue(entry.getKey() + "/" + m.group(1)
                        + " has an unescaped apostrophe (aapt2 rejects it): " + value,
                        escaped);
                }
            }
        }
    }

    /** RTL screens mirror from the layout direction, which needs a real translation to exist. */
    @Test
    public void rightToLeftLanguagesArePresent() {
        Map<String, File> files = localeFiles();
        for (String locale : new String[] { "values-ar", "values-fa" }) {
            assertTrue(locale + " must be present: Arabic and Persian are offered by the picker",
                files.containsKey(locale));
        }
    }

    /** The launcher's own languages, one folder each. A tag with no folder falls back to English. */
    @Test
    public void theExpectedLocaleSetIsPresent() {
        Map<String, File> files = localeFiles();
        for (String locale : new String[] {
                "values", "values-ar", "values-b+isv", "values-de", "values-es", "values-fa",
                "values-fr", "values-ko", "values-pl", "values-pt-rBR", "values-ru", "values-uk",
                "values-zh" }) {
            assertTrue("missing " + locale + "/strings.xml", files.containsKey(locale));
        }
    }

    /** Two locales must not be byte-identical: that means one was copied and never translated. */
    @Test
    public void translationsAreNotJustCopiesOfEnglish() throws IOException {
        Map<String, File> files = localeFiles();
        Map<String, String> base = strings(files.get(BASE_LOCALE));
        for (Map.Entry<String, File> entry : files.entrySet()) {
            if (BASE_LOCALE.equals(entry.getKey())) {
                continue;
            }
            Map<String, String> translated = strings(entry.getValue());
            int identical = 0, total = 0;
            for (Map.Entry<String, String> string : translated.entrySet()) {
                if (!base.containsKey(string.getKey())) {
                    continue;
                }
                total++;
                if (base.get(string.getKey()).equals(string.getValue())) {
                    identical++;
                }
            }
            // Proper nouns and format-only strings legitimately match; a whole file does not.
            assertTrue(entry.getKey() + " looks untranslated (" + identical + "/" + total
                + " values identical to English)", total == 0 || identical < total);
        }
    }

    /** The set of languages the launcher offers must be the set of folders that exist. */
    @Test
    public void pickerLanguagesAndFoldersAgree() {
        java.util.Set<String> folders = new TreeSet<>();
        for (String folder : localeFiles().keySet()) {
            if (BASE_LOCALE.equals(folder)) {
                continue;
            }
            String tag = folder.substring("values-".length());
            if ("b+isv".equals(tag)) {
                tag = "isv";
            } else if (tag.contains("-r")) {
                tag = tag.replace("-r", "-");
            }
            folders.add(tag);
        }
        java.util.Set<String> offered = new TreeSet<>();
        for (String tag : LocaleHelper.SUPPORTED_TAGS) {
            if (!tag.isEmpty() && !"en".equals(tag)) {
                offered.add(tag);
            }
        }
        assertEquals("the language picker and the res/ folders must offer the same languages",
            offered, folders);
    }
}
