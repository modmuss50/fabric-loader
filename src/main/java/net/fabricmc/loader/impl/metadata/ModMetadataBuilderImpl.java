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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.ContactInformation;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModDependency.Kind;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.Person;
import net.fabricmc.loader.api.metadata.ProvidedMod;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import net.fabricmc.loader.api.plugin.ModMetadataBuilder;
import net.fabricmc.loader.impl.metadata.ModMetadataImpl.EntrypointMetadataImpl;
import net.fabricmc.loader.impl.metadata.ModMetadataImpl.IconEntry;
import net.fabricmc.loader.impl.metadata.ModMetadataImpl.MapIconEntry;
import net.fabricmc.loader.impl.metadata.ModMetadataImpl.MixinEntry;
import net.fabricmc.loader.impl.metadata.ModMetadataImpl.ProvidedModImpl;
import net.fabricmc.loader.impl.metadata.ModMetadataImpl.SingleIconEntry;

public final class ModMetadataBuilderImpl implements ModMetadataBuilder {
	static final String DEFAULT_ENTRYPOINT_ADAPTER = "default";

	private final String id;
	private final Version version;

	// Optional (id provides)
	private final List<ProvidedMod> provides = new ArrayList<>();

	// Optional (mod loading)
	ModEnvironment environment = ModEnvironment.UNIVERSAL; // Default is always universal
	Map<String, List<EntrypointMetadata>> entrypoints = new HashMap<>();
	List<NestedJarEntry> jars = new ArrayList<>();
	List<MixinEntry> mixins = new ArrayList<>();
	String accessWidener = null;

	// Optional (dependency resolution)
	List<ModDependency> dependencies = new ArrayList<>();

	// Optional (metadata)
	String name = null;
	String description = null;
	List<Person> authors = new ArrayList<>();
	List<Person> contributors = new ArrayList<>();
	ContactInformation contact = null;
	List<String> license = new ArrayList<>();
	SortedMap<Integer, String> icons = new TreeMap<>(Comparator.naturalOrder());;

	// Optional (language adapter providers)
	Map<String, String> languageAdapters = new HashMap<>();

	// Optional (custom values)
	Map<String, CustomValue> customValues = new HashMap<>();

	public ModMetadataBuilderImpl(String id, Version version) {
		this.id = id;
		this.version = version;
	}

	@Override
	public ModMetadataBuilder provides(String modId, /* @Nullable */ Version version, boolean exclusive) {
		Objects.requireNonNull(modId, "null modId");

		provides.add(new ProvidedModImpl(modId, version != null ? version : this.version, exclusive));

		return this;
	}

	@Override
	public ModMetadataBuilder environment(ModEnvironment environment) {
		Objects.requireNonNull(environment, "null environment");

		this.environment = environment;

		return this;
	}

	@Override
	public ModMetadataBuilder entrypoint(String key, String value, /* @Nullable */ String adapter) {
		Objects.requireNonNull(key, "null key");
		Objects.requireNonNull(value, "null value");

		if (adapter == null) adapter = DEFAULT_ENTRYPOINT_ADAPTER;
		entrypoints.computeIfAbsent(key, ignore -> new ArrayList<>()).add(new EntrypointMetadataImpl(adapter, value));

		return this;
	}

	@Override
	public ModMetadataBuilder nestedJar(String location) {
		Objects.requireNonNull(location, "null location");

		jars.add(new ModMetadataImpl.NestedJarEntryImpl(location));

		return this;
	}

	@Override
	public ModMetadataBuilder mixinConfig(String location, ModEnvironment environment) {
		Objects.requireNonNull(location, "null location");
		Objects.requireNonNull(environment, "null environment");

		mixins.add(new MixinEntry(location, environment));

		return this;
	}

	@Override
	public ModMetadataBuilder accessWidener(String location) {
		Objects.requireNonNull(location, "null location");
		if (accessWidener != null && !accessWidener.equals(location)) throw new UnsupportedOperationException("support for multiple access widener files is not implemented");

		accessWidener = location;

		return this;
	}

	@Override
	public ModMetadataBuilder dependency(Kind kind, String modId, Collection<VersionPredicate> versionOptions) {
		Objects.requireNonNull(kind, "null kind");
		Objects.requireNonNull(modId, "null modId");
		Objects.requireNonNull(versionOptions, "null versionOptions");

		dependencies.add(new ModDependencyImpl(kind, modId, versionOptions));

		return this;
	}

	@Override
	public ModMetadataBuilder name(String name) {
		this.name = name;

		return this;
	}

	@Override
	public ModMetadataBuilder description(String description) {
		this.description = description;

		return this;
	}

	@Override
	public ModMetadataBuilder author(String name, /* @Nullable */ ContactInformation contact) {
		Objects.requireNonNull(name, "null name");

		authors.add(new ContactInfoBackedPerson(name, contact != null ? contact : ContactInformation.EMPTY));

		return this;
	}

	@Override
	public ModMetadataBuilder contributor(String name, /* @Nullable */ ContactInformation contact) {
		Objects.requireNonNull(name, "null name");

		contributors.add(new ContactInfoBackedPerson(name, contact != null ? contact : ContactInformation.EMPTY));

		return this;
	}

	@Override
	public ModMetadataBuilder contact(ContactInformation contact) {
		this.contact = contact;

		return this;
	}

	@Override
	public ModMetadataBuilder license(String name) {
		Objects.requireNonNull(name, "null name");

		this.license.add(name);

		return this;
	}

	@Override
	public ModMetadataBuilder icon(int size, String location) {
		Objects.requireNonNull(location, "null location");

		if (size <= 0) {
			size = 0;
			if (icons.size() > 1 || icons.size() == 1 && !icons.containsKey(0)) throw new IllegalArgumentException("mixing sized icons with single-size icon");
		} else {
			if (icons.containsKey(0)) throw new IllegalArgumentException("mixing sized icons with single-size icon");
		}

		icons.put(size, location);

		return this;
	}

	@Override
	public ModMetadataBuilder languageAdapter(String name, String cls) {
		Objects.requireNonNull(name, "null name");
		Objects.requireNonNull(cls, "null cls");

		languageAdapters.put(name, cls);

		return this;
	}

	@Override
	public ModMetadataBuilder customValue(String key, CustomValue value) {
		Objects.requireNonNull(key, "null key");
		Objects.requireNonNull(value, "null value");

		customValues.put(key, value);

		return this;
	}

	@Override
	public ModMetadata build() {
		IconEntry icon;

		if (icons.isEmpty()) {
			icon = null;
		} else if (icons.size() == 1) {
			icon = new SingleIconEntry(icons.values().iterator().next());
		} else {
			icon = new MapIconEntry(icons);
		}

		return new ModMetadataImpl(ModMetadataParser.LATEST_VERSION,
				id, version,
				provides,
				environment, entrypoints, jars,
				mixins, accessWidener,
				dependencies,
				name, description,
				authors, contributors, contact, license, icon,
				languageAdapters,
				customValues,
				Collections.emptyList());
	}

	public static final class ContactInformationBuilderImpl implements ContactInformationBuilder {
		private final Map<String, String> values = new HashMap<>();

		@Override
		public ContactInformationBuilder set(String key, String value) {
			Objects.requireNonNull(key, "null key");
			Objects.requireNonNull(value, "null value");

			values.put(key, value);

			return this;
		}

		@Override
		public ContactInformation build() {
			return values.isEmpty() ? ContactInformation.EMPTY : new ContactInformationImpl(values);
		}
	}
}
