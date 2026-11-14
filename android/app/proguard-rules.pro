# GeneralsX Zero Hour (Android) -- R8 / ProGuard rules for the release variant.
#
# GeneralsX @build Android R8 protection 14/11/2026
#
# The launcher is a thin Java shell around a C++ engine that is built OUTSIDE
# Gradle (see android/settings.gradle). So the surface that must survive
# obfuscation is exactly the Java/C++ boundary plus the Android entry points --
# everything else is free to be renamed, inlined and shrunk.
#
# Read with the two files this one is appended to:
#   * $AGP/proguard-android-optimize.txt (via getDefaultProguardFile in
#     android/app/build.gradle) -- the standard Android rules, including the
#     global `native <methods>` name keep, the View getter/setter keep, the
#     Activity onClick(View) keep, the enum values()/valueOf() keep and the
#     Parcelable CREATOR keep.
#   * org/libsdl/app/*.java rules below, which are SDL3's own and are the
#     authority for the SDL<->native contract.
#
# Rules that are deliberately NOT here, because nothing in this repository needs
# them: Gson/Moshi/Jackson, Retrofit/OkHttp converters, Room, Dagger/Hilt,
# kotlinx.serialization, custom ClassLoaders or plugin loaders. None of those
# libraries is a dependency (android/app/build.gradle has Material, androidx.core
# and the JUnit/Robolectric test deps only), so a rule for them would be dead
# weight that silently widens the keep set. Add one only if such a dependency is
# actually introduced.

# ---------------------------------------------------------------------------
# 1. Attributes needed to read a stack trace back through mapping.txt
# ---------------------------------------------------------------------------
# LineNumberTable is what turns "at com.generalsx.zerohour.a.b(SourceFile)" into
# a real line number once mapping.txt is applied; SourceFile names the file. The
# annotation attributes are already kept by AGP's proguard-common.txt.
-keepattributes SourceFile,LineNumberTable

# The generated name for the file attribute; the mapping file records the
# original, so nothing is lost.
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# 2. Android entry points declared in AndroidManifest.xml
# ---------------------------------------------------------------------------
# AGP already derives keep rules from the manifest (aapt_rules.txt), so these
# classes would survive anyway. They are repeated here because they are the
# contract with the OS -- a future AGP that stops emitting one of them would
# otherwise break the launcher in a way that only shows up on a device -- and
# because a reader should be able to see the complete entry-point set in one
# place. `allowoptimization` still lets R8 optimize and inline the bodies; only
# the names are pinned.
-keep,allowoptimization class com.generalsx.zerohour.GeneralsZHActivity { <init>(...); }
-keep,allowoptimization class com.generalsx.zerohour.SetupActivity { <init>(...); }
-keep,allowoptimization class com.generalsx.zerohour.FolderPickerActivity { <init>(...); }
-keep,allowoptimization class com.generalsx.zerohour.TouchControlsActivity { <init>(...); }
-keep,allowoptimization class com.generalsx.zerohour.LogViewerActivity { <init>(...); }
-keep,allowoptimization class com.generalsx.zerohour.ModManagerActivity { <init>(...); }
-keep,allowoptimization class com.generalsx.zerohour.ReplayCheckActivity { <init>(...); }
-keep,allowoptimization class com.generalsx.zerohour.MapPackActivity { <init>(...); }
-keep,allowoptimization class com.generalsx.zerohour.ControlBarPackActivity { <init>(...); }
-keep,allowoptimization class com.generalsx.zerohour.NetworkDiagnosticsActivity { <init>(...); }
-keep,allowoptimization class com.generalsx.zerohour.GeneralsOnlineActivity { <init>(...); }

# The androidx.core.content.FileProvider in the manifest is kept by
# androidx.core's own consumer rules; no rule needed here.
# There is no <service>, <receiver> or <provider> of this project's own, so the
# activity list above is the complete OS-facing entry-point set.

# ---------------------------------------------------------------------------
# 3. JNI -- SDL3 Java glue (org.libsdl.app)
# ---------------------------------------------------------------------------
# SDL3's native half (SDL_android.c) binds these classes at JNI_OnLoad with
#   register_methods(env, "org/libsdl/app/SDLActivity", ...)
# i.e. FindClass() on the LITERAL class name, then RegisterNatives() with a
# table of LITERAL method names and signatures. A renamed class or method makes
# that lookup fail ("Failed to register methods of ...") and the app dies at
# startup with no Java-level symptom. The same native file also resolves
# non-native helpers by name (GetStaticMethodID), so those are listed too.
#
# These four blocks are SDL3 3.4.2's own android-project/app/proguard-rules.pro,
# reproduced verbatim so the contract is visible in this repository. They are
# what SDL upstream tests against; re-check them after an SDL3 upgrade
# (cmake/sdl3.cmake).
-keep,includedescriptorclasses,allowoptimization class org.libsdl.app.SDLActivity {
    java.lang.String nativeGetHint(java.lang.String); # Java-side doesn't use this, so it gets minified, but C-side still tries to register it
    java.lang.String clipboardGetText();
    boolean clipboardHasText();
    void clipboardSetText(java.lang.String);
    int createCustomCursor(int[], int, int, int, int);
    void destroyCustomCursor(int);
    android.app.Activity getContext();
    boolean getManifestEnvironmentVariables();
    android.view.Surface getNativeSurface();
    void initTouch();
    boolean isAndroidTV();
    boolean isChromebook();
    boolean isDeXMode();
    boolean isTablet();
    void manualBackButton();
    int messageboxShowMessageBox(int, java.lang.String, java.lang.String, int[], int[], java.lang.String[], int[]);
    void minimizeWindow();
    boolean openURL(java.lang.String);
    void requestPermission(java.lang.String, int);
    boolean showToast(java.lang.String, int, int, int, int);
    boolean sendMessage(int, int);
    boolean setActivityTitle(java.lang.String);
    boolean setCustomCursor(int);
    void setOrientation(int, int, boolean, java.lang.String);
    boolean setRelativeMouseEnabled(boolean);
    boolean setSystemCursor(int);
    void setWindowStyle(boolean);
    boolean shouldMinimizeOnFocusLoss();
    boolean showTextInput(int, int, int, int, int);
    boolean supportsRelativeMouse();
    int openFileDescriptor(java.lang.String, java.lang.String);
    boolean showFileDialog(java.lang.String[], boolean, boolean, int);
    java.lang.String getPreferredLocales();
    java.lang.String formatLocale(java.util.Locale);
}

