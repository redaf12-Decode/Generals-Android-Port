// GeneralsX @build Android CI 06/11/2026
//
// Two pieces of the launcher that read attacker-supplied or user-supplied bytes:
//
//   NetworkTrace.redact() -- a support log is shared by hand with whoever is helping, so a token
//   that leaks here leaks out of the app entirely. The placeholder keeps the length because "was a
//   token sent, and of the shape we expected" is the question the log has to answer.
//
//   ModHealthCheck -- a mod is a zip the player downloaded from anywhere. Its BIG directory table
//   is parsed before the engine ever sees it, so the bounds checks are the only thing between a
//   malformed table and the importer.

package com.generalsx.zerohour;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@RunWith(RobolectricTestRunner.class)
public class NetworkTraceRedactTest {

    @Test
    public void nullAndEmptyAreEmpty() {
        assertEquals("", NetworkTrace.redact(null));
        assertEquals("", NetworkTrace.redact(""));
    }

    @Test
    public void textWithoutSecretsIsUnchanged() {
        String text = "GET /motd HTTP/1.1\nHost: playgenerals.online\n";
        assertEquals(text, NetworkTrace.redact(text));
    }

    @Test
    public void bearerTokenIsReplacedAndItsLengthKept() {
        String out = NetworkTrace.redact("Authorization: Bearer abcdefghijklmnop.qrstuvwx\n");
        assertFalse("the token itself must not survive", out.contains("abcdefghijklmnop"));
        assertTrue(out.contains("Bearer <token, 25 chars>"));
    }

    @Test
    public void sessionRefreshAndAccessTokenFieldsAreReplaced() {
        String body = "{\"session_token\":\"0123456789abcdef\",\"refresh_token\":\"fedcba9876543210\"}";
        String out = NetworkTrace.redact(body);
        assertFalse(out.contains("0123456789abcdef"));
        assertFalse(out.contains("fedcba9876543210"));
        assertTrue(out.contains("\"session_token\":\"<token, 16 chars>\""));
        assertTrue(out.contains("\"refresh_token\":\"<token, 16 chars>\""));
    }

    /** A short value is not a token, and the JSON shape around it must survive untouched. */
    @Test
    public void shortValuesAreLeftAlone() {
        String body = "{\"session_token\":\"abc\"}";
        assertEquals(body, NetworkTrace.redact(body));
    }

    /** A bearer token shorter than 8 characters is not replaced; the header name still is intact. */
    @Test
    public void shortBearerValueIsLeftAlone() {
        String text = "Authorization: Bearer abc";
        assertEquals(text, NetworkTrace.redact(text));
    }

    @Test
    public void everyTokenOnTheLineIsReplaced() {
        String out = NetworkTrace.redact(
            "Bearer aaaaaaaa and Bearer bbbbbbbb and \"access_token\":\"cccccccc\"");
        assertFalse(out.contains("aaaaaaaa"));
        assertFalse(out.contains("bbbbbbbb"));
        assertFalse(out.contains("cccccccc"));
        assertEquals(3, countOccurrences(out, "<token, 8 chars>"));
    }

    /**
     * The character class has to cover the shapes the server actually issues: a dotted JWT and a
     * standard base64 value with '+', '/' and '=' padding. A token that only half-matches would be
     * replaced in part and left in the log in part, which is worse than not replacing it.
     */
    @Test
    public void bearerTokenCharacterClassCoversJwtAndBase64Shapes() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        String out = NetworkTrace.redact("Authorization: Bearer " + jwt);
        assertFalse("a JWT must not survive in the log", out.contains("eyJhbGciOiJIUzI1NiJ9"));

