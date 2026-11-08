/*
**      Command & Conquer Generals Zero Hour(tm)
**      Copyright 2025 Electronic Arts Inc.
**
**      This program is free software: you can redistribute it and/or modify
**      it under the terms of the GNU General Public License as published by
**      the Free Software Foundation, either version 3 of the License, or
**      (at your option) any later version.
**
**      This program is distributed in the hope that it will be useful,
**      but WITHOUT ANY WARRANTY; without even the implied warranty of
**      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
**      GNU General Public License for more details.
**
**      You should have received a copy of the GNU General Public License
**      along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

// GeneralsX @build Android port 08/11/2026 The launcher's application id, in ONE place.
//
// Several native files reach the launcher's private files directory by building the path from
// the uid (getuid()/100000) rather than through an Android storage API, because they run before
// SDL_main has started (AndroidCrashHandler.cpp, GXLogging.h) or are plain inline helpers with no
// Context (GXSubtitles.h, GXControlScheme.h, GXMouseMode.h, GXManagedMods.h, GXRemoteConfig.h).
// The package segment of that path is the applicationId in android/app/build.gradle, so a change
// there silently breaks all of them unless it is changed here too -- keep the two in step.
#pragma once

#define GX_ANDROID_APP_ID "com.ports.generalsx"
