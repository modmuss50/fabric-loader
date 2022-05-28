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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.extension.ModMetadataBuilder;
import net.fabricmc.loader.api.metadata.ContactInformation;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import net.fabricmc.loader.api.metadata.Person;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import net.fabricmc.loader.impl.lib.gson.JsonReader;
import net.fabricmc.loader.impl.lib.gson.JsonToken;

final class V1ModMetadataParser {
	/**
	 * Reads a {@code fabric.mod.json} file of schema version {@code 1}.
	 */
	static void parse(JsonReader reader, List<ParseWarning> warnings, ModMetadataBuilderImpl builder) throws IOException, ParseMetadataException {
		while (reader.hasNext()) {
			final String key = reader.nextName();

			// Work our way from required to entirely optional
			switch (key) {
			case "schemaVersion":
				V0ModMetadataParser.readSchemaVersion(reader, 1);
				break;
			case "id":
				V0ModMetadataParser.readModId(reader, builder);
				break;
			case "version":
				V0ModMetadataParser.readModVersion(reader, builder);
				break;
			case "provides":
				readProvides(reader, builder);
				break;
			case "environment":
				builder.setEnvironment(readEnvironment(reader));
				break;
			case "entrypoints":
				readEntrypoints(reader, warnings, builder);
				break;
			case "jars":
				readNestedJarEntries(reader, warnings, builder);
				break;
			case "mixins":
				readMixinConfigs(reader, warnings, builder);
				break;
			case "accessWidener":
				if (reader.peek() != JsonToken.STRING) {
					throw new ParseMetadataException("Access Widener file must be a string", reader);
				}

				builder.setAccessWidener(reader.nextString());
				break;
			case "depends":
				readDependenciesContainer(reader, ModDependency.Kind.DEPENDS, builder);
				break;
			case "recommends":
				readDependenciesContainer(reader, ModDependency.Kind.RECOMMENDS, builder);
				break;
			case "suggests":
				readDependenciesContainer(reader, ModDependency.Kind.SUGGESTS, builder);
				break;
			case "conflicts":
				readDependenciesContainer(reader, ModDependency.Kind.CONFLICTS, builder);
				break;
			case "breaks":
				readDependenciesContainer(reader, ModDependency.Kind.BREAKS, builder);
				break;
			case "name":
				V0ModMetadataParser.readModName(reader, builder);
				break;
			case "description":
				V0ModMetadataParser.readModDescription(reader, builder);
				break;
			case "authors":
				readPeople(reader, true, warnings, builder);
				break;
			case "contributors":
				readPeople(reader, false, warnings, builder);
				break;
			case "contact":
				builder.setContact(readContactInfo(reader));
				break;
			case "license":
				readLicense(reader, builder);
				break;
			case "icon":
				readIcon(reader, builder);
				break;
			case "languageAdapters":
				readLanguageAdapters(reader, builder);
				break;
			case "custom":
				readCustomValues(reader, builder);
				break;
			default:
				if (!ModMetadataParser.IGNORED_KEYS.contains(key)) {
					warnings.add(new ParseWarning(reader, key, "Unsupported root entry"));
				}

				reader.skipValue();
				break;
			}
		}
	}