        String base64 = "AbCd+Ef/Gh==";
        String out2 = NetworkTrace.redact("Authorization: Bearer " + base64);
        assertFalse(out2.contains(base64));
        assertTrue(out2.contains("Bearer <token, " + base64.length() + " chars>"));
    }

    @Test
    public void logFileLivesUnderTheAppAndIsNamed() {
        android.app.Application app = org.robolectric.RuntimeEnvironment.getApplication();
        File log = NetworkTrace.logFile(app);
        assertEquals("generals-network.log", log.getName());
        // GeneralsX @tweak Android port 08/11/2026 The applicationId (and so the external
        // files path) is no longer the Java package; assert against the id the app actually
        // reports rather than a hardcoded string, which is what the device layout uses.
        assertTrue(log.getAbsolutePath().contains(app.getPackageName()));
    }

    // ------------------------------------------------------------------ BIG directory table

    /**
     * A BIG header whose file table claims a file that runs past the end of the archive. The
     * importer trusts the table, so this check is the only thing standing between a malformed
     * table and it -- a size or offset it does not reject is a read out of bounds.
     */
    @Test
    public void bigTableRejectsAnEntryThatRunsPastTheEndOfTheArchive() throws IOException {
        // One entry, 4 bytes from offset 0 in a 30-byte file: it fits, so the table is accepted.
        byte[] inRange = bigFile(0x42494746, 1, 0, 4, "a.txt");
        assertEquals("an entry inside the archive is accepted",
            1, ModHealthCheck.checkBigTable(write("in-range", inRange)));

        // Same table, but the entry now starts at 32 in a 30-byte file.
        byte[] outOfRange = bigFile(0x42494746, 1, 32, 4, "a.txt");
        assertEquals("start past the end must be rejected",
            -1, ModHealthCheck.checkBigTable(write("bad-start", outOfRange)));

        // And an entry whose size runs past the end even though its start does not.
        byte[] overrun = bigFile(0x42494746, 1, 0, 1000, "a.txt");
        assertEquals("size past the end must be rejected",
            -1, ModHealthCheck.checkBigTable(write("bad-size", overrun)));
    }

    @Test
    public void bigTableRejectsAnImpossibleEntryCountOrHeaderSize() throws IOException {
        // MAX_BIG_ENTRIES is 200000; Integer.MAX_VALUE must not be believed.
        assertEquals(-1, ModHealthCheck.checkBigTable(
            write("bad-count", bigFile(0x42494746, Integer.MAX_VALUE, 0, 0, "a.txt"))));
        // A header size below 16 cannot even hold the header that declared it.
        assertEquals(-1, ModHealthCheck.checkBigTable(
            write("bad-header", bigFileWithHeader(0x42494746, 1, 8, 0, 4))));
        // A header size larger than the file itself cannot be honoured either.
        assertEquals(-1, ModHealthCheck.checkBigTable(
            write("huge-header", bigFileWithHeader(0x42494746, 1, 4096, 0, 4))));
        // A file shorter than a header is not an archive.
        File tiny = write("tiny", new byte[] { 0x42, 0x49, 0x47, 0x46 });
        assertEquals(-1, ModHealthCheck.checkBigTable(tiny));
    }

    @Test
    public void bigTableRejectsTheWrongMagicAndAnUnreadableEntry() throws IOException {
        assertEquals("not a BIGF/BIG4 magic", -1, ModHealthCheck.checkBigTable(
            write("wrong-magic", bigFile(0x42494741, 1, 0, 4, "a.txt"))));
        assertEquals("null and non-files are rejected",
            -1, ModHealthCheck.checkBigTable(null));
        // A name whose header runs out before its NUL: the scan must stop at the header, not read
        // on into the file or past its end.
        assertEquals(-1, ModHealthCheck.checkBigTable(write("no-nul", noNulName())));
    }

    /** A BIG archive with one entry, laid out the way checkBigTable reads it. */
    private static byte[] bigFile(int magic, int count, long start, long size, String name) {
        byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
        // 16-byte header + 8-byte (start, size) + name + NUL, which is what the header size
        // declares and what is actually written.
        int headerSize = 16 + 8 + nameBytes.length + 1;
        return bigArchive(magic, count, headerSize, start, size, nameBytes, true);
    }

    /** The same layout with a header size that does not match the entry, to test the bound. */
    private static byte[] bigFileWithHeader(int magic, int count, int headerSize, long start,
            long size) {
        byte[] name = "a.txt".getBytes(StandardCharsets.US_ASCII);
        return bigArchive(magic, count, headerSize, start, size, name, true);
    }

    /** The same layout with no NUL terminator after the name. */
    private static byte[] noNulName() {
        byte[] name = { 'a', 'b', 'c' };
        return bigArchive(0x42494746, 1, 16 + 8 + name.length, 0, 4, name, false);
    }

    private static byte[] bigArchive(int magic, int count, int headerSize, long start, long size,
            byte[] name, boolean nulTerminate) {
        int length = 16 + 8 + name.length + (nulTerminate ? 1 : 0);
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(length);
        buf.putInt(magic);        // readInt() is big-endian, and so is the magic
        buf.putInt(0);            // the little-endian declared size, which the check ignores
        buf.putInt(count);
        buf.putInt(headerSize);
        buf.putInt((int) start);
        buf.putInt((int) size);
        buf.put(name);
        if (nulTerminate) {
            buf.put((byte) 0);
        }
        return buf.array();
    }

    private static File write(String name, byte[] bytes) throws IOException {
        File f = new File(org.robolectric.RuntimeEnvironment.getApplication().getCacheDir(),
            "big-" + name + "-" + System.nanoTime() + ".big");
        Files.write(f.toPath(), bytes);
        return f;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0, index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    // ------------------------------------------------------------------ ModHealthCheck BIG table

    /** Builds a BIG file: "BIGF", declared size, entry count, header size, then the table. */
    private static File bigFile(String name, String[] entries) throws IOException {
        File file = new File(org.robolectric.RuntimeEnvironment.getApplication().getCacheDir(),
            name + "-" + System.nanoTime() + ".big");
        try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
            out.writeInt(0x42494746); // BIGF
            out.writeInt(0);
            out.writeInt(entries.length);
            out.writeInt(16 + tableSize(entries));
            for (String entry : entries) {
                out.writeInt(0);
                out.writeInt(0);
                out.write(entry.getBytes(StandardCharsets.US_ASCII));
                out.write(0);
            }
        }
        return file;
    }

    private static int tableSize(String[] entries) {
        int size = 0;
        for (String entry : entries) {
            size += 8 + entry.length() + 1;
        }
        return size;
    }

    @Test
    public void aWellFormedTableIsRead() throws IOException {
        File big = bigFile("good", new String[] { "data\\ini\\default\\weather.ini", "a.big" });
        assertEquals(2, ModHealthCheck.checkBigTable(big));
    }

    @Test
    public void aMissingOrTinyFileIsRejected() {
        assertEquals(-1, ModHealthCheck.checkBigTable(null));
        assertEquals(-1, ModHealthCheck.checkBigTable(new File("/does/not/exist.big")));
    }

    @Test
    public void aWrongMagicIsRejected() throws IOException {
        File file = new File(org.robolectric.RuntimeEnvironment.getApplication().getCacheDir(),
            "wrong-" + System.nanoTime() + ".big");
        Files.write(file.toPath(), "NOPE not a big file at all".getBytes(StandardCharsets.US_ASCII));
        assertEquals(-1, ModHealthCheck.checkBigTable(file));
    }

    /** A count larger than the header could hold is refused before the loop walks off the end. */
    @Test
    public void anOverlongEntryCountIsRejected() throws IOException {
        File file = new File(org.robolectric.RuntimeEnvironment.getApplication().getCacheDir(),
            "count-" + System.nanoTime() + ".big");
        try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
            out.writeInt(0x42494746);
            out.writeInt(0);
            out.writeInt(1_000_000);
            out.writeInt(16);
        }
        assertEquals(-1, ModHealthCheck.checkBigTable(file));
    }

    @Test
    public void anEntryPointingPastTheEndOfTheFileIsRejected() throws IOException {
        File file = new File(org.robolectric.RuntimeEnvironment.getApplication().getCacheDir(),
            "past-" + System.nanoTime() + ".big");
        try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
            out.writeInt(0x42494746);
            out.writeInt(0);
            out.writeInt(1);
            out.writeInt(16 + 8 + 5);
            out.writeInt(0x7FFFFFFF); // start, far past EOF
            out.writeInt(0);
            out.write("a.big".getBytes(StandardCharsets.US_ASCII));
            out.write(0);
        }
        assertEquals(-1, ModHealthCheck.checkBigTable(file));
    }

    /** A name that never hits its terminator must not read past the header. */
    @Test
    public void anUnterminatedNameIsRejected() throws IOException {
        File file = new File(org.robolectric.RuntimeEnvironment.getApplication().getCacheDir(),
            "noname-" + System.nanoTime() + ".big");
        try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
            out.writeInt(0x42494746);
            out.writeInt(0);
            out.writeInt(1);
            out.writeInt(24);
            out.writeInt(0);
            out.writeInt(0);
            out.write("abcdefgh".getBytes(StandardCharsets.US_ASCII)); // no terminator
        }
        assertEquals(-1, ModHealthCheck.checkBigTable(file));
    }

    @Test
    public void anEmptyArchiveIsValidAndReadsAsZeroEntries() throws IOException {
        File big = bigFile("empty", new String[0]);
        assertEquals(0, ModHealthCheck.checkBigTable(big));
    }
}