-keep,includedescriptorclasses,allowoptimization class org.libsdl.app.HIDDeviceManager {
    void closeDevice(int);
    boolean initialize(boolean, boolean);
    boolean openDevice(int);
    boolean readReport(int, byte[], boolean);
    int writeReport(int, byte[], boolean);
}

-keep,includedescriptorclasses,allowoptimization class org.libsdl.app.SDLAudioManager {
    void registerAudioDeviceCallback();
    void unregisterAudioDeviceCallback();
    void audioSetThreadPriority(boolean, int);
}

-keep,includedescriptorclasses,allowoptimization class org.libsdl.app.SDLControllerManager {
    void pollInputDevices();
    void joystickSetLED(int, int, int, int);
    void pollHapticDevices();
    void hapticRun(int, float, int);
    void hapticRumble(int, float, float, int);
    void hapticStop(int);
}

# SDLInputConnection is also FindClass'd by literal name and has its own
# RegisterNatives table (nativeCommitText / nativeGenerateScancodeForUnichar).
# SDL upstream relies on the global `native <methods>` rule for the member names
# but does not pin the class name, so it is pinned explicitly here.
-keep,allowoptimization class org.libsdl.app.SDLInputConnection { *; }

# SDLDummyEdit is instantiated directly by SDLActivity; the global
# `native <methods>` rule keeps the native names of the classes above. Nothing
# else in org.libsdl.app is referenced from native code by name, so the rest of
# the glue (HIDDeviceUSB, HIDDeviceBLESteamController, ...) is left obfuscatable.

# ---------------------------------------------------------------------------
# 4. JNI -- the game engine (com.generalsx.zerohour.GeneralsZHActivity)
# ---------------------------------------------------------------------------
# The engine registers its JNI methods with the STATIC naming convention, not
# RegisterNatives. Core/GameEngineDevice/Source/SDL3Device/GameClient/AndroidTextEditor.cpp
# exports
#   Java_com_generalsx_zerohour_GeneralsZHActivity_nativeTextEditorChanged
#   Java_com_generalsx_zerohour_GeneralsZHActivity_nativeTextEditorDone
# The symbol is built from the class name and the method name at build time, so
# both must be byte-for-byte identical after obfuscation. The class name is
# already pinned in section 2 (it is the game Activity); pin the two methods.
-keepclassmembers class com.generalsx.zerohour.GeneralsZHActivity {
    native <methods>;
}

# The engine also looks up three ordinary methods by name, on the running
# Activity, with GetMethodID():
#   AndroidTextEditor.cpp: "showTextEditor" "(Ljava/lang/String;III)V"
#                          "hideTextEditor" "()V"
#   Core/GameEngine/Source/GameNetwork/udp.cpp: "setLanMulticastLock" "(Z)V"
# The class is obtained with GetObjectClass(activity), which yields
# GeneralsZHActivity (the concrete runtime class), so the methods must keep
# their names on that class.
-keepclassmembers class com.generalsx.zerohour.GeneralsZHActivity {
    public void showTextEditor(java.lang.String, int, int, int);
    public void hideTextEditor();
    public void setLanMulticastLock(boolean);
}

# ---------------------------------------------------------------------------
# 5. Reflection
# ---------------------------------------------------------------------------
# There is no reflective class loading, no getDeclaredMethod/getField on
# application classes, and no ServiceLoader or plugin loader anywhere in the
# launcher. The one reflective surface is resource lookup by name:
#
#   ThemeHelper.resolve()/swatchColor() build a colour name at runtime
#   ("gzh_navy_" + role, "gzh_accent_" + accent + "_" + role, ...) and call
#   Resources.getIdentifier(..., "color", packageName).
#
# Resource shrinking cannot see those names, so the colours it must not delete
# are listed in res/raw/keep.xml (tools:keep="@color/gzh_*"). That file is the
# reflection-compatibility mechanism for this project; there is no ProGuard rule
# for it.
#
# Everything reached reflectively is therefore handled; no blanket keep is
# needed and none is added.

# ---------------------------------------------------------------------------
# 6. Serialization
# ---------------------------------------------------------------------------
# Nothing in the launcher uses Serializable, Parcelable or a reflection-based
# JSON mapper. JSON is written and read field-by-field with org.json.JSONObject
# (TouchControlConfig, DataPackInstaller, GeneralsOnlineSession, ...), so the
# names live in string literals, not in the class layout, and renaming the
# classes that produce them changes nothing on disk. The AGP default rules
# already keep enum values()/valueOf() and Parcelable CREATOR if either is ever
# introduced.

# ---------------------------------------------------------------------------
# 7. Kotlin
# ---------------------------------------------------------------------------
# The launcher is pure Java and has no Kotlin dependency, so no kotlin.Metadata
# or kotlin-reflect rules are needed. `kotlin.Metadata` is not in
# -keepattributes here on purpose: adding it would be a rule for a language this
# module does not compile.
