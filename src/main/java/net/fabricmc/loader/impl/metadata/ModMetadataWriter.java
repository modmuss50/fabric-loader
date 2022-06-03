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
import net.fabricmc.loader.impl.metadata.ModMetadataImpl.MixinEntry;

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
					if (entrypoint.getAdapter().equals(ModMetadataBuilderImpl.DEFAULT_ENTRYPOINT_ADAPTER)) {
						jw.value(entrypoint.getValue());
					} else {
						jw.beginObject();
						jw.name("value").value(entrypoint.getValue());
						jw.name("adapter").value(entrypoint.getAdapter());
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
			jw.beginArray();

			for (MixinEntry mixin : meta.mixins) {
				if (mixin.environment == ModEnvironment.UNIVERSAL) {
					jw.value(mixin.config);
				} else {
					jw.beginObject();
					jw.name("config").value(mixin.config);
					jw.name("environment").value(serializeEnvironment(mixin.environment));
					jw.endObject();
				}
			}

			jw.endArray();
		}

		if (!meta.classTweakers.isEmpty()) {
			jw.name("classTweakers");
			boolean multiple = meta.classTweakers.size() != 1;

			if (multiple) jw.beginArray();

			for (String accessWidener : meta.classTweakers) {
				jw.value(accessWidener);
			}

			if (multiple) jw.endArray();
		}

		if (!meta.dependencies.isEmpty()) {
			Map<ModDependency.Kind, List<ModDependency>> groupedDeps = new EnumMap<>(ModDependency.Kind.class);

			for (ModDependency dep : meta.dependencies) {
				groupedDeps.computeIfAbsent(dep.getKind(), ignore -> new ArrayList<>()).add(dep);
			}

			for (Map.Entry<ModDependency.Kind, List<ModDependency>> entry : groupedDeps.entrySet()) {
				ModDependency.Kind kind = entry.getKey();
				List<ModDependency> deps = entry.getValue();
				deps.sort(Comparator.comparing(ModDependency::getModId));

				jw.name(kind.name().toLowerCase(Locale.ENGLISH));
				jw.beginObject();

				for (ModDependency dep : deps) {
					jw.name(dep.getModId());
					boolean multiple = dep.getVersionRequirements().size() != 1;

					if (multiple) jw.beginArray();

					for (VersionPredicate predicate : dep.getVersionRequirements()) {
						jw.value(predicate.toString());
					}

					if (multiple) jw.endArray();
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
