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
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.CustomValue.CvObject;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import net.fabricmc.loader.api.metadata.Person;
import net.fabricmc.loader.api.metadata.ProvidedMod;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import net.fabricmc.loader.impl.discovery.LoadPhases;
import net.fabricmc.loader.impl.lib.gson.JsonWriter;
import net.fabricmc.loader.impl.metadata.ModMetadataImpl.ConditionalConfigEntry;
import net.fabricmc.loader.impl.util.version.VersionIntervalImpl;

final class ModMetadataWriter {
	static void write(ModMetadataBuilderImpl meta, Writer writer) throws IOException {
		@SuppressWarnings("resource")
		JsonWriter jw = new JsonWriter(writer);

		jw.beginObject();

		jw.name("schemaVersion").value(2);
		jw.name("id").value(meta.id);
		jw.name("version").value(meta.version.getFriendlyString());

		if (!meta.providedMods.isEmpty()) {
			jw.name("provides");
			jw.beginArray();

			for (ProvidedMod provided : meta.providedMods) {
				if (provided.getVersion().equals(meta.version) && provided.isExclusive()) {
					jw.value(provided.getId());
				} else {
					jw.beginObject();

					jw.name("id").value(provided.getId());

					if (!provided.getVersion().equals(meta.version)) {
						jw.name("version").value(provided.getVersion().getFriendlyString());
					}

					if (!provided.isExclusive()) {
						jw.name("exclusive").value(false);
					}

					jw.endObject();
				}
			}

			jw.endArray();
		}

		if (meta.environment != ModEnvironment.UNIVERSAL) {
			jw.name("environment").value(serializeEnvironment(meta.environment));
		}

		if (meta.getLoadCondition() != null) {
			jw.name("loadCondition").value(meta.getLoadCondition().name().toLowerCase(Locale.ENGLISH));
		}

		if (!meta.loadPhase.equals(LoadPhases.DEFAULT)) {
			jw.name("loadPhase").value(meta.loadPhase);
		}

		if (!meta.entrypoints.isEmpty()) {
			jw.name("entrypoints");
			jw.beginObject();

			for (Map.Entry<String, List<EntrypointMetadata>> entry : meta.entrypoints.entrySet()) {
				jw.name(entry.getKey());

				boolean multiple = entry.getValue().size() != 1;

				if (multiple) jw.beginArray();

				for (EntrypointMetadata entrypoint : entry.getValue()) {
					if (entrypoint.getAdapter().equals(ModMetadataBuilderImpl.DEFAULT_ENTRYPOINT_ADAPTER)
							&& entrypoint.getCondition() == null) {
						jw.value(entrypoint.getValue());
					} else {
						jw.beginObject();
						jw.name("value").value(entrypoint.getValue());

						if (!entrypoint.getAdapter().equals(ModMetadataBuilderImpl.DEFAULT_ENTRYPOINT_ADAPTER)) {
							jw.name("adapter").value(entrypoint.getAdapter());
						}

						if (entrypoint.getCondition() != null) {
							jw.name("condition").value(entrypoint.getCondition().toInfixString());
						}

						jw.endObject();
					}
				}

				if (multiple) jw.endArray();
			}

			jw.endObject();
		}

		if (!meta.nestedMods.isEmpty()) {
			jw.name("jars");
			jw.beginArray();

			for (NestedJarEntry mod : meta.nestedMods) {
				jw.beginObject();
				jw.name("file").value(mod.getFile());
				jw.endObject();
			}

			jw.endArray();
		}

		if (!meta.mixins.isEmpty()) {
			jw.name("mixins");
			writeConditionalConfigEntries(meta.mixins, jw);
		}

		if (!meta.classTweakers.isEmpty()) {
			jw.name("classTweakers");
			writeConditionalConfigEntries(meta.classTweakers, jw);
		}

		if (!meta.dependencies.isEmpty()) {
			Map<ModDependency.Kind, List<ModDependencyImpl>> groupedDeps = new EnumMap<>(ModDependency.Kind.class);

			for (ModDependencyImpl dep : meta.dependencies) {
				groupedDeps.computeIfAbsent(dep.getKind(), ignore -> new ArrayList<>()).add(dep);
			}

			for (Map.Entry<ModDependency.Kind, List<ModDependencyImpl>> entry : groupedDeps.entrySet()) {
				ModDependency.Kind kind = entry.getKey();
				List<ModDependencyImpl> deps = entry.getValue();
				deps.sort(Comparator.comparing(ModDependency::getModId));

				jw.name(kind.name().toLowerCase(Locale.ENGLISH));
				jw.beginObject();

				for (ModDependencyImpl dep : deps) {
					jw.name(dep.getModId());

					if (dep.getEnvironment() == ModEnvironment.UNIVERSAL
							&& !dep.isInferEnvironment()
							&& dep.getCondition() == null
							&& dep.getReason() == null
							&& dep.getMetadata() == null
							&& dep.getRootMetadata() == null) {
						writeDependencyVersionRequirements(dep, jw);
					} else {
						jw.beginObject();

						if (dep.getVersionRequirements().size() != 1
								|| !dep.getVersionRequirements().iterator().next().getInterval().equals(VersionIntervalImpl.INFINITE)) {
							jw.name("versions");
							writeDependencyVersionRequirements(dep, jw);
						}

						if (dep.getEnvironment() != ModEnvironment.UNIVERSAL || dep.isInferEnvironment()) {
							jw.name("environment");
							jw.value(dep.isInferEnvironment() ? "auto" : serializeEnvironment(dep.getEnvironment()));
						}

						if (dep.getOriginalCondition() != null) {
							jw.name("condition").value(dep.getOriginalCondition().toInfixString());
						}

						if (dep.getReason() != null) {
							jw.name("reason").value(dep.getReason());
						}

						if (dep.getMetadata() != null) {
							writeDependencyMetadata(dep.getMetadata(), dep.getModId(), jw);
						}

						if (dep.getRootMetadata() != null) {
							jw.name("root");
							jw.beginObject();
							writeDependencyMetadata(dep.getRootMetadata(), null, jw);
							jw.endObject();
						}

						jw.endObject();
					}
				}

				jw.endObject();
			}
		}

		if (meta.name != null) {
			jw.name("name").value(meta.name);
		}

		if (meta.description != null) {
			jw.name("description").value(meta.description);
		}

		if (!meta.authors.isEmpty()) {
			jw.name("authors");
			jw.beginArray();

			for (Person person : meta.authors) {
				writePerson(person, jw);
			}

			jw.endArray();
		}

		if (!meta.contributors.isEmpty()) {
			jw.name("contributors");
			jw.beginArray();

			for (Person person : meta.contributors) {
				writePerson(person, jw);
			}

			jw.endArray();
		}

		if (meta.contact != null && !meta.contact.asMap().isEmpty()) {
			jw.name("contact");
			writeStringStringMap(meta.contact.asMap(), jw);
		}

		if (!meta.licenses.isEmpty()) {
			jw.name("licenses");

			boolean multiple = meta.licenses.size() != 1;

			if (multiple) jw.beginArray();

			for (String license : meta.licenses) {
				jw.value(license);
			}

			if (multiple) jw.endArray();
		}

		if (!meta.icons.isEmpty()) {
			jw.name("icon");

			if (meta.icons.size() == 1 && meta.icons.keySet().iterator().next() <= 0) {
				jw.value(meta.icons.values().iterator().next());
			} else {
				jw.beginObject();

				for (Map.Entry<Integer, String> entry : meta.icons.entrySet()) {
					jw.name(entry.getKey().toString()).value(entry.getValue());
				}

				jw.endObject();
			}
		}

		if (!meta.languageAdapters.isEmpty()) {
			jw.name("languageAdapters");
			writeStringStringMap(meta.languageAdapters, jw);
		}

		if (!meta.customValues.isEmpty()) {
			List<Map.Entry<String, CustomValue>> entries = new ArrayList<>(meta.customValues.entrySet());
			entries.sort(Comparator.comparing(Map.Entry<String, CustomValue>::getKey));

			jw.name("custom");
			jw.beginObject();

			for (Map.Entry<String, CustomValue> entry : entries) {
				jw.name(entry.getKey());
				writeCustomValue(entry.getValue(), jw);
			}

			jw.endObject();
		}

		jw.endObject();

		jw.flush();
	}

