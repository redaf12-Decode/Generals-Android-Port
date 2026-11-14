# Android code protection (R8 / ProGuard)

How the Android launcher's Java is obfuscated, shrunk and optimized for the release build, what
must survive that, and how to read a stack trace back through the mapping file.

This documents the configuration that is actually in the tree. Where a guarantee is not yet in
place, the text says so rather than describing an intention.

---

## 1. Scope and an important caveat

R8 protects **Java/Kotlin bytecode**. In this project that is the launcher shell
(`android/app/src/main/java/com/generalsx/zerohour/`) and the SDL3 Java glue
(`android/app/src/main/java-sdl/org/libsdl/app/`). It does **not** protect:

* the C++ engine (`GeneralsMD/`, `Generals/`, `Core/`), which is compiled by CMake outside
  Gradle -- R8 never sees it;
* native `.so` files, which R8 does not touch at all;
* game assets (`.big` archives, `.wnd` layout files), which are data, not code.

R8 makes static analysis of the launcher harder. It cannot prevent someone from observing and
reproducing visible application behavior, and it is not encryption.

### The shipped APK is currently the debug variant

`scripts/build/android/package-android-zh.sh` runs `gradle assembleDebug`, and
`build-local-sandboxed.sh` and `.github/workflows/build-android.yml` both copy
`app-debug.apk`. The APK in `apk/` is therefore **debuggable and unminified**.

The R8 configuration in this repository applies to the **release** variant. It is complete,
verified by CI against the release artifact, and takes effect the moment the packaging pipeline
switches to `assembleRelease`. That switch is tracked as ROADMAP **P4.2** and is deliberately not
made here: it also removes the `debuggable` flag and so needs a device pass over every JNI entry
point (ROADMAP P4.1/P3.2), which is not something this change can assert. See §10.

---

## 2. Protection architecture

```
android/app/build.gradle            release { minifyEnabled, shrinkResources, proguardFiles }
android/app/proguard-rules.pro      the keep rules (JNI, reflection, entry points)
android/app/src/main/res/raw/keep.xml   resource-shrinker guard for names resolved at runtime
scripts/qa/android/check-r8-protection.py   post-build verification of the release artifact
.github/workflows/android-unit-tests.yml    builds the release variant and runs the checks
```

The chain runs in one direction: Gradle turns on R8 for `release`; R8 reads
`proguard-android-optimize.txt` (AGP default) plus `proguard-rules.pro` (this project); the
resource shrinker reads `keep.xml`; and `check-r8-protection.py` then asserts, from the produced
APK and `mapping.txt`, that all of it did what it claims.

---

## 3. R8 configuration

`android/app/build.gradle`, `buildTypes.release`:

```groovy
minifyEnabled true
shrinkResources true
proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
```

* **`minifyEnabled true`** enables R8: obfuscation, tree shaking and optimization. This is the
  switch that does the work.
* **`shrinkResources true`** enables resource shrinking. It is only honored together with
  `minifyEnabled`; the shrinker works from R8's record of which resources the code reaches.
* **`proguard-android-optimize.txt`** is the AGP-bundled default *with* the optimization passes
  enabled. It already keeps, among other things, all `native <methods>` names
  (`-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }`), View
  setters/getters, `Activity` `onClick(View)`, enum `values()`/`valueOf()` and `Parcelable`
  `CREATOR`. `proguard-rules.pro` is appended to it.
* **debug is unchanged**: `minifyEnabled false`. A sideload debug build keeps readable stack
  traces and the local dev loop is unaffected.
* **`launcherOnly`** (the CI variant that strips native libraries) also stays unminified on
  purpose: its job is to exercise the same classes the real APK ships.

Signing is untouched: `release` uses the same committed `signingConfigs.debug`, so a release
build still installs as an update over an existing one.

Gradle 8.9 and AGP 8.5.2 were not changed; R8 is the version AGP 8.5.2 bundles (8.5.35).

---

## 4. ProGuard rules

`android/app/proguard-rules.pro`. Every rule exists because something outside R8's view names a
class or member as a **string**. The rule set is deliberately narrow: the launcher is a thin Java
shell around a C++ engine, so the surface that must survive is exactly the Android entry points
plus the Java/C++ boundary. Everything else is free to be renamed, inlined and shrunk -- and is.

### 4.1 Stack-trace attributes

```proguard
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
```

Without `LineNumberTable` a deobfuscated trace has no line numbers. `SourceFile` names the file
in the trace; the mapping file records the original name, so obfuscating the attribute loses
nothing.

### 4.2 Android entry points

Each Activity named in `AndroidManifest.xml` is pinned by name with `allowoptimization`, so R8 may
still optimize and inline the bodies:

