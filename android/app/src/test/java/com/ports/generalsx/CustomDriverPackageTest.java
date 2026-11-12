// GeneralsX @build Android security 10/11/2026
//
// Two attacker-supplied inputs the launcher parses before the engine ever sees them:
//
//   CustomDriverPackage.extract() -- a driver package the player picked, which is a zip that
//   must not be able to write outside its staging directory (Zip Slip), exhaust storage or the
//   entry budget, or name a library that is not a 64-bit AArch64 ELF.
//
//   GeneralsZHActivity.isSafeReplayName() -- a replay name from an Intent extra, which is
//   appended to the engine's command line, so it must be a bare file name.

package com.ports.generalsx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RunWith(RobolectricTestRunner.class)
public class CustomDriverPackageTest {

    private static File tempDir() throws Exception {
        File dir = new File(RuntimeEnvironment.getApplication().getCacheDir(),
            "drv-" + System.nanoTime());
        assertTrue(dir.mkdirs());
        return dir;
    }

    private static byte[] zipOf(String[] names, byte[][] contents) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < names.length; i++) {
                zip.putNextEntry(new ZipEntry(names[i]));
                if (contents != null && contents[i] != null) {
                    zip.write(contents[i]);
                }
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /**
     * A minimal stored-entry zip writer that, unlike java.util.zip, permits duplicate names -- the
     * only way to build the duplicate-entry archive the validator must refuse.
     */
    private static byte[] zipWithDuplicates(String[] names, byte[][] contents) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] offsets = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            byte[] name = names[i].getBytes(StandardCharsets.UTF_8);
            byte[] data = contents[i] == null ? new byte[0] : contents[i];
            offsets[i] = out.size();
            le32(out, 0x04034b50);
            le16(out, 20); le16(out, 0); le16(out, 0); le16(out, 0); le16(out, 0);
            le32(out, crc32(data));
            le32(out, data.length); le32(out, data.length);
            le16(out, name.length); le16(out, 0);
            out.write(name); out.write(data);
        }
        int cdStart = out.size();
        for (int i = 0; i < names.length; i++) {
            byte[] name = names[i].getBytes(StandardCharsets.UTF_8);
            byte[] data = contents[i] == null ? new byte[0] : contents[i];
            le32(out, 0x02014b50);
            le16(out, 20); le16(out, 20);
            le16(out, 0); le16(out, 0); le16(out, 0); le16(out, 0);
            le32(out, crc32(data));
            le32(out, data.length); le32(out, data.length);
            le16(out, name.length); le16(out, 0); le16(out, 0); le16(out, 0); le16(out, 0);
            le32(out, 0); le32(out, offsets[i]);
            out.write(name);
        }
        int cdSize = out.size() - cdStart;
        le32(out, 0x06054b50);
        le16(out, 0); le16(out, 0);
        le16(out, names.length); le16(out, names.length);
        le32(out, cdSize); le32(out, cdStart);
        le16(out, 0);
        return out.toByteArray();
    }

    private static void le16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff); o.write((v >> 8) & 0xff);
    }

    private static void le32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff); o.write((v >> 8) & 0xff);
        o.write((v >> 16) & 0xff); o.write((v >> 24) & 0xff);
    }

    private static int crc32(byte[] data) {
        java.util.zip.CRC32 c = new java.util.zip.CRC32();
        c.update(data);
        return (int) c.getValue();
    }

    private static final String META_OK =
        "{\"name\":\"Turnip\",\"libraryName\":\"libvulkan_freedreno.so\"}";

    /** A 20-byte AArch64 little-endian ELF header followed by padding. */
    private static byte[] aarch64Elf() {
        byte[] b = new byte[64];
        b[0] = 0x7f; b[1] = 'E'; b[2] = 'L'; b[3] = 'F';
        b[4] = 2;   // ELFCLASS64
        b[5] = 1;   // ELFDATA2LSB
        b[18] = (byte) 0xB7;  // EM_AARCH64 low byte
        return b;
    }

    /** An x86-64 ELF: same shape, wrong machine. */
    private static byte[] x86Elf() {
        byte[] b = new byte[64];
        b[0] = 0x7f; b[1] = 'E'; b[2] = 'L'; b[3] = 'F';
        b[4] = 2;
        b[5] = 1;
        b[18] = 0x3E;  // EM_X86_64
        return b;
    }

    @Test
    public void aValidPackageWithAnAarch64DriverIsAccepted() throws Exception {
        byte[] zip = zipOf(
            new String[] {"meta.json", "libvulkan_freedreno.so"},
            new byte[][] { META_OK.getBytes(StandardCharsets.UTF_8), aarch64Elf() });
        File tmp = tempDir();
        CustomDriverPackage.Package pkg = CustomDriverPackage.extract(
            new ByteArrayInputStream(zip), tmp, 34);
        assertEquals("libvulkan_freedreno.so", pkg.meta.libraryName);
    }

    /** A ".." entry must never escape the staging directory (Zip Slip). */
    @Test
    public void zipSlipIsRefused() throws Exception {
        byte[] zip = zipOf(
            new String[] {"../../evil.so", "meta.json"},
            new byte[][] { aarch64Elf(), META_OK.getBytes(StandardCharsets.UTF_8) });
        File tmp = tempDir();
        try {
            CustomDriverPackage.extract(new ByteArrayInputStream(zip), tmp, 34);
            org.junit.Assert.fail("a traversal entry must be refused");
        } catch (CustomDriverPackage.ImportException e) {
            assertEquals(CustomDriverPackage.Reason.UNSAFE_PATH, e.reason);
        }
    }

    /** An absolute path entry is refused too. */
    @Test
    public void absolutePathEntryIsRefused() throws Exception {
        byte[] zip = zipOf(
            new String[] {"/etc/evil.so", "meta.json"},
            new byte[][] { aarch64Elf(), META_OK.getBytes(StandardCharsets.UTF_8) });
        File tmp = tempDir();
        try {
            CustomDriverPackage.extract(new ByteArrayInputStream(zip), tmp, 34);
            org.junit.Assert.fail("an absolute entry must be refused");
        } catch (CustomDriverPackage.ImportException e) {
            assertEquals(CustomDriverPackage.Reason.UNSAFE_PATH, e.reason);
        }
    }

    /** A named driver that is not a 64-bit AArch64 ELF is refused. */
    @Test
    public void aNonAarch64DriverIsRefused() throws Exception {
        byte[] zip = zipOf(
            new String[] {"meta.json", "libvulkan_freedreno.so"},
            new byte[][] { META_OK.getBytes(StandardCharsets.UTF_8), x86Elf() });
        File tmp = tempDir();
        try {
            CustomDriverPackage.extract(new ByteArrayInputStream(zip), tmp, 34);
            org.junit.Assert.fail("an x86 driver must be refused");
        } catch (CustomDriverPackage.ImportException e) {
            assertEquals(CustomDriverPackage.Reason.BAD_LIBRARY, e.reason);
        }
    }

    /** A file that is not a zip at all is refused, not silently accepted as empty. */
    @Test
    public void aNonZipIsRefused() throws Exception {
        File tmp = tempDir();
        try {
            CustomDriverPackage.extract(
                new ByteArrayInputStream("this is not a zip".getBytes(StandardCharsets.UTF_8)),
                tmp, 34);
            org.junit.Assert.fail("a non-zip must be refused");
        } catch (CustomDriverPackage.ImportException e) {
            assertEquals(CustomDriverPackage.Reason.NOT_A_ZIP, e.reason);
        }
    }

    /** A package whose meta.json names a library that is not present is refused. */
    @Test
    public void aMissingNamedLibraryIsRefused() throws Exception {
        byte[] zip = zipOf(
            new String[] {"meta.json"},
            new byte[][] { META_OK.getBytes(StandardCharsets.UTF_8) });
        File tmp = tempDir();
        try {
            CustomDriverPackage.extract(new ByteArrayInputStream(zip), tmp, 34);
            org.junit.Assert.fail("a package without its named library must be refused");
        } catch (CustomDriverPackage.ImportException e) {
            assertEquals(CustomDriverPackage.Reason.MISSING_LIBRARY, e.reason);
        }
    }

    /** The ELF check itself: AArch64 yes, x86/32-bit/wrong-magic no. */
    @Test
    public void isAarch64ElfDistinguishesArchitectures() throws Exception {
        File aarch64 = new File(tempDir(), "a.so");
        java.nio.file.Files.write(aarch64.toPath(), aarch64Elf());
        assertTrue(CustomDriverPackage.isAarch64Elf(aarch64));

        File x86 = new File(tempDir(), "x.so");
        java.nio.file.Files.write(x86.toPath(), x86Elf());
        assertFalse(CustomDriverPackage.isAarch64Elf(x86));

        File text = new File(tempDir(), "t.so");
        java.nio.file.Files.write(text.toPath(), "not an elf".getBytes(StandardCharsets.UTF_8));
        assertFalse(CustomDriverPackage.isAarch64Elf(text));

        File tiny = new File(tempDir(), "tiny.so");
        java.nio.file.Files.write(tiny.toPath(), new byte[] {0x7f, 'E'});
        assertFalse(CustomDriverPackage.isAarch64Elf(tiny));
    }

    /**
     * GeneralsX @feature Android security 12/11/2026 A package that names the same file twice is
     * refused: two entries writing one path is how a validator and a loader end up disagreeing
     * about what a file contains.
     */
    @Test
    public void aDuplicateEntryIsRefused() throws Exception {
        byte[] zip = zipWithDuplicates(
            new String[] {"meta.json", "libvulkan_freedreno.so", "libvulkan_freedreno.so"},
            new byte[][] { META_OK.getBytes(StandardCharsets.UTF_8), aarch64Elf(), aarch64Elf() });
        File tmp = tempDir();
        try {
            CustomDriverPackage.extract(new ByteArrayInputStream(zip), tmp, 34);
            org.junit.Assert.fail("a duplicate entry must be refused");
        } catch (CustomDriverPackage.ImportException e) {
            assertEquals(CustomDriverPackage.Reason.DUPLICATE_ENTRY, e.reason);
        }
    }

    /**
     * GeneralsX @feature Android security 12/11/2026 A driver package is metadata plus shared
     * libraries. Any other file type (here, a second archive) is refused rather than unpacked
     * beside a library the app is about to dlopen.
     */
    @Test
    public void anUnexpectedFileTypeIsRefused() throws Exception {
        byte[] zip = zipOf(
            new String[] {"meta.json", "libvulkan_freedreno.so", "payload.apk"},
            new byte[][] { META_OK.getBytes(StandardCharsets.UTF_8), aarch64Elf(), aarch64Elf() });
        File tmp = tempDir();
        try {
            CustomDriverPackage.extract(new ByteArrayInputStream(zip), tmp, 34);
            org.junit.Assert.fail("an unexpected file type must be refused");
        } catch (CustomDriverPackage.ImportException e) {
            assertEquals(CustomDriverPackage.Reason.UNEXPECTED_FILE, e.reason);
        }
    }

    /** The allowed-type rule itself, so a later loosening is visible in a test. */
    @Test
    public void onlyMetadataAndLibrariesAreAllowedEntries() {
        assertTrue(CustomDriverPackage.isAllowedDriverEntry("meta.json"));
        assertTrue(CustomDriverPackage.isAllowedDriverEntry("libvulkan_freedreno.so"));
        assertTrue(CustomDriverPackage.isAllowedDriverEntry("sub/libvulkan_freedreno.so"));
        assertTrue(CustomDriverPackage.isAllowedDriverEntry("README.md"));
        assertFalse(CustomDriverPackage.isAllowedDriverEntry("payload.apk"));
        assertFalse(CustomDriverPackage.isAllowedDriverEntry("run.sh"));
        assertFalse(CustomDriverPackage.isAllowedDriverEntry("driver.dex"));
        assertFalse(CustomDriverPackage.isAllowedDriverEntry("blob.bin"));
    }

    /**
     * GeneralsX @feature Android security 12/11/2026 A package is verified only when its main
     * library's SHA-256 is one the port vouches for. Anything else is accepted but unverified, and
     * the caller must say so. This pins both directions of the check.
     */
    @Test
    public void aPackageIsVerifiedOnlyOnAContentHashMatch() throws Exception {
        File trusted = new File(tempDir(), "trusted.so");
        java.nio.file.Files.write(trusted.toPath(), aarch64Elf());
        String trustedHash = CustomDriverPackage.sha256Hex(trusted);

        // The named build, matched by content: verified.
        assertTrue(CustomDriverPackage.isVerifiedDriver(trusted, new String[] { trustedHash }));

        // The same bytes under a different name are still that build (content, not name).
        File renamed = new File(tempDir(), "vulkan.ad07xx.so");
        java.nio.file.Files.write(renamed.toPath(), aarch64Elf());
        assertTrue(CustomDriverPackage.isVerifiedDriver(renamed, new String[] { trustedHash }));

        // One byte different from a trusted build is not that build.
        byte[] tampered = aarch64Elf();
        tampered[tampered.length - 1] ^= 0x01;
        File tamperedFile = new File(tempDir(), "tampered.so");
        java.nio.file.Files.write(tamperedFile.toPath(), tampered);
        assertFalse(CustomDriverPackage.isVerifiedDriver(tamperedFile, new String[] { trustedHash }));

        // An allowlist that does not name this build verifies nothing.
        assertFalse(CustomDriverPackage.isVerifiedDriver(trusted, new String[0]));

        // The allowlist this port actually ships is non-empty and well-formed hex.
        assertTrue(CustomDriverPackage.VERIFIED_DRIVER_SHA256.length > 0);
        for (String h : CustomDriverPackage.VERIFIED_DRIVER_SHA256) {
            assertTrue(h.matches("[0-9a-f]{64}"));
        }
    }

    // ------------------------------------------------------------------ replay name validation

    @Test
    public void replayNamesWithPathComponentsAreRefused() {
        assertFalse(GeneralsZHActivity.isSafeReplayName("../etc/passwd"));
        assertFalse(GeneralsZHActivity.isSafeReplayName("sub/dir.rep"));
        assertFalse(GeneralsZHActivity.isSafeReplayName("sub\\dir.rep"));
        assertFalse(GeneralsZHActivity.isSafeReplayName(".."));
        assertFalse(GeneralsZHActivity.isSafeReplayName("."));
        assertFalse(GeneralsZHActivity.isSafeReplayName(""));
        assertFalse(GeneralsZHActivity.isSafeReplayName(null));
        assertFalse(GeneralsZHActivity.isSafeReplayName(
            "a".repeat(256) + ".rep"));
    }

    @Test
    public void aPlainReplayFileNameIsAccepted() {
        assertTrue(GeneralsZHActivity.isSafeReplayName("LastReplay.rep"));
        assertTrue(GeneralsZHActivity.isSafeReplayName("my game 2026.rep"));
    }

    // ------------------------------------------------------------------ replay intent extras

    private static java.util.List<String> argsFor(android.content.Intent intent) {
        java.util.List<String> args = new java.util.ArrayList<>();
        GeneralsZHActivity.appendReplayExtras(args, intent);
        return args;
    }

    @Test
    public void aReplayIntentTranslatesToBoundedEngineArguments() {
        android.content.Intent intent = new android.content.Intent()
            .putExtra(GeneralsZHActivity.EXTRA_REPLAY, "LastReplay.rep")
            .putExtra(GeneralsZHActivity.EXTRA_FAST_TO, 1200)
            .putExtra(GeneralsZHActivity.EXTRA_AUTO_QUIT, true)
            .putExtra(GeneralsZHActivity.EXTRA_CRC_EVERY_FRAME, true);
        assertEquals(java.util.Arrays.asList(
                "-replay", "LastReplay.rep", "-gxFastTo", "1200",
                "-gxAutoQuit", "-gxCrcEveryFrame"),
            argsFor(intent));
    }

    @Test
    public void anUnsafeReplayNameProducesNoArgumentsAtAll() {
        // A traversal name must not become "-replay ../etc/passwd": nothing is emitted, so the
        // engine never sees the extra. The other extras are dropped with it.
        android.content.Intent intent = new android.content.Intent()
            .putExtra(GeneralsZHActivity.EXTRA_REPLAY, "../../etc/passwd")
            .putExtra(GeneralsZHActivity.EXTRA_AUTO_QUIT, true);
        assertTrue(argsFor(intent).isEmpty());
    }

    @Test
    public void aNegativeOrAbsurdFrameNumberIsRefused() {
        for (int bad : new int[] { 0, -1, Integer.MIN_VALUE }) {
            android.content.Intent intent = new android.content.Intent()
                .putExtra(GeneralsZHActivity.EXTRA_REPLAY, "r.rep")
                .putExtra(GeneralsZHActivity.EXTRA_FAST_TO, bad);
            assertFalse(String.format("frame %d must not become a -gxFastTo argument", bad),
                argsFor(intent).contains("-gxFastTo"));
        }
    }

    @Test
    public void aNullIntentProducesNoArguments() {
        assertTrue(argsFor(null).isEmpty());
    }

    @Test
    public void onlyTheKnownFlagsCanBeProduced() {
        // An extra that is not one of the four known names must have no effect: an external
        // caller cannot smuggle an arbitrary engine argument (e.g. "-nologo") through an extra.
        android.content.Intent intent = new android.content.Intent()
            .putExtra("gx_unknown_flag", "-win")
            .putExtra("nologo", true);
        assertTrue(argsFor(intent).isEmpty());
    }
}
