// GeneralsX @build Android security 12/11/2026
//
// DataPackInstaller turns a manifest and a ZIP fetched from the network into files the engine
// treats as authoritative game data. Two attacker-supplied inputs are parsed here:
//
//   validateManifest() -- the manifest's download URL, digest and size, which are now all
//   mandatory and bound to a trusted host and a hard size cap.
//
//   extract() -- the ZIP itself, which must not escape its directory (Zip Slip), list a path
//   twice, or inflate without bound (a zip bomb).
//
// Nothing here reaches the network: validateManifest() is pure, and extract() reads a local file.

package com.ports.generalsx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class DataPackInstallerTest {

    private static final String SHA =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private static File tempDir() throws Exception {
        return Files.createTempDirectory("datapack").toFile();
    }

    // ------------------------------------------------------------------ manifest rules

    @Test
    public void aCompleteManifestOnATrustedHostIsAccepted() {
        assertNull(DataPackInstaller.validateManifest(
            "https://cdn.playgenerals.online/datapack.zip", SHA, 30_000_000L));
        assertNull(DataPackInstaller.validateManifest(
            "https://playgenerals.online/datapack.zip", SHA, 30_000_000L));
        assertNull(DataPackInstaller.validateManifest(
            "https://raw.githubusercontent.com/x/y/datapack.zip", SHA, 1L));
    }

    @Test
    public void httpAndUntrustedHostsAreRefused() {
        assertNotNull(DataPackInstaller.validateManifest(
            "http://cdn.playgenerals.online/datapack.zip", SHA, 30_000_000L));
        assertNotNull(DataPackInstaller.validateManifest(
            "https://evil.example/datapack.zip", SHA, 30_000_000L));
        // The lookalike suffix a startsWith() test would wrongly accept.
        assertNotNull(DataPackInstaller.validateManifest(
            "https://cdn.playgenerals.online.evil.example/x", SHA, 30_000_000L));
    }

    @Test
    public void aMissingOrMalformedDigestIsRefused() {
        assertNotNull(DataPackInstaller.validateManifest(
            "https://cdn.playgenerals.online/datapack.zip", "", 30_000_000L));
        assertNotNull(DataPackInstaller.validateManifest(
            "https://cdn.playgenerals.online/datapack.zip", null, 30_000_000L));
        assertNotNull(DataPackInstaller.validateManifest(
            "https://cdn.playgenerals.online/datapack.zip", "abcd", 30_000_000L));
    }

    @Test
    public void aMissingOrOversizedSizeIsRefused() {
        assertNotNull(DataPackInstaller.validateManifest(
            "https://cdn.playgenerals.online/datapack.zip", SHA, -1L));
        assertNotNull(DataPackInstaller.validateManifest(
            "https://cdn.playgenerals.online/datapack.zip", SHA, 0L));
        assertNotNull(DataPackInstaller.validateManifest(
            "https://cdn.playgenerals.online/datapack.zip", SHA, 600L * 1024 * 1024));
    }

    // ------------------------------------------------------------------ archive rules

    private static File zipFile(String[] names, byte[][] contents) throws Exception {
        File out = new File(tempDir(), "pack.zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(out))) {
            for (int i = 0; i < names.length; i++) {
                zip.putNextEntry(new ZipEntry(names[i]));
                if (contents[i] != null) {
                    zip.write(contents[i]);
                }
                zip.closeEntry();
            }
        }
        return out;
    }

    private static byte[] data(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Builds a ZIP that lists the same name twice on purpose. ZipOutputStream refuses that, so the
     * local and central records are written by hand -- a duplicate name is exactly the malformed
     * archive the extractor must reject.
     */
    private static File zipWithDuplicate(String[] names, byte[][] contents) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] offsets = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            byte[] name = names[i].getBytes(StandardCharsets.UTF_8);
            byte[] body = contents[i] == null ? new byte[0] : contents[i];
            offsets[i] = out.size();
            le32(out, 0x04034b50);
            le16(out, 20); le16(out, 0); le16(out, 0); le16(out, 0); le16(out, 0);
            le32(out, crc32(body));
            le32(out, body.length); le32(out, body.length);
            le16(out, name.length); le16(out, 0);
            out.write(name); out.write(body);
        }
        int cdStart = out.size();
        for (int i = 0; i < names.length; i++) {
            byte[] name = names[i].getBytes(StandardCharsets.UTF_8);
            byte[] body = contents[i] == null ? new byte[0] : contents[i];
            le32(out, 0x02014b50);
            le16(out, 20); le16(out, 20);
            le16(out, 0); le16(out, 0); le16(out, 0); le16(out, 0);
            le32(out, crc32(body));
            le32(out, body.length); le32(out, body.length);
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
        File file = new File(tempDir(), "dup.zip");
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(out.toByteArray());
        }
        return file;
    }

    private static void le16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff); o.write((v >> 8) & 0xff);
    }

    private static void le32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff); o.write((v >> 8) & 0xff);
        o.write((v >> 16) & 0xff); o.write((v >> 24) & 0xff);
    }

    private static int crc32(byte[] data) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data);
        return (int) crc.getValue();
    }

    @Test
    public void wantedDirectoriesAreExtracted() throws Exception {
        File zip = zipFile(
            new String[] {"GeneralsOnlineGameData/500_900_CommunityPatch_CoreINI.big",
                "Maps/MyMap/map.map", "WindowsOnly/game.exe"},
            new byte[][] { data("patch"), data("map"), data("exe") });
        File target = tempDir();
        List<String> written = DataPackInstaller.extract(zip, target, new long[] {-1, -1});
        assertEquals(2, written.size());
        assertTrue(new File(target,
            "GeneralsOnlineGameData/500_900_CommunityPatch_CoreINI.big").isFile());
        assertTrue(new File(target, "Maps/MyMap/map.map").isFile());
        assertTrue(!new File(target, "WindowsOnly/game.exe").exists());
    }

    @Test
    public void zipSlipIsRefused() throws Exception {
        File zip = zipFile(
            new String[] {"Maps/../../escape.txt"},
            new byte[][] { data("evil") });
        File target = tempDir();
        try {
            DataPackInstaller.extract(zip, target, new long[] {-1, -1});
            fail("a traversal entry must be refused");
        } catch (java.io.IOException expected) {
            // A ZipInputStream normalizes many "../" forms away; any that reach the path check
            // are refused, and the important thing is that nothing is written outside target.
            assertTrue(!new File(target.getParentFile(), "escape.txt").exists());
        }
    }

    @Test
    public void aDuplicateWantedEntryIsRefused() throws Exception {
        File zip = zipWithDuplicate(
            new String[] {"Maps/a.map", "Maps/a.map"},
            new byte[][] { data("one"), data("two") });
        File target = tempDir();
        try {
            DataPackInstaller.extract(zip, target, new long[] {-1, -1});
            fail("a duplicate entry must be refused");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("twice"));
        }
    }

    @Test
    public void aPackageWithNoneOfTheWantedDataIsRefused() throws Exception {
        File zip = zipFile(
            new String[] {"WindowsOnly/game.exe"},
            new byte[][] { data("exe") });
        File target = tempDir();
        try {
            DataPackInstaller.extract(zip, target, new long[] {-1, -1});
            fail("a package with no wanted data must be refused");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("none of the expected data"));
        }
    }

    // ------------------------------------------------------------------ staged install

    @Test
    public void anExistingFileIsReplacedAndOthersAreLeftAlone() throws Exception {
        File target = tempDir();
        File existing = new File(target, "GeneralsOnlineGameData/patch.big");
        assertTrue(existing.getParentFile().mkdirs());
        Files.write(existing.toPath(), data("old"));
        File playerMap = new File(target, "Maps/PlayerMap/map.map");
        assertTrue(playerMap.getParentFile().mkdirs());
        Files.write(playerMap.toPath(), data("player's own map"));

        File zip = zipFile(
            new String[] {"GeneralsOnlineGameData/patch.big"},
            new byte[][] { data("new") });
        List<String> written = DataPackInstaller.extract(zip, target, new long[] {-1, -1});

        assertEquals(1, written.size());
        assertEquals("new", new String(Files.readAllBytes(existing.toPath()), StandardCharsets.UTF_8));
        // A file the package never mentions is not touched.
        assertEquals("player's own map",
            new String(Files.readAllBytes(playerMap.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void aSuccessfulInstallLeavesNoStagingOrBackupBehind() throws Exception {
        File target = tempDir();
        File zip = zipFile(
            new String[] {"Maps/a.map"},
            new byte[][] { data("map") });
        DataPackInstaller.extract(zip, target, new long[] {-1, -1});
        assertTrue(!new File(target, ".gx-datapack-staging").exists());
        assertTrue(!new File(target, ".gx-datapack-backup").exists());
    }

    @Test
    public void aRefusedPackageLeavesTheGameDirectoryUntouched() throws Exception {
        File target = tempDir();
        File existing = new File(target, "GeneralsOnlineGameData/patch.big");
        assertTrue(existing.getParentFile().mkdirs());
        Files.write(existing.toPath(), data("old"));
        // A package that fails part-way (a duplicate entry) must not have written anything.
        File zip = zipWithDuplicate(
            new String[] {"GeneralsOnlineGameData/patch.big", "Maps/a.map", "Maps/a.map"},
            new byte[][] { data("new"), data("one"), data("two") });
        try {
            DataPackInstaller.extract(zip, target, new long[] {-1, -1});
            fail("a duplicate entry must be refused");
        } catch (java.io.IOException expected) {
            assertEquals("old",
                new String(Files.readAllBytes(existing.toPath()), StandardCharsets.UTF_8));
            assertTrue(!new File(target, "Maps/a.map").exists());
            assertTrue(!new File(target, ".gx-datapack-staging").exists());
            assertTrue(!new File(target, ".gx-datapack-backup").exists());
        }
    }
}
