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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.extension.ModMetadataBuilder;
import net.fabricmc.loader.api.extension.ModMetadataBuilder.ModDependencyBuilder;
import net.fabricmc.loader.api.metadata.ContactInformation;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import net.fabricmc.loader.api.metadata.Person;
import net.fabricmc.loader.impl.lib.gson.JsonReader;
import net.fabricmc.loader.impl.lib.gson.JsonToken;

final class V0ModMetadataParser {
	private static final Pattern WEBSITE_PATTERN = Pattern.compile("\\((.+)\\)");
	private static final Pattern EMAIL_PATTERN = Pattern.compile("<(.+)>");

	public static void parse(JsonReader reader, List<ParseWarning> warnings, ModMetadataBuilderImpl builder) throws IOException, ParseMetadataException {
		while (reader.hasNext()) {
			final String key = reader.nextName();

			switch (key) {
			case "schemaVersion":
				readSchemaVersion(reader, 0);
				break;
			case "id":
				readModId(reader, builder);
				break;
			case "version":
				readModVersion(reader, builder);
				break;
			case "requires":
				readDependency(reader, ModDependency.Kind.DEPENDS, key, builder);
				break;
			case "conflicts":
				readDependency(reader, ModDependency.Kind.BREAKS, key, builder);
				break;
			case "mixins":
				readMixins(reader, warnings, builder);
				break;
			case "side": {
				if (reader.peek() != JsonToken.STRING) {
					throw new ParseMetadataException("Side must be a string", reader);
				}

				final String rawEnvironment = reader.nextString();

				switch (rawEnvironment) {
				case "universal":
					builder.setEnvironment(ModEnvironment.UNIVERSAL);
					break;
				case "client":
					builder.setEnvironment(ModEnvironment.CLIENT);
					break;
				case "server":
					builder.setEnvironment(ModEnvironment.SERVER);
					break;
				default:
					warnings.add(new ParseWarning(reader, rawEnvironment, "Invalid side type"));
				}

				break;
			}
			case "initializer":
				// `initializer` and `initializers` cannot be used at the same time
				if (!builder.oldInitializers.isEmpty()) {
					throw new ParseMetadataException("initializer and initializers should not be set at the same time! (mod ID '" + builder.getId() + "')");
				}

				if (reader.peek() != JsonToken.STRING) {
					throw new ParseMetadataException("Initializer must be a non-empty string", reader);
				}

				builder.addOldInitializer(reader.nextString());
				break;
			case "initializers":
				// `initializer` and `initializers` cannot be used at the same time
				if (!builder.oldInitializers.isEmpty()) {
					throw new ParseMetadataException("initializer and initializers should not be set at the same time! (mod ID '" + builder.getId() + "')");
				}

				if (reader.peek() != JsonToken.BEGIN_ARRAY) {
					throw new ParseMetadataException("Initializers must be in a list", reader);
				}

				reader.beginArray();

				while (reader.hasNext()) {
					if (reader.peek() != JsonToken.STRING) {
						throw new ParseMetadataException("Initializer in initializers list must be a string", reader);
					}

					builder.addOldInitializer(reader.nextString());
				}

				reader.endArray();

				break;
			case "name":
				readModName(reader, builder);
				break;
			case "description":
				readModDescription(reader, builder);
				break;
			case "recommends":
				readDependency(reader, ModDependency.Kind.RECOMMENDS, "recommends", builder);
				break;
			case "authors":
				readPeople(reader, true, warnings, builder);
				break;
			case "contributors":
				readPeople(reader, false, warnings, builder);
				break;
			case "links":
				builder.setContact(readLinks(reader, warnings));
				break;
			case "license":
				if (reader.peek() != JsonToken.STRING) {
					throw new ParseMetadataException("License name must be a string", reader);
				}

				builder.addLicense(reader.nextString());
				break;
			default:
				if (!ModMetadataParser.IGNORED_KEYS.contains(key)) {
					warnings.add(new ParseWarning(reader, key, "Unsupported root entry"));
				}

				reader.skipValue();
				break;
			}
		}

		if (builder.getId() != null) {
			builder.setIcon("assets/" + builder.getId() + "/icon.png");
		}
	}