	private static String serializeEnvironment(ModEnvironment env) {
		return env.name().toLowerCase(Locale.ENGLISH);
	}

	private static void writeConditionalConfigEntries(Collection<ConditionalConfigEntry> entries, JsonWriter jw) throws IOException {
		boolean multiple = entries.size() != 1;

		if (multiple) jw.beginArray();

		for (ConditionalConfigEntry entry : entries) {
			if (entry.environment == ModEnvironment.UNIVERSAL && entry.condition == null) {
				jw.value(entry.config);
			} else {
				jw.beginObject();
				jw.name("config").value(entry.config);

				if (entry.environment != ModEnvironment.UNIVERSAL) {
					jw.name("environment").value(serializeEnvironment(entry.environment));
				}

				if (entry.condition != null) {
					jw.name("condition").value(entry.condition.toInfixString());
				}

				jw.endObject();
			}
		}

		if (multiple) jw.endArray();
	}

	private static void writeDependencyVersionRequirements(ModDependency dep, JsonWriter jw) throws IOException {
		boolean multiple = dep.getVersionRequirements().size() != 1;

		if (multiple) jw.beginArray();

		for (VersionPredicate predicate : dep.getVersionRequirements()) {
			jw.value(predicate.toString());
		}

		if (multiple) jw.endArray();
	}

