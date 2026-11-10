// GeneralsX @build Android port 10/11/2026
//
// A guard against the package migration and the legacy updater creeping back. The application id
// and the Java namespace are both com.ports.generalsx now; the old id (com.generalsx.zerohour)
// and the old update repository (MYSOREZ/GeneralsZH-Android-Port) must not appear as active code
// anywhere in the Android sources. These tests read the real files on disk, so a stray reference
// added later fails here rather than shipping.

package com.ports.generalsx;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class PackageIdentityTest {

    /** The Gradle build may run the tests from android/ or android/app/; find the android root. */
    private static File androidDir() {
        File[] candidates = { new File("."), new File(".."), new File("../..") };
        for (File candidate : candidates) {
            if (new File(candidate, "app/src/main").isDirectory()) {
                return candidate;
            }
        }
        throw new AssertionError(
            "could not locate the android/ directory from " + new File(".").getAbsolutePath());
    }

    private static File sourceRoot() {
        return new File(androidDir(), "app/src/main/java/com/ports/generalsx");
    }

    private static List<File> javaFiles(File root) {
        List<File> out = new ArrayList<>();
        try (Stream<java.nio.file.Path> walk = Files.walk(root.toPath())) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> out.add(p.toFile()));
        } catch (IOException e) {
            throw new AssertionError("could not walk " + root, e);
        }
        return out;
    }

    // ------------------------------------------------------------------ package identity

    @Test
    public void theJavaSourcesLiveUnderTheNewPackage() {
        File root = sourceRoot();
        assertTrue("no java sources under " + root, root.isDirectory());
        assertFalse(javaFiles(root).isEmpty());
        // The old directory must be gone entirely.
        assertFalse("the old package directory still exists",
            new File(androidDir(), "app/src/main/java/com/generalsx/zerohour").exists());
    }

    @Test
    public void buildGradleDeclaresTheNewNamespaceAndApplicationId() throws IOException {
        String gradle = new String(Files.readAllBytes(
            new File(androidDir(), "app/build.gradle").toPath()), StandardCharsets.UTF_8);
        assertTrue("namespace is not com.ports.generalsx", gradle.contains("namespace 'com.ports.generalsx'"));
        assertTrue("applicationId is not com.ports.generalsx", gradle.contains("applicationId 'com.ports.generalsx'"));
    }

    @Test
    public void everyJavaFileDeclaresTheNewPackage() throws IOException {
        for (File file : javaFiles(sourceRoot())) {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            assertTrue(file + " does not declare package com.ports.generalsx",
                text.contains("package com.ports.generalsx;"));
            assertFalse(file + " still declares the old package",
                text.contains("package com.generalsx.zerohour"));
        }
    }

    /** The FileProvider authority is ${applicationId}.fileprovider, and the code builds the same. */
    @Test
    public void theFileProviderAuthorityUsesTheApplicationId() throws IOException {
        String manifest = new String(Files.readAllBytes(
            new File(androidDir(), "app/src/main/AndroidManifest.xml").toPath()), StandardCharsets.UTF_8);
        assertTrue("the provider authority is not ${applicationId}.fileprovider",
            manifest.contains("android:authorities=\"${applicationId}.fileprovider\""));
        String installer = new String(Files.readAllBytes(
            new File(sourceRoot(), "ApkInstaller.java").toPath()), StandardCharsets.UTF_8);
        assertTrue("ApkInstaller does not build the authority from the package name",
            installer.contains("getPackageName() + \".fileprovider\""));
    }

    // ------------------------------------------------------------------ legacy references

    @Test
    public void noActiveReferenceToTheOldPackageOrUpdateRepository() {
        for (File file : javaFiles(sourceRoot())) {
            String text;
            try {
                text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new AssertionError("could not read " + file, e);
            }
            assertNoActiveReference(file, text);
        }
    }

    /**
     * A comment may explain what was removed (and names the old repository or package to do so),
     * but no string literal or code may point at it. This strips line comments before looking, so
     * the "MYSOREZ ... is gone" notes are allowed while a URL constant is not.
     */
    private static void assertNoActiveReference(File file, String text) {
        StringBuilder code = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                continue;
            }
            code.append(line).append('\n');
        }
        String stripped = code.toString();
        assertFalse(file + " still contains com.generalsx.zerohour in code",
            stripped.contains("com.generalsx.zerohour"));
        assertFalse(file + " still points at the MYSOREZ repository in code",
            stripped.contains("MYSOREZ"));
        assertFalse(file + " still points at the old GeneralsZH-Android-Port repository in code",
            stripped.contains("GeneralsZH-Android-Port"));
    }

    // ------------------------------------------------------------------ JNI bindings

    /**
     * The two JNI entry points the engine calls must carry the new package, matching
     * AndroidTextEditor.cpp's exported names.
     */
    @Test
    public void nativeMethodNamesCarryTheNewPackage() throws IOException {
        File editor = new File(androidDir(),
            "../Core/GameEngineDevice/Source/SDL3Device/GameClient/AndroidTextEditor.cpp");
        if (!editor.isFile()) {
            return; // built from a directory that does not include the engine; skip quietly
        }
        String text = new String(Files.readAllBytes(editor.toPath()), StandardCharsets.UTF_8);
        assertTrue(text.contains("Java_com_ports_generalsx_GeneralsZHActivity_nativeTextEditorChanged"));
        assertTrue(text.contains("Java_com_ports_generalsx_GeneralsZHActivity_nativeTextEditorDone"));
        assertFalse(text.contains("Java_com_generalsx_zerohour"));
    }
}