	static void readSchemaVersion(JsonReader reader, int expected) throws IOException, ParseMetadataException {
		// Duplicate field, make sure it matches our current schema version
		if (reader.peek() != JsonToken.NUMBER) {
			throw new ParseMetadataException("Duplicate \"schemaVersion\" field is not a number", reader);
		}

		final int read = reader.nextInt();

		if (read != expected) {
			throw new ParseMetadataException(String.format("Duplicate \"schemaVersion\" field does not match the predicted schema version of %d. Duplicate field value is %s", expected, read), reader);
		}
	}

	static void readModId(JsonReader reader, ModMetadataBuilder builder) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.STRING) {
			throw new ParseMetadataException("Mod id must be a non-empty string with a length of 3-64 characters.", reader);
		}

		builder.setId(reader.nextString());
	}

	static void readModVersion(JsonReader reader, ModMetadataBuilder builder) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.STRING) {
			throw new ParseMetadataException("Version must be a non-empty string", reader);
		}

		try {
			builder.setVersion(reader.nextString());
		} catch (VersionParsingException e) {
			throw new ParseMetadataException("Failed to parse version", e, reader);
		}
	}

	static void readModName(JsonReader reader, ModMetadataBuilder builder) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.STRING) {
			throw new ParseMetadataException("Mod name must be a string", reader);
		}

		builder.setName(reader.nextString());
	}

	static void readModDescription(JsonReader reader, ModMetadataBuilder builder) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.STRING) {
			throw new ParseMetadataException("Mod description must be a string", reader);
		}

		builder.setDescription(reader.nextString());
	}

	private static ContactInformation readLinks(JsonReader reader, List<ParseWarning> warnings) throws IOException, ParseMetadataException {
		final Map<String, String> contactInfo = new HashMap<>();

		switch (reader.peek()) {
		case STRING:
			contactInfo.put("homepage", reader.nextString());
			break;
		case BEGIN_OBJECT:
			reader.beginObject();

			while (reader.hasNext()) {
				final String key = reader.nextName();

				switch (key) {
				case "homepage":
					if (reader.peek() != JsonToken.STRING) {
						throw new ParseMetadataException("homepage link must be a string", reader);
					}

					contactInfo.put("homepage", reader.nextString());
					break;
				case "issues":
					if (reader.peek() != JsonToken.STRING) {
						throw new ParseMetadataException("issues link must be a string", reader);
					}

					contactInfo.put("issues", reader.nextString());
					break;
				case "sources":
					if (reader.peek() != JsonToken.STRING) {
						throw new ParseMetadataException("sources link must be a string", reader);
					}

					contactInfo.put("sources", reader.nextString());
					break;
				default:
					warnings.add(new ParseWarning(reader, key, "Unsupported links entry"));
					reader.skipValue();
				}
			}

			reader.endObject();
			break;
		default:
			throw new ParseMetadataException("Expected links to be an object or string", reader);
		}

		return new ContactInformationImpl(contactInfo);
	}

	private static void readMixins(JsonReader reader, List<ParseWarning> warnings, ModMetadataBuilder builder) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.BEGIN_OBJECT) {
			throw new ParseMetadataException("Expected mixins to be an object.", reader);
		}

		reader.beginObject();

		while (reader.hasNext()) {
			String envName = reader.nextName();
			ModEnvironment env;

			switch (envName) {
			case "client":
				env = ModEnvironment.CLIENT;
				break;
			case "common":
				env = ModEnvironment.UNIVERSAL;
				break;
			case "server":
				env = ModEnvironment.SERVER;
				break;
			default:
				warnings.add(new ParseWarning(reader, envName, "Invalid environment type"));
				reader.skipValue();
				continue;
			}

			switch (reader.peek()) {
			case NULL:
				reader.nextNull();
				break;
			case STRING:
				builder.addMixinConfig(reader.nextString(), env, null);
				break;
			case BEGIN_ARRAY:
				reader.beginArray();

				while (reader.hasNext()) {
					if (reader.peek() != JsonToken.STRING) {
						throw new ParseMetadataException(String.format("Expected entries in mixin %s to be an array of strings", envName), reader);
					}

					builder.addMixinConfig(reader.nextString(), env, null);
				}

				reader.endArray();
				break;
			default:
				throw new ParseMetadataException(String.format("Expected mixin %s to be a string or an array of strings", envName), reader);
			}
		}

		reader.endObject();
	}

	static void readDependency(JsonReader reader, ModDependency.Kind kind, String name, ModMetadataBuilder builder) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.BEGIN_OBJECT) {
			throw new ParseMetadataException(String.format("%s must be an object containing dependencies.", name), reader);
		}

		reader.beginObject();

		while (reader.hasNext()) {
			ModDependencyBuilder depBuilder = ModDependencyBuilder.create(kind, reader.nextName());
			depBuilder.setInferEnvironment(true);
			readDependencyValue(reader, depBuilder);

			builder.addDependency(depBuilder.build());
		}

		reader.endObject();
	}

	static void readDependencyValue(JsonReader reader, ModDependencyBuilder builder) throws IOException, ParseMetadataException {
		try {
			switch (reader.peek()) {
			case STRING:
				builder.addVersion(reader.nextString());
				break;
			case BEGIN_ARRAY:
				reader.beginArray();

				while (reader.hasNext()) {
					builder.addVersion(ParserUtil.readString(reader, "dependency version"));
				}

				reader.endArray();
				break;
			default:
				throw new ParseMetadataException("Expected dependency version to be a string or array", reader);
			}
		} catch (VersionParsingException e) {
			throw new ParseMetadataException(e, reader);
		}
	}

	private static void readPeople(JsonReader reader, boolean isAuthor, List<ParseWarning> warnings, ModMetadataBuilder builder) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.BEGIN_ARRAY) {
			throw new ParseMetadataException("List of people must be an array", reader);
		}

		reader.beginArray();

		while (reader.hasNext()) {
			Person person = readPerson(reader, warnings);

			if (isAuthor) {
				builder.addAuthor(person);
			} else {
				builder.addContributor(person);
			}
		}

		reader.endArray();
	}

	private static Person readPerson(JsonReader reader, List<ParseWarning> warnings) throws IOException, ParseMetadataException {
		final HashMap<String, String> contactMap = new HashMap<>();
		String name = "";

		switch (reader.peek()) {
		case STRING:
			final String person = reader.nextString();
			String[] parts = person.split(" ");

			Matcher websiteMatcher = V0ModMetadataParser.WEBSITE_PATTERN.matcher(parts[parts.length - 1]);

			if (websiteMatcher.matches()) {
				contactMap.put("website", websiteMatcher.group(1));
				parts = Arrays.copyOf(parts, parts.length - 1);
			}

			Matcher emailMatcher = V0ModMetadataParser.EMAIL_PATTERN.matcher(parts[parts.length - 1]);

			if (emailMatcher.matches()) {
				contactMap.put("email", emailMatcher.group(1));
				parts = Arrays.copyOf(parts, parts.length - 1);
			}

			name = String.join(" ", parts);

			return new ContactInfoBackedPerson(name, new ContactInformationImpl(contactMap));
		case BEGIN_OBJECT:
			reader.beginObject();

			while (reader.hasNext()) {
				final String key = reader.nextName();

				switch (key) {
				case "name":
					if (reader.peek() != JsonToken.STRING) {
						break;
					}

					name = reader.nextString();
					break;
				case "email":
					if (reader.peek() != JsonToken.STRING) {
						break;
					}

					contactMap.put("email", reader.nextString());
					break;
				case "website":
					if (reader.peek() != JsonToken.STRING) {
						break;
					}

					contactMap.put("website", reader.nextString());
					break;
				default:
					warnings.add(new ParseWarning(reader, key, "Unsupported contact information entry"));
					reader.skipValue();
				}
			}

			reader.endObject();
			return new ContactInfoBackedPerson(name, new ContactInformationImpl(contactMap));
		default:
			throw new ParseMetadataException("Expected person to be a string or object", reader);
		}
	}
}
