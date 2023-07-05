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
import java.util.List;
import java.util.Locale;

import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.extension.ModMetadataBuilder;
import net.fabricmc.loader.api.extension.ModMetadataBuilder.ModDependencyBuilder;
import net.fabricmc.loader.api.extension.ModMetadataBuilder.ModDependencyMetadataBuilder;
import net.fabricmc.loader.api.metadata.ContactInformation;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModDependency.Kind;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import net.fabricmc.loader.api.metadata.ModLoadCondition;
import net.fabricmc.loader.api.metadata.Person;
import net.fabricmc.loader.impl.lib.gson.JsonReader;
import net.fabricmc.loader.impl.lib.gson.JsonToken;
import net.fabricmc.loader.impl.util.Expression;
import net.fabricmc.loader.impl.util.Expression.ExpressionParseException;
import net.fabricmc.loader.impl.util.StringUtil;

final class V2ModMetadataParser {
	/**
	 * Reads a {@code fabric.mod.json} file of schema version {@code 2}.
	 *
	 * <p>Changes over v1:
	 * <ul>
	 * <li>provides also accepts objects with id/version/exclusive
	 * <li>entrypoints object can use string values without an array
	 * <li>added loadCondition
	 * <li>added loadPhase
	 * <li>mixins can take a string directly, no array required
	 * <li>accessWidener -> classTweakers
	 * <li>mixins and classTweaks can accept an object with additional environment and condition entries, besides the plain string as before
	 * <li>license -> licenses
	 * <li>dependencies accept object value form with additional environment, condition, reason, metadata (id, name, description, contact), root metadata (same as dep metadata)
	 * </ul>
	 */
	static void parse(JsonReader reader, List<ParseWarning> warnings, ModMetadataBuilderImpl builder) throws IOException, ParseMetadataException {
		while (reader.hasNext()) {
			final String key = reader.nextName();

			// Work our way from required to entirely optional
			switch (key) {
			case "schemaVersion":
				V0ModMetadataParser.readSchemaVersion(reader, 2);
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
				builder.setEnvironment(V1ModMetadataParser.readEnvironment(reader));
				break;
			case "loadCondition":
				builder.setLoadCondition(ParserUtil.readEnum(reader, ModLoadCondition.class, key));
				break;
			case "loadPhase":
				builder.setLoadPhase(ParserUtil.readString(reader, key));
				break;
			case "entrypoints":
				readEntrypoints(reader, builder);
				break;
			case "jars":
				V1ModMetadataParser.readNestedJarEntries(reader, null, builder);
				break;
			case "mixins":
				readConditionalConfigs(reader, true, builder);
				break;
			case "classTweakers":
				readConditionalConfigs(reader, false, builder);
				break;
			case "depends":
				readDependency(reader, ModDependency.Kind.DEPENDS, builder);
				break;
			case "recommends":
				readDependency(reader, ModDependency.Kind.RECOMMENDS, builder);
				break;
			case "suggests":
				readDependency(reader, ModDependency.Kind.SUGGESTS, builder);
				break;
			case "conflicts":
				readDependency(reader, ModDependency.Kind.CONFLICTS, builder);
				break;
			case "breaks":
				readDependency(reader, ModDependency.Kind.BREAKS, builder);
				break;
			case "name":
				V0ModMetadataParser.readModName(reader, builder);
				break;
			case "description":
				V0ModMetadataParser.readModDescription(reader, builder);
				break;
			case "authors":
				readPeople(reader, true, builder);
				break;
			case "contributors":
				readPeople(reader, false, builder);
				break;
			case "contact":
				builder.setContact(V1ModMetadataParser.readContactInfo(reader));
				break;
			case "licenses":
				V1ModMetadataParser.readLicense(reader, builder);
				break;
			case "icon":
				V1ModMetadataParser.readIcon(reader, builder);
				break;
			case "languageAdapters":
				V1ModMetadataParser.readLanguageAdapters(reader, builder);
				break;
			case "custom":
				V1ModMetadataParser.readCustomValues(reader, builder);
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
			switch (reader.peek()) {
			case STRING:
				builder.addProvidedMod(reader.nextString(), null, true);
				break;
			case BEGIN_OBJECT: {
				reader.beginObject();

				String id = null;
				Version version = null;
				boolean exclusive = true;

				while (reader.hasNext()) {
					final String key = reader.nextName();

					switch (key) {
					case "id":
						id = ParserUtil.readString(reader, key);
						break;
					case "version":
						try {
							version = Version.parse(ParserUtil.readString(reader, key));
						} catch (VersionParsingException e) {
							throw new ParseMetadataException(e, reader);
						}

						break;
					case "exclusive":
						exclusive = ParserUtil.readBoolean(reader, key);
						break;
					default:
						throw new ParseMetadataException("Invalid key "+key+" in mixin config entry", reader);
					}
				}

				reader.endObject();

				if (id == null) {
					throw new ParseMetadataException.MissingField("Missing mandatory key 'id' in provides entry!");
				}

				builder.addProvidedMod(id, version, exclusive);
				break;
			}
			default:
				throw new ParseMetadataException("Provides entry must be a string or object!", reader);
			}
		}

		reader.endArray();
	}

	private static void readEntrypoints(JsonReader reader, ModMetadataBuilder builder) throws IOException, ParseMetadataException {
		// Entrypoints must be an object
		if (reader.peek() != JsonToken.BEGIN_OBJECT) {
			throw new ParseMetadataException("Entrypoints must be an object", reader);
		}

		reader.beginObject();

		while (reader.hasNext()) {
			final String key = reader.nextName();

			switch (reader.peek()) {
			case STRING:
				readEntrypoint(reader, key, builder);
				break;
			case BEGIN_ARRAY:
				reader.beginArray();

				while (reader.hasNext()) {
					readEntrypoint(reader, key, builder);
				}

				reader.endArray();
				break;
			default:
				throw new ParseMetadataException("Entrypoint list must be a string or array!", reader);
			}
		}

		reader.endObject();
	}

	private static void readEntrypoint(JsonReader reader, String key, ModMetadataBuilder builder) throws IOException, ParseMetadataException {
		String adapter = null;
		String value = null;
		Expression condition = null;

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
				case "condition":
					condition = readCondition(reader);
					break;
				default:
					throw new ParseMetadataException("Invalid key "+key+" in entrypoint entry", reader);
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

		builder.addEntrypoint(key, value, adapter, condition);
	}

	private static void readConditionalConfigs(JsonReader reader, boolean isMixin, ModMetadataBuilder builder) throws IOException, ParseMetadataException {
		String configName = isMixin ? "mixin" : "class tweaker";

		switch (reader.peek()) {
		case STRING:
			readConditionalConfig(reader, isMixin, configName, builder);
			break;
		case BEGIN_ARRAY:
			reader.beginArray();

			while (reader.hasNext()) {
				readConditionalConfig(reader, isMixin, configName, builder);
			}

			reader.endArray();
			break;
		default:
			throw new ParseMetadataException(StringUtil.capitalize(configName)+" must be a string or array of strings!", reader);
		}
	}

	private static void readConditionalConfig(JsonReader reader, boolean isMixin, String configName, ModMetadataBuilder builder) throws IOException, ParseMetadataException {
		switch (reader.peek()) {
		case STRING: {
			// All configs specified via string are assumed to be universal
			String config = reader.nextString();

			if (isMixin) {
				builder.addMixinConfig(config);
			} else {
				builder.addClassTweaker(config);
			}

			break;
		}
		case BEGIN_OBJECT:
			reader.beginObject();

			String config = null;
			ModEnvironment environment = null;
			Expression condition = null;

			while (reader.hasNext()) {
				final String key = reader.nextName();

				switch (key) {
				case "config":
					config = ParserUtil.readString(reader, key);
					break;
				case "environment": // optional
					environment = V1ModMetadataParser.readEnvironment(reader);
					break;
				case "condition": // optional
					condition = readCondition(reader);
					break;
				default:
					throw new ParseMetadataException("Invalid key "+key+" in "+configName+" config entry", reader);
				}
			}

			reader.endObject();

			if (config == null) {
				throw new ParseMetadataException.MissingField("Missing mandatory key 'config' in "+configName+" entry!");
			}

			if (isMixin) {
				builder.addMixinConfig(config, environment, condition);
			} else {
				builder.addClassTweaker(config, environment, condition);
			}

			break;
		default:
			throw new ParseMetadataException(StringUtil.capitalize(configName)+" list must be a string or object!", reader);
		}
	}

	private static void readDependency(JsonReader reader, ModDependency.Kind kind, ModMetadataBuilder builder) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.BEGIN_OBJECT) {
			throw new ParseMetadataException(String.format("%s must be an object containing dependencies.", kind.name().toLowerCase(Locale.ENGLISH)), reader);
		}

