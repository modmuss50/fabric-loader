/*
 * Copyright 2016 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.loader.impl.gui.theme;

import java.util.Locale;

public class ThemeProvider {
	// Returns either "light" or "dark"
	public static String getTheme() {
		try {
			String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);

			if (os.contains("win")) {
				return new WindowsThemeProvider().getTheme();
			} else if (os.contains("mac")) {
				return new MacOSThemeProvider().getTheme();
			}
		} catch (Throwable t) {
			// Ignored
		}

		return "light";
	}
}
