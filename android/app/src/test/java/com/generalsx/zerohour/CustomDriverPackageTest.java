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

package com.generalsx.zerohour;

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
}