		reader.beginObject();

		while (reader.hasNext()) {
			ModDependencyBuilder depBuilder = ModDependencyBuilder.create(kind, reader.nextName());

			if (reader.peek() != JsonToken.BEGIN_OBJECT) {
				V0ModMetadataParser.readDependencyValue(reader, depBuilder);
			} else {
				ModDependencyMetadataBuilder metaBuilder = ModDependencyMetadataBuilder.create();
				boolean metaUsed = false;
				reader.beginObject();

				while (reader.hasNext()) {
					String key = reader.nextName();

					switch (key) {
					case "versions": // if this is absent depBuilder will default to any
						V0ModMetadataParser.readDependencyValue(reader, depBuilder);
						break;
					case "environment": {
						if (reader.peek() != JsonToken.STRING) {
							throw new ParseMetadataException("Dependency environment must be a string", reader);
						}

						final String environment = reader.nextString().toLowerCase(Locale.ROOT);

						switch (environment) {
						case "":
						case "*":
							depBuilder.setEnvironment(ModEnvironment.UNIVERSAL);
							break;
						case "client":
							depBuilder.setEnvironment(ModEnvironment.CLIENT);
							break;
						case "server":
							depBuilder.setEnvironment(ModEnvironment.SERVER);
							break;
						case "auto":
							if (!kind.isPositive() || kind == Kind.SUGGESTS) {
								throw new ParseMetadataException("Dependency environment " + environment + " is only applicable to depends and recommends!", reader);
							}

							depBuilder.setInferEnvironment(true);
							break;
						default:
							throw new ParseMetadataException("Invalid dependency environment type: " + environment + "!", reader);
						}

						break;
					}
					case "condition":
						depBuilder.setCondition(readCondition(reader));
						break;
					case "reason":
						depBuilder.setReason(ParserUtil.readString(reader, key));
						break;
					case "root":
						switch (reader.peek()) {
						case STRING:
							depBuilder.setRootMetadata(ModDependencyMetadataBuilder.create().setModId(ParserUtil.readString(reader, key)).build());
							break;
						case BEGIN_OBJECT: {
							ModDependencyMetadataBuilder rootMetaBuilder = ModDependencyMetadataBuilder.create();
							reader.beginObject();

							while (reader.hasNext()) {
								String metaKey = reader.nextName();

								if (!readDependencyMetadata(reader, metaKey, rootMetaBuilder)) {
									throw new ParseMetadataException("Invalid key "+metaKey+" in dependency root value", reader);
								}
							}

							reader.endObject();
							depBuilder.setRootMetadata(rootMetaBuilder.build());
							break;
						}
						default:
							throw new ParseMetadataException("Dependency root metadata must be a string or object", reader);
						}
						break;
					default:
						if (readDependencyMetadata(reader, key, metaBuilder)) {
							metaUsed = true;
						} else {
							throw new ParseMetadataException("Invalid key "+key+" in dependency value", reader);
						}
					}
				}

				reader.endObject();

				if (metaUsed) depBuilder.setMetadata(metaBuilder.build());
			}

			builder.addDependency(depBuilder.build());
		}