```proguard
-keep,allowoptimization class com.generalsx.zerohour.SetupActivity { <init>(...); }
```

AGP already derives equivalent rules from the manifest (`aapt_rules.txt`), so these are belt and
braces: they keep the entry-point set visible in one file and survive an AGP change that stopped
emitting one of them. There is no `<service>`, `<receiver>` or `<provider>` of this project's own
(the one provider is androidx's `FileProvider`, covered by androidx's consumer rules).

### 4.3 JNI -- SDL3 Java glue

SDL3's native half (`SDL_android.c`) binds its Java side by **literal name**:
`register_methods(env, "org/libsdl/app/SDLActivity", ...)` calls `FindClass()` on that string and
then `RegisterNatives()` with a table of literal method names and signatures. A renamed class or
method makes the lookup fail ("Failed to register methods of ...") and the app dies at startup
with no Java-level symptom.

The four `-keep` blocks for `SDLActivity`, `HIDDeviceManager`, `SDLAudioManager` and
`SDLControllerManager` are **SDL3 3.4.2's own** `android-project/app/proguard-rules.pro`,
reproduced verbatim, and are the authority for that contract. Re-check them after an SDL3 upgrade
(`cmake/sdl3.cmake`).

One class SDL upstream does *not* pin is added here, because it is `FindClass`'d by literal name
too (`SDL_android.c:582`) and carries its own `RegisterNatives` table
(`nativeCommitText`, `nativeGenerateScancodeForUnichar`):

```proguard
-keep,allowoptimization class org.libsdl.app.SDLInputConnection { *; }
```

The rest of `org.libsdl.app` (for example `HIDDeviceUSB`, `HIDDeviceBLESteamController`) is not
named from native code and is left obfuscatable.

### 4.4 JNI -- the game engine

The engine registers its JNI methods with the **static naming convention**, not `RegisterNatives`.
`Core/GameEngineDevice/Source/SDL3Device/GameClient/AndroidTextEditor.cpp` exports
`Java_com_generalsx_zerohour_GeneralsZHActivity_nativeTextEditorChanged` and
`..._nativeTextEditorDone`. The symbol is built from the class and method names at build time, so
both must be byte-for-byte identical after obfuscation. The class is already pinned in §4.2; the
two native methods are covered by AGP's global `native <methods>` keep.

The engine also looks three ordinary methods up by name on the running Activity with
`GetMethodID()`:

| Method | Signature | Caller |
|---|---|---|
| `showTextEditor` | `(Ljava/lang/String;III)V` | `AndroidTextEditor.cpp:188` |
| `hideTextEditor` | `()V` | `AndroidTextEditor.cpp:201` |
| `setLanMulticastLock` | `(Z)V` | `Core/GameEngine/Source/GameNetwork/udp.cpp:578` |

```proguard
-keepclassmembers class com.generalsx.zerohour.GeneralsZHActivity {
    public void showTextEditor(java.lang.String, int, int, int);
    public void hideTextEditor();
    public void setLanMulticastLock(boolean);
}
```

These are not `native`, so the global rule does not cover them.

---

## 5. Reflection compatibility

There is no reflective class loading, no `getDeclaredMethod`/`getField` on application classes, and
no `ServiceLoader` or plugin loader in the launcher. The one reflective surface is **resource
lookup by name**:

`ThemeHelper.resolve()` / `swatchColor()` build a color resource name at runtime
(`"gzh_navy_" + role`, `"gzh_accent_" + accent + "_" + role`, `"gzh_day_accent_" + accent + "_primary"`,
...) and call `Resources.getIdentifier(..., "color", packageName)`.

The resource shrinker cannot see those names, so it would delete colors that look unused. Because
`getIdentifier()` returns `0` on a miss and `ThemeHelper` then falls through to the base palette,
the failure is a silently wrong color, not a crash -- which is exactly why it is guarded rather
than left to chance. The guard is `android/app/src/main/res/raw/keep.xml`:

```xml
<resources xmlns:tools="http://schemas.android.com/tools"
    tools:keep="@color/gzh_*" />
```

Every name this lookup can produce starts with `gzh_`, so the prefix covers the base palette, the
navy and day palettes and the accent colors. Colors reached through `R.color.*` need no entry --
the shrinker sees those references itself.

---

## 6. Serialization compatibility

Nothing in the launcher uses `Serializable`, `Parcelable` or a reflection-based JSON mapper. JSON
is read and written field-by-field with `org.json.JSONObject` (`TouchControlConfig`,
`DataPackInstaller`, `GeneralsOnlineSession`, ...), so the field names live in string literals
rather than in the class layout: renaming the classes that produce them changes nothing on disk or
over the wire. No rule is needed. AGP's defaults already keep enum `values()`/`valueOf()` and
`Parcelable` `CREATOR` if either is ever introduced.

