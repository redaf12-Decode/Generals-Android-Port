#!/usr/bin/env python3
"""Stage the Reforged tests' isolated SDL Java layer (v1.15.2).

WHY: the C3 test payload (libmainrfx.so) carries SDL 3.4.16 native,
statically linked. On device, C0 measured that running it under the
host shell's SDL Java glue (3.4.2, staged by package-android-zh.sh
for the game) is a SILENT NATIVE CRASH -- the two must never pair.
The C2 breakthrough ran the payload under upstream SDL 3.4.16's own
Java glue. One APK cannot hold two classes named
org.libsdl.app.SDLActivity, so the tests get the 3.4.16 glue under a
renamed package, org.libsdl.apprfx, and the payload's JNI layer is
patched to match (probe/patches/reforged-rfx-jni-prefix.patch:
SDL_JAVA_PREFIX + the five register_methods class names). The game's
own org.libsdl.app layer (3.4.2) is untouched; the two layers share
nothing but the process list.

Reads the glue from the PINNED upstream checkout's vendored SDL --
the exact tree the payload was just built from, so glue and native
always match -- and writes it, package-renamed, into the app tree.

Env:
  GX_GLUE_SRC  dir holding the upstream org/libsdl/app/*.java files
  GX_APP_JAVA  the app's src/main/java dir (target root)
"""
import os
import re
import sys

GLUE_FILES = [
    'HIDDevice.java',
    'HIDDeviceBLESteamController.java',
    'HIDDeviceManager.java',
    'HIDDeviceUSB.java',
    'SDL.java',
    'SDLActivity.java',
    'SDLAudioManager.java',
    'SDLControllerManager.java',
    'SDLDummyEdit.java',
    'SDLInputConnection.java',
    'SDLSensorManager.java',
    'SDLSurface.java',
]

SRC = os.environ.get('GX_GLUE_SRC')
DST_ROOT = os.environ.get('GX_APP_JAVA')
if not SRC or not DST_ROOT:
    sys.exit('stage-rfx-glue: GX_GLUE_SRC and GX_APP_JAVA are required')
DST = os.path.join(DST_ROOT, 'org', 'libsdl', 'apprfx')
if os.path.exists(DST):
    sys.exit(f'stage-rfx-glue: {DST} already exists -- refusing to overwrite')
os.makedirs(DST)

for name in GLUE_FILES:
    src_path = os.path.join(SRC, name)
    if not os.path.isfile(src_path):
        sys.exit(f'stage-rfx-glue: missing glue source {src_path}')
    text = open(src_path, encoding='utf-8').read()
    if text.count('package org.libsdl.app;') != 1:
        sys.exit(f'stage-rfx-glue: {name}: package line not unique/found')
    text = text.replace('package org.libsdl.app;',
                        'package org.libsdl.apprfx;', 1)
    text = text.replace('"org.libsdl.app.USB_PERMISSION"',
                        '"org.libsdl.apprfx.USB_PERMISSION"')
    if re.search(r'org\.libsdl\.app(?!rfx)', text):
        sys.exit(f'stage-rfx-glue: {name}: org.libsdl.app reference remains')
    open(os.path.join(DST, name), 'w', encoding='utf-8').write(text)
    print('staged', name)

# v1.18 (Stage C4): the game payload needs the gesture-patched
# SDLActivity (the host shell's edge-gesture fixes) rather than the
# vendored vanilla one. The override file is upstream 3.4.16's
# SDLActivity.java with the gesture patch applied, kept in the
# overlay; it receives exactly the same package rename as the rest
# of the glue and replaces the staged vanilla file.
OVERRIDE = os.environ.get('GX_GLUE_SDL_ACTIVITY_OVERRIDE')
if OVERRIDE:
    if not os.path.isfile(OVERRIDE):
        sys.exit(f'stage-rfx-glue: override not found: {OVERRIDE}')
    text = open(OVERRIDE, encoding='utf-8').read()
    if text.count('package org.libsdl.app;') != 1:
        sys.exit('stage-rfx-glue: override: package line not unique/found')
    text = text.replace('package org.libsdl.app;',
                        'package org.libsdl.apprfx;', 1)
    text = text.replace('"org.libsdl.app.USB_PERMISSION"',
                        '"org.libsdl.apprfx.USB_PERMISSION"')
    if re.search(r'org\.libsdl\.app(?!rfx)', text):
        sys.exit('stage-rfx-glue: override: org.libsdl.app reference remains')
    open(os.path.join(DST, 'SDLActivity.java'), 'w',
         encoding='utf-8').write(text)
    print('staged SDLActivity.java OVERRIDE (gesture-patched):', OVERRIDE)

print('RFX GLUE STAGED:', len(GLUE_FILES), 'files ->',
      os.path.join('org', 'libsdl', 'apprfx'))