	private static void writeDependencyMetadata(ModDependency.Metadata metadata, String depModId, JsonWriter jw) throws IOException {
		if (metadata.getId() != null && !metadata.getId().equals(depModId)) {
			jw.name("id").value(metadata.getId());
		}

		if (metadata.getName() != null) {
			jw.name("name").value(metadata.getName());
		}

		if (metadata.getDescription() != null) {
			jw.name("description").value(metadata.getDescription());
		}

		if (metadata.getContact() != null && !metadata.getContact().asMap().isEmpty()) {
			jw.name("contact");
			writeStringStringMap(metadata.getContact().asMap(), jw);
		}
	}

	private static void writePerson(Person person, JsonWriter jw) throws IOException {
		Map<String, String> contact = person.getContact().asMap();

		if (contact.isEmpty()) {
			jw.value(person.getName());
		} else {
			jw.beginObject();

			jw.name("name").value(person.getName());
			jw.name("contact");
			writeStringStringMap(contact, jw);

			jw.endObject();
		}
	}

	private static void writeStringStringMap(Map<String, String> map, JsonWriter jw) throws IOException {
		List<Map.Entry<String, String>> entries = new ArrayList<>(map.entrySet());
		entries.sort(Comparator.comparing(Map.Entry<String, String>::getKey));

		jw.beginObject();

		for (Map.Entry<String, String> entry : entries) {
			jw.name(entry.getKey()).value(entry.getValue());
		}

		jw.endObject();
	}

	private static void writeCustomValue(CustomValue value, JsonWriter jw) throws IOException {
		switch (value.getType()) {
		case OBJECT: {
			jw.beginObject();

			CvObject obj = value.getAsObject();
			List<Map.Entry<String, CustomValue>> entries = new ArrayList<>(obj.size());

			for (Map.Entry<String, CustomValue> entry : obj) {
				entries.add(entry);
			}

			entries.sort(Comparator.comparing(Map.Entry<String, CustomValue>::getKey));

			for (Map.Entry<String, CustomValue> entry : entries) {
				jw.name(entry.getKey());
				writeCustomValue(entry.getValue(), jw);
			}

			jw.endObject();
			break;
		}
		case ARRAY:
			jw.beginArray();

			for (CustomValue v : value.getAsArray()) {
				writeCustomValue(v, jw);
			}

			jw.endArray();
			break;
		case STRING:
			jw.value(value.getAsString());
			break;
		case NUMBER:
			jw.value(value.getAsNumber());
			break;
		case BOOLEAN:
			jw.value(value.getAsBoolean());
			break;
		case NULL:
			jw.nullValue();
			break;
		default:
			throw new IllegalStateException("invalid cv type: "+value.getType());
		}
	}
}