		reader.endObject();
	}

	private static boolean readDependencyMetadata(JsonReader reader, String key, ModDependencyMetadataBuilder builder) throws IOException, ParseMetadataException {
		switch (key) {
		case "id":
			builder.setModId(ParserUtil.readString(reader, key));
			break;
		case "name":
			builder.setName(ParserUtil.readString(reader, key));
			break;
		case "description":
			builder.setDescription(ParserUtil.readString(reader, key));
			break;
		case "contact":
			builder.setContact(V1ModMetadataParser.readContactInfo(reader));
			break;
		default:
			return false;
		}

		return true;
	}

	private static void readPeople(JsonReader reader, boolean isAuthor, ModMetadataBuilder builder) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.BEGIN_ARRAY) {
			throw new ParseMetadataException("List of people must be an array", reader);
		}

		reader.beginArray();

		while (reader.hasNext()) {
			Person person = readPerson(reader);

			if (isAuthor) {
				builder.addAuthor(person);
			} else {
				builder.addContributor(person);
			}
		}

		reader.endArray();
	}

	private static Person readPerson(JsonReader reader) throws IOException, ParseMetadataException {
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
					personName = ParserUtil.readString(reader, "person name");
					break;
					// Effectively optional
				case "contact":
					contactInformation = V1ModMetadataParser.readContactInfo(reader);
					break;
				default:
					throw new ParseMetadataException("Invalid key "+key+" in person entry", reader);
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

	private static Expression readCondition(JsonReader reader) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.STRING) {
			throw new ParseMetadataException("Condition must be a string", reader);
		}

		String expr = reader.nextString();

		try {
			Expression ret = Expression.parse(expr);

			if (!ret.maybeBooleanExpression()) {
				throw new ParseMetadataException("The condition "+expr+" doesn't yield a boolean value", reader);
			}

			return ret;
		} catch (ExpressionParseException e) {
			throw new ParseMetadataException("The condition "+expr+" can't be parsed", e, reader);
		}
	}
}