	private static void readProvides(JsonReader reader, ModMetadataBuilder builder) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.BEGIN_ARRAY) {
			throw new ParseMetadataException("Provides must be an array");
		}

		reader.beginArray();

		while (reader.hasNext()) {
			if (reader.peek() != JsonToken.STRING) {
				throw new ParseMetadataException("Provided id must be a string", reader);
			}

			builder.addProvidedMod(reader.nextString(), null, true);
		}

		reader.endArray();
	}

	static ModEnvironment readEnvironment(JsonReader reader) throws ParseMetadataException, IOException {
		if (reader.peek() != JsonToken.STRING) {
			throw new ParseMetadataException("Environment must be a string", reader);
		}

		final String environment = reader.nextString().toLowerCase(Locale.ROOT);

		if (environment.isEmpty() || environment.equals("*")) {
			return ModEnvironment.UNIVERSAL;
		} else if (environment.equals("client")) {
			return ModEnvironment.CLIENT;
		} else if (environment.equals("server")) {
			return ModEnvironment.SERVER;
		} else {
			throw new ParseMetadataException("Invalid environment type: " + environment + "!", reader);
		}
	}

	private static void readEntrypoints(JsonReader reader, List<ParseWarning> warnings, ModMetadataBuilder builder) throws IOException, ParseMetadataException {
		// Entrypoints must be an object
		if (reader.peek() != JsonToken.BEGIN_OBJECT) {
			throw new ParseMetadataException("Entrypoints must be an object", reader);
		}

		reader.beginObject();

		while (reader.hasNext()) {
			final String key = reader.nextName();

			if (reader.peek() != JsonToken.BEGIN_ARRAY) {
				throw new ParseMetadataException("Entrypoint list must be an array!", reader);
			}

			reader.beginArray();

			while (reader.hasNext()) {
				String adapter = null;
				String value = null;

				// Entrypoints may be specified directly as a string or as an object to allow specification of the language adapter to use.
				switch (reader.peek()) {
				case STRING:
					value = reader.nextString();
					break;
				case BEGIN_OBJECT:
					reader.beginObject();

					while (reader.hasNext()) {
						final String entryKey = reader.nextName();
						switch (entryKey) {
						case "adapter":
							adapter = reader.nextString();
							break;
						case "value":
							value = reader.nextString();
							break;
						default:
							warnings.add(new ParseWarning(reader, entryKey, "Invalid entry in entrypoint metadata"));
							reader.skipValue();
							break;
						}
					}

					reader.endObject();
					break;
				default:
					throw new ParseMetadataException("Entrypoint must be a string or object with \"value\" field", reader);
				}

				if (value == null) {
					throw new ParseMetadataException.MissingField("Entrypoint value must be present");
				}

				builder.addEntrypoint(key, value, adapter);
			}

			reader.endArray();
		}

		reader.endObject();
	}

	private static void readNestedJarEntries(JsonReader reader, List<ParseWarning> warnings, ModMetadataBuilder builder) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.BEGIN_ARRAY) {
			throw new ParseMetadataException("Jar entries must be in an array", reader);
		}

		reader.beginArray();

		while (reader.hasNext()) {
			if (reader.peek() != JsonToken.BEGIN_OBJECT) {
				throw new ParseMetadataException("Invalid type for JAR entry!", reader);
			}

			reader.beginObject();
			String file = null;

			while (reader.hasNext()) {
				final String key = reader.nextName();

				if (key.equals("file")) {
					if (reader.peek() != JsonToken.STRING) {
						throw new ParseMetadataException("\"file\" entry in jar object must be a string", reader);
					}

					file = reader.nextString();
				} else {
					warnings.add(new ParseWarning(reader, key, "Invalid entry in jar entry"));
					reader.skipValue();
				}
			}

			reader.endObject();

			if (file == null) {
				throw new ParseMetadataException("Missing mandatory key 'file' in JAR entry!", reader);
			}

			builder.addNestedMod(file);
		}

		reader.endArray();
	}

	private static void readMixinConfigs(JsonReader reader, List<ParseWarning> warnings, ModMetadataBuilder builder) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.BEGIN_ARRAY) {
			throw new ParseMetadataException("Mixin configs must be in an array", reader);
		}

		reader.beginArray();

		while (reader.hasNext()) {
			switch (reader.peek()) {
			case STRING:
				// All mixin configs specified via string are assumed to be universal
				builder.addMixinConfig(reader.nextString(), null);
				break;
			case BEGIN_OBJECT:
				reader.beginObject();

				String config = null;
				ModEnvironment environment = null;

				while (reader.hasNext()) {
					final String key = reader.nextName();

					switch (key) {
					// Environment is optional
					case "environment":
						environment = V1ModMetadataParser.readEnvironment(reader);
						break;
					case "config":
						if (reader.peek() != JsonToken.STRING) {
							throw new ParseMetadataException("Value of \"config\" must be a string", reader);
						}

						config = reader.nextString();
						break;
					default:
						warnings.add(new ParseWarning(reader, key, "Invalid entry in mixin config entry"));
						reader.skipValue();
					}
				}

				reader.endObject();

				if (config == null) {
					throw new ParseMetadataException.MissingField("Missing mandatory key 'config' in mixin entry!");
				}

				builder.addMixinConfig(config, environment);
				break;
			default:
				warnings.add(new ParseWarning(reader, "Invalid mixin entry type"));
				reader.skipValue();
				break;
			}
		}

		reader.endArray();
	}

	private static void readDependenciesContainer(JsonReader reader, ModDependency.Kind kind, ModMetadataBuilder builder) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.BEGIN_OBJECT) {
			throw new ParseMetadataException("Dependency container must be an object!", reader);
		}

		reader.beginObject();

		while (reader.hasNext()) {
			final String modId = reader.nextName();
			final List<String> matcherStringList = new ArrayList<>();

			switch (reader.peek()) {
			case STRING:
				matcherStringList.add(reader.nextString());
				break;
			case BEGIN_ARRAY:
				reader.beginArray();

				while (reader.hasNext()) {
					if (reader.peek() != JsonToken.STRING) {
						throw new ParseMetadataException("Dependency version range array must only contain string values", reader);
					}

					matcherStringList.add(reader.nextString());
				}

				reader.endArray();
				break;
			default:
				throw new ParseMetadataException("Dependency version range must be a string or string array!", reader);
			}

			try {
				builder.addDependency(kind, modId, VersionPredicate.parse(matcherStringList));
			} catch (VersionParsingException e) {
				throw new ParseMetadataException(e);
			}
		}

		reader.endObject();
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
		switch (reader.peek()) {
		case STRING:
			// Just a name
			return new SimplePerson(reader.nextString());
		case BEGIN_OBJECT:
			// Map-backed impl
			reader.beginObject();
			// Name is required
			String personName = null;
			ContactInformation contactInformation = ContactInformation.EMPTY; // Empty if not specified

			while (reader.hasNext()) {
				final String key = reader.nextName();

				switch (key) {
				case "name":
					if (reader.peek() != JsonToken.STRING) {
						throw new ParseMetadataException("Name of person in dependency container must be a string", reader);
					}

					personName = reader.nextString();
					break;
					// Effectively optional
				case "contact":
					contactInformation = V1ModMetadataParser.readContactInfo(reader);
					break;
				default:
					// Ignore unsupported keys
					warnings.add(new ParseWarning(reader, key, "Invalid entry in person"));
					reader.skipValue();
				}
			}

			reader.endObject();

			if (personName == null) {
				throw new ParseMetadataException.MissingField("Person object must have a 'name' field!");
			}

			return new ContactInfoBackedPerson(personName, contactInformation);
		default:
			throw new ParseMetadataException("Person type must be an object or string!", reader);
		}
	}

	private static ContactInformation readContactInfo(JsonReader reader) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.BEGIN_OBJECT) {
			throw new ParseMetadataException("Contact info must in an object", reader);
		}

		reader.beginObject();

		final Map<String, String> map = new HashMap<>();

		while (reader.hasNext()) {
			final String key = reader.nextName();

			if (reader.peek() != JsonToken.STRING) {
				throw new ParseMetadataException("Contact information entries must be a string", reader);
			}

			map.put(key, reader.nextString());
		}

		reader.endObject();

		// Map is wrapped as unmodifiable in the contact info impl
		return new ContactInformationImpl(map);
	}

	private static void readLicense(JsonReader reader, ModMetadataBuilder builder) throws IOException, ParseMetadataException {
		switch (reader.peek()) {
		case STRING:
			builder.addLicense(reader.nextString());
			break;
		case BEGIN_ARRAY:
			reader.beginArray();

			while (reader.hasNext()) {
				if (reader.peek() != JsonToken.STRING) {
					throw new ParseMetadataException("List of licenses must only contain strings", reader);
				}

				builder.addLicense(reader.nextString());
			}

			reader.endArray();
			break;
		default:
			throw new ParseMetadataException("License must be a string or array of strings!", reader);
		}
	}

	private static void readIcon(JsonReader reader, ModMetadataBuilder builder) throws IOException, ParseMetadataException {
		switch (reader.peek()) {
		case STRING:
			builder.setIcon(reader.nextString());
			break;
		case BEGIN_OBJECT:
			reader.beginObject();

			final NavigableMap<Integer, String> iconMap = new TreeMap<>(Comparator.naturalOrder());

			while (reader.hasNext()) {
				String key = reader.nextName();
				int size;

				try {
					size = Integer.parseInt(key);
				} catch (NumberFormatException e) {
					throw new ParseMetadataException("Could not parse icon size '" + key + "'!", e);
				}

				if (size < 1) {
					throw new ParseMetadataException("Size must be positive!", reader);
				}

				if (reader.peek() != JsonToken.STRING) {
					throw new ParseMetadataException("Icon path must be a string", reader);
				}

				builder.addIcon(size, reader.nextString());
			}

			reader.endObject();

			if (iconMap.isEmpty()) {
				throw new ParseMetadataException("Icon object must not be empty!", reader);
			}

			break;
		default:
			throw new ParseMetadataException("Icon entry must be an object or string!", reader);
		}
	}

	private static void readLanguageAdapters(JsonReader reader, ModMetadataBuilder builder) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.BEGIN_OBJECT) {
			throw new ParseMetadataException("Language adapters must be in an object", reader);
		}

		reader.beginObject();

		while (reader.hasNext()) {
			final String adapter = reader.nextName();

			if (reader.peek() != JsonToken.STRING) {
				throw new ParseMetadataException("Value of language adapter entry must be a string", reader);
			}

			builder.addLanguageAdapter(adapter, reader.nextString());
		}

		reader.endObject();
	}

	private static void readCustomValues(JsonReader reader, ModMetadataBuilder builder) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.BEGIN_OBJECT) {
			throw new ParseMetadataException("Custom values must be in an object!", reader);
		}

		reader.beginObject();

		while (reader.hasNext()) {
			builder.addCustomValue(reader.nextName(), CustomValueImpl.readCustomValue(reader));
		}

		reader.endObject();
	}
}
