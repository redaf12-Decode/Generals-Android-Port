/*
**	Command & Conquer Generals Zero Hour(tm)
**	Copyright 2025 Electronic Arts Inc.
**
**	This program is free software: you can redistribute it and/or modify
**	it under the terms of the GNU General Public License as published by
**	the Free Software Foundation, either version 3 of the License, or
**	(at your option) any later version.
**
**	This program is distributed in the hope that it will be useful,
**	but WITHOUT ANY WARRANTY; without even the implied warranty of
**	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
**	GNU General Public License for more details.
**
**	You should have received a copy of the GNU General Public License
**	along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/


// GeneralsX @feature Android port 05/10/2026 The launcher's "Mouse and touchpad" switch (issue #39).
//
// Off by default: the game is played by touch, read natively by the touch layer
// (SDL3GameEngine.cpp, TouchInput.cpp), and there is no pointer. Turned on, it drops an empty file
// named mouse_mode into the app's own files directory (SetupActivity.MOUSE_MODE_MARKER), and the
// game has a pointer instead: a connected mouse drives it exactly as on a PC (SDL3Mouse feeds the
// message stream), and a finger moves it like a laptop touchpad (SDL3GameEngine.cpp,
// handleTouchpadEvent), with the cursor drawn by the game. Read the same way as GXLogging.h, once
// per process. Always FALSE off Android.
#pragma once

#include "Common/GXAndroidApp.h"

#if defined(__ANDROID__)
#include <cstdio>
#include <unistd.h>
#endif

// The cursor size the launcher's slider chose, in percent (50-200), kept as the marker's content.
// 100 when the marker is empty or unreadable.
inline int GXMouseCursorPercent()
{
#if defined(__ANDROID__)
	static int percent = -1;
	if (percent < 0)
	{
		percent = 100;
		char path[256];
		const int userId = (int)(getuid() / 100000);
		snprintf(path, sizeof(path), "/data/user/%d/" GX_ANDROID_APP_ID "/files/mouse_mode", userId);
		FILE *fp = fopen(path, "r");
		if (fp != nullptr)
		{
			int value = 0;
			if (fscanf(fp, "%d", &value) == 1 && value >= 50 && value <= 200)
				percent = value;
			fclose(fp);
		}
	}
	return percent;
#else
	return 100;
#endif
}

inline bool GXMouseModeEnabled()
{
#if defined(__ANDROID__)
	static int state = -1;
	if (state < 0)
	{
		char path[256];
		const int userId = (int)(getuid() / 100000);
		snprintf(path, sizeof(path), "/data/user/%d/" GX_ANDROID_APP_ID "/files/mouse_mode", userId);
		state = (access(path, F_OK) == 0) ? 1 : 0;
	}
	return state == 1;
#else
	return false;
#endif
}

// GeneralsX @feature Android port 07/11/2026 Whether a connected mouse should be *captured*: the
// hardware pointer confined to the window, Android's cursor hidden, relative motion only, the game
// drawing the cursor (SDL's requestPointerCapture path, the one Winlator uses). Read from the
// second line of the same mouse_mode marker -- "0" turns it off, anything else (including a marker
// written before this line existed) leaves it on, because a connected mouse should just work.
//
// Only meaningful while GXMouseModeEnabled() is on: with the pointer mode off the game is played by
// touch and there is no cursor to capture.
inline bool GXMouseCaptureEnabled()
{
#if defined(__ANDROID__)
	static int state = -1;
	if (state < 0)
	{
		state = 1;   // default on: a connected mouse works without any setup
		char path[256];
		const int userId = (int)(getuid() / 100000);
		snprintf(path, sizeof(path), "/data/user/%d/" GX_ANDROID_APP_ID "/files/mouse_mode", userId);
		FILE *fp = fopen(path, "r");
		if (fp != nullptr)
		{
			char line[64];
			if (fgets(line, sizeof(line), fp) != nullptr &&           // line 1: cursor percent
			    fgets(line, sizeof(line), fp) != nullptr)             // line 2: capture flag
			{
				int value = 1;
				if (sscanf(line, "%d", &value) == 1)
					state = (value != 0) ? 1 : 0;
			}
			fclose(fp);
		}
	}
	return state == 1;
#else
	return false;
#endif
}

