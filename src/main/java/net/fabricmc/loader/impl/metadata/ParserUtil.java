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

package net.fabricmc.loader.impl.metadata;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

import net.fabricmc.loader.impl.lib.gson.JsonReader;
import net.fabricmc.loader.impl.lib.gson.JsonToken;

final class ParserUtil {
	public static boolean readBoolean(JsonReader reader, String key) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.BOOLEAN) {
			throw new ParseMetadataException(key+" must be a boolean", reader);
		}

		return reader.nextBoolean();
	}

	public static String readString(JsonReader reader, String key) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.STRING) {
			throw new ParseMetadataException(key+" must be a string", reader);
		}

		return reader.nextString();
	}

	public static <T extends Enum<T>> T readEnum(JsonReader reader, Class<T> enumCls, String key) throws IOException, ParseMetadataException {
		String value = readString(reader, key);

		try {
			return Enum.valueOf(enumCls, value.toUpperCase(Locale.ENGLISH));
		} catch (IllegalArgumentException e) {
			String options = Arrays.stream(enumCls.getEnumConstants()).map(v -> v.name().toLowerCase(Locale.ENGLISH)).collect(Collectors.joining(", "));
			throw new ParseMetadataException(key+" "+value+" must be one of "+options, reader);
		}
	}
}