No library-specific rules are present for Gson, Moshi, Jackson, Retrofit, Room, Dagger/Hilt or
kotlinx.serialization, because none of those is a dependency (`android/app/build.gradle` has
Material, androidx.core and the JUnit/Robolectric test dependencies only). A rule for an absent
library would be dead weight that silently widened the keep set.

---

## 7. Kotlin

The launcher is pure Java and has no Kotlin dependency, so no `kotlin.Metadata` or `kotlin-reflect`
rules are needed. `kotlin.Metadata` is deliberately not in `-keepattributes`.

---

## 8. Resource shrinking

`shrinkResources true` removes resources R8 proves unreachable. In this project that is mostly
unused Material and androidx resources: the release APK carries 5,367 resources against the
unshrunk debug build's 6,230.

What must survive, and how it is assured:

* **Colors resolved by name** -- `keep.xml` (§5).
* **Icons, themes, layouts, menus, fonts, strings** -- reached through `R.*` or from the manifest,
  so the shrinker sees them. The launcher icon (`ic_launcher_foreground` / `_background` /
  `_monochrome`) and the touch-editor background are asserted present by
  `check-r8-protection.py`.
* **`assets/`** (the `gamedata` tree extracted on first launch, and the bundled driver) -- assets
  are **not** touched by resource shrinking at all; they are copied verbatim. `keep.xml` is not
  involved.

Resource shrinking does **not** encrypt assets or resources. Anything left in the APK can be
extracted and read; this is a size and tidiness measure, not a confidentiality one.

---

## 9. Mapping file handling

R8 writes `android/app/build/outputs/mapping/release/mapping.txt` (plus `seeds.txt`, `usage.txt`
and `configuration.txt`). The mapping is what turns an obfuscated stack trace back into a readable
one, so it is kept -- but it is also a complete map of the app's internals, so it does not travel
with the APK.

* It is uploaded by `.github/workflows/android-unit-tests.yml` as its **own** artifact
  (`r8-mapping-release`), never inside the APK/AAB artifact.
* It is never a release asset and never committed. `android/app/build/` is gitignored.
* Retention is 30 days, shorter than the APK artifact's 14 days only in the sense that both are
  bounded; the point is that it does not live forever.
* The repository is private, so an Actions artifact is readable only by people who already have
  repository access. That is the access restriction; GitHub offers no finer per-artifact control.
  If the repository ever becomes public, this upload must move to a private store, because a
  public artifact URL would then expose the mapping.

---

## 10. GitHub Actions integration

The R8 checks run in the existing **Android Unit Tests** workflow
(`.github/workflows/android-unit-tests.yml`), which already builds the launcher's Java with no NDK.
No new workflow was added, and the existing triggers (`workflow_dispatch` and `pull_request` to
`main` / `New-app`) are unchanged.

The added steps:

1. **Build R8-protected release APK and AAB** -- `gradle :app:assembleRelease :app:bundleRelease`.
   AGP fails this task on any R8 error, a missing class, a duplicate class or a resource-shrinking
   error, so a successful exit is itself the check for all four. A green debug build would say
   nothing: debug is deliberately unminified.
2. **Verify R8 protection** -- `scripts/qa/android/check-r8-protection.py` (below).
3. **Upload release APK and AAB** (`launcher-release-r8`) and the **mapping** (`r8-mapping-release`).

`check-r8-protection.py` asserts, from the produced artifacts:

| Check | Why |
|---|---|
| `mapping.txt` exists and is non-empty | R8 really ran |
| at least 20 launcher classes renamed | a keep rule did not widen to a whole package |
| no unexpected launcher class kept | the pinned set is exactly the entry points |
| every obfuscated name in `mapping.txt` appears in the APK dex | the mapping belongs to *this* APK (a stale mapping would otherwise pass everything) |
| pinned classes and all JNI method names present verbatim | the native contract survived |
| runtime-resolved colors and required resources present in `resources.arsc` | the shrinker did not delete something needed |
| signing certificate unchanged | updates still install over existing versions |
| `applicationId`, launcher activity, `versionCode` unchanged | identity preserved |

Each failure prints a GitHub Actions `::error::` annotation. The checks were exercised in both
directions: they pass on the real release APK and fail on the unminified `launcherOnly` APK, on a
missing mapping, and on a deliberately raised threshold.

### What CI does not cover

The workflow has no NDK and no device, so it cannot load the native libraries or run the app. The
JNI contract is verified **statically** (the names native code looks up are present in the dex),
which catches the realistic R8 failure -- a renamed class or method -- but is not a runtime test.
Device validation of the obfuscated build (launch, text editor, LAN lock, SDL activity, game
start) is a manual step and is listed in §10 of the ROADMAP as a prerequisite for switching the
pipeline to `assembleRelease`.

---

## 11. Release verification

To reproduce locally:

```bash
cd android
gradle :app:assembleRelease :app:bundleRelease --no-daemon

APKSIGNER=$ANDROID_HOME/build-tools/35.0.0/apksigner \
AAPT2=$ANDROID_HOME/build-tools/35.0.0/aapt2 \
  python3 ../scripts/qa/android/check-r8-protection.py
```

Measured on the current tree (release variant, launcher Java only, no native libraries staged):

| Property | Value |
|---|---|
| Launcher classes obfuscated | 140 |
| Launcher classes kept (entry points) | 11 |
| Launcher members removed by R8 | ~1,550 |
| Resources in release APK | 5,367 (debug: 6,230) |
| R8 version | 8.5.35 (bundled with AGP 8.5.2) |
| `applicationId` | `com.generalsx.zerohour` (unchanged) |
| Launcher activity | `com.generalsx.zerohour.SetupActivity` (unchanged) |
| Signing certificate SHA-256 | `644a3b0f...f77c48e` (the committed debug key, unchanged) |

Inspect the result by hand with Android Studio's APK Analyzer or `aapt2 dump` / `dexdump`. A quick
spot check that a class was renamed:

```bash
grep '^com.generalsx.zerohour.ThemeHelper ->' android/app/build/outputs/mapping/release/mapping.txt
# com.generalsx.zerohour.ThemeHelper -> f0.j1:
```

### Known limitations

* Only the release variant is protected; the shipped APK is still the debug variant (§1).
* The C++ engine and native libraries are not protected by R8.
* Assets are not encrypted or obfuscated.
* Static analysis of the launcher is harder, not impossible: the mapping is retrievable by anyone
  with access to a build, and behavior can be observed by running the app.
* There is no runtime integrity check, root detection or anti-tamper logic, and none is claimed.
* **R8 does not protect string constants.** A key, URL or token compiled into the launcher stays
  readable in the dex no matter how thoroughly the classes around it are renamed, so R8 is not a
  substitute for secret management. See §11.1.

### 11.1 Sensitive information

The audit behind this configuration, and the reason no secret is embedded in the APK:

* **Private signing keys are not in the repository.** Update signing uses the
  `UPDATE_SIGNING_KEY` GitHub Actions secret, written to `$RUNNER_TEMP` for the duration of a job
  (`.github/workflows/sign-update.yml`) and never committed. R8 does not change this.
* **The one embedded key is public by design.** `UpdateManager.PUBLIC_KEY_B64` is the
  SubjectPublicKeyInfo of the update-verification key. It is a public key used to *check* a
  signature; it is not a secret and is safe in the APK.
* **`android/app/debug.keystore` is tracked on purpose.** It is a fixed, well-known debug key
  (password `android`) that exists so every CI build is installable as an update over the last
  one. It signs sideload builds only and must never be used for a store listing. It is not a
  production key and is left exactly as it was.
* **No other credentials were found** in the launcher Java, resources or assets. `GeneralsOnlineRelease/cacert.pem`
  is a public CA trust bundle, not a secret.

Do not put a real credential in the launcher on the assumption that R8 will hide it; it will not.

---

## 12. Restoring readable stack traces

An obfuscated crash shows names like `f0.j1` with a source file of `SourceFile`. To read it:

1. Obtain the `mapping.txt` from the exact build that produced the crash. The mapping is
   build-specific; a mapping from a different build will produce wrong names. Take it from the
   `r8-mapping-release` artifact of the CI run whose APK the user has, or rebuild that commit.
2. Retrace. Either use the standalone tool:

   ```bash
   retrace mapping.txt obfuscated-stack-trace.txt
   ```

   or paste both into Android Studio's **Build > Analyze Stack Trace** / the *R8 retrace* tool
   (`<sdk>/tools/retrace`).

The retraced trace shows the original class, method and line number, because
`-keepattributes SourceFile,LineNumberTable` preserved the line table.

---

## 13. Changing the rules

Add a keep rule only when something outside R8's view names a class or member as a string -- a
JNI lookup, a manifest reference, a reflective lookup. Every rule costs obfuscation coverage, so
prefer `-keepclassmembers` (keep the member, let the class be renamed) over `-keep class ... { *; }`,
and prefer pinning a specific member over a wildcard.

Do **not** add:

* `-keep class com.** { *; }` or `-keep class * { *; }` -- these defeat the point;
* rules for libraries that are not dependencies (see §6).

After any change, run `gradle :app:assembleRelease` and `check-r8-protection.py`, and update the
measured table in §11 if the numbers move.
