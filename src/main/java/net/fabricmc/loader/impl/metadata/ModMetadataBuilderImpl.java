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
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.extension.ModMetadataBuilder;
import net.fabricmc.loader.api.metadata.ContactInformation;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModDependency.Kind;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import net.fabricmc.loader.api.metadata.Person;
import net.fabricmc.loader.api.metadata.ProvidedMod;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import net.fabricmc.loader.impl.discovery.LoadPhases;
import net.fabricmc.loader.impl.metadata.ModMetadataImpl.EntrypointMetadataImpl;
import net.fabricmc.loader.impl.metadata.ModMetadataImpl.IconEntry;
import net.fabricmc.loader.impl.metadata.ModMetadataImpl.MapIconEntry;
import net.fabricmc.loader.impl.metadata.ModMetadataImpl.MixinEntry;
import net.fabricmc.loader.impl.metadata.ModMetadataImpl.ProvidedModImpl;
import net.fabricmc.loader.impl.metadata.ModMetadataImpl.SingleIconEntry;

public final class ModMetadataBuilderImpl implements ModMetadataBuilder {
	static final String DEFAULT_ENTRYPOINT_ADAPTER = "default";

	int schemaVersion = ModMetadataParser.LATEST_VERSION;

	String id;
	Version version;

	// Optional (id provides)
	final List<ProvidedModImpl> providedMods = new ArrayList<>();

	// Optional (mod loading)
	ModEnvironment environment = ModEnvironment.UNIVERSAL; // Default is always universal
	String loadPhase = LoadPhases.DEFAULT;
	final Map<String, List<EntrypointMetadata>> entrypoints = new HashMap<>();
	final List<String> oldInitializers = new ArrayList<>();
	final List<NestedJarEntry> nestedMods = new ArrayList<>();
	final List<MixinEntry> mixins = new ArrayList<>();
	String accessWidener = null;

	// Optional (dependency resolution)
	List<ModDependency> dependencies = new ArrayList<>();

	// Optional (metadata)
	String name = null;
	String description = null;
	final List<Person> authors = new ArrayList<>();
	final List<Person> contributors = new ArrayList<>();
	ContactInformation contact = null;
	final List<String> licenses = new ArrayList<>();
	final NavigableMap<Integer, String> icons = new TreeMap<>(Comparator.naturalOrder());;

	// Optional (language adapter providers)
	final Map<String, String> languageAdapters = new HashMap<>();

	// Optional (custom values)
	final Map<String, CustomValue> customValues = new HashMap<>();

	public ModMetadataBuilderImpl() { }

	public ModMetadataBuilder setSchemaVersion(int version) {
		this.schemaVersion = version;

		return this;
	}

	@Override
	public String getType() {
		return ModMetadataImpl.TYPE_FABRIC_MOD;
	}

	@Override
	public String getId() {
		return id;
	}

	@Override
	public ModMetadataBuilder setId(String modId) {
		this.id = modId;

		return this;
	}

	@Override
	public Version getVersion() {
		return version;
	}

	@Override
	public ModMetadataBuilder setVersion(String version) throws VersionParsingException {
		return setVersion(Version.parse(version));
	}

	@Override
	public ModMetadataBuilder setVersion(Version version) {
		// replace default version in provided mods if it points to the old version
		for (ProvidedModImpl mod : providedMods) {
			if (mod.getVersion() == this.version) { // identity cmp is fine because it was defaulted to this.version
				mod.setVersion(version);
			}
		}

		this.version = version;

		return this;
	}

	@Override
	public Collection<? extends ProvidedMod> getAdditionallyProvidedMods() {
		return providedMods;
	}

	@Override
	public ModMetadataBuilder addProvidedMod(String modId, /* @Nullable */ Version version, boolean exclusive) {
		Objects.requireNonNull(modId, "null modId");

		providedMods.add(new ProvidedModImpl(modId, version != null ? version : this.version, exclusive));

		return this;
	}

	@Override
	public ModEnvironment getEnvironment() {
		return environment;
	}

	@Override
	public ModMetadataBuilder setEnvironment(ModEnvironment environment) {
		Objects.requireNonNull(environment, "null environment");

		this.environment = environment;

		return this;
	}

	@Override
	public ModMetadataBuilder setLoadPhase(String loadPhase) {
		this.loadPhase = loadPhase;

		return this;
	}

	@Override
	public ModMetadataBuilder addEntrypoint(String key, String value, /* @Nullable */ String adapter) {
		Objects.requireNonNull(key, "null key");
		Objects.requireNonNull(value, "null value");

		if (adapter == null) adapter = DEFAULT_ENTRYPOINT_ADAPTER;
		entrypoints.computeIfAbsent(key, ignore -> new ArrayList<>()).add(new EntrypointMetadataImpl(adapter, value));

		return this;
	}

	public ModMetadataBuilder addOldInitializer(String initializer) {
		Objects.requireNonNull(initializer, "null initializer");

		oldInitializers.add(initializer);

		return this;
	}

	@Override
	public ModMetadataBuilder addNestedMod(String location) {
		Objects.requireNonNull(location, "null location");

		nestedMods.add(new ModMetadataImpl.NestedJarEntryImpl(location));

		return this;
	}

	@Override
	public ModMetadataBuilder addMixinConfig(String location, /* @Nullable */ ModEnvironment environment) {
		Objects.requireNonNull(location, "null location");

		mixins.add(new MixinEntry(location, environment != null ? environment : ModEnvironment.UNIVERSAL));

		return this;
	}

	@Override
	public ModMetadataBuilder setAccessWidener(String location) {
		Objects.requireNonNull(location, "null location");

		accessWidener = location;

		return this;
	}

	@Override
	public Collection<ModDependency> getDependencies() {
		return dependencies;
	}

	@Override
	public ModMetadataBuilder addDependency(Kind kind, String modId, Collection<VersionPredicate> versionOptions) {
		Objects.requireNonNull(kind, "null kind");
		Objects.requireNonNull(modId, "null modId");
		Objects.requireNonNull(versionOptions, "null versionOptions");

		dependencies.add(new ModDependencyImpl(kind, modId, versionOptions));

		return this;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public ModMetadataBuilder setName(String name) {
		this.name = name;

		return this;
	}

	@Override
	public String getDescription() {
		return description;
	}

	@Override
	public ModMetadataBuilder setDescription(String description) {
		this.description = description;

		return this;
	}

	@Override
	public Collection<Person> getAuthors() {
		return authors;
	}

	@Override
	public ModMetadataBuilder addAuthor(String name, /* @Nullable */ ContactInformation contact) {
		return addAuthor(createPerson(name, contact));
	}

	@Override
	public ModMetadataBuilder addAuthor(Person person) {
		Objects.requireNonNull(person, "null person");

		authors.add(person);

		return this;
	}

	@Override
	public Collection<Person> getContributors() {
		return contributors;
	}

	@Override
	public ModMetadataBuilder addContributor(String name, /* @Nullable */ ContactInformation contact) {
		return addContributor(createPerson(name, contact));
	}

	@Override
	public ModMetadataBuilder addContributor(Person person) {
		Objects.requireNonNull(person, "null person");

		authors.add(person);

		return this;
	}

	private static Person createPerson(String name, /* @Nullable */ ContactInformation contact) {
		Objects.requireNonNull(name, "null name");

		if (contact != null
				&& contact != ContactInformation.EMPTY
				&& !contact.asMap().isEmpty()) {
			return new ContactInfoBackedPerson(name, contact);
		} else {
			return new SimplePerson(name);
		}
	}

	@Override
	public ContactInformation getContact() {
		return contact;
	}

	@Override
	public ModMetadataBuilder setContact(/* @Nullable */ ContactInformation contact) {
		this.contact = contact;

		return this;
	}

	@Override
	public Collection<String> getLicense() {
		return licenses;
	}

	@Override
	public ModMetadataBuilder addLicense(String name) {
		Objects.requireNonNull(name, "null name");

		this.licenses.add(name);

		return this;
	}

	@Override
	public Optional<String> getIconPath(int size) {
		if (icons.isEmpty()) return Optional.empty();

		Map.Entry<Integer, String> entry = icons.ceilingEntry(size);
		if (entry == null) entry = icons.lastEntry();

		return Optional.of(entry.getValue());
	}

	@Override
	public ModMetadataBuilder setIcon(String location) {
		return addIcon(0, location);
	}

	@Override
	public ModMetadataBuilder addIcon(int size, String location) {
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
	public ModMetadataBuilder addLanguageAdapter(String name, String cls) {
		Objects.requireNonNull(name, "null name");
		Objects.requireNonNull(cls, "null cls");

		languageAdapters.put(name, cls);

		return this;
	}

	@Override
	public boolean containsCustomValue(String key) {
		return customValues.containsKey(key);
	}

	@Override
	public CustomValue getCustomValue(String key) {
		return customValues.get(key);
	}

	@Override
	public Map<String, CustomValue> getCustomValues() {
		return customValues;
	}

	@Override
	public ModMetadataBuilder addCustomValue(String key, CustomValue value) {
		Objects.requireNonNull(key, "null key");
		Objects.requireNonNull(value, "null value");

		customValues.put(key, value);

		return this;
	}

	@Override
	public void toJson(Writer writer) throws IOException {
		checkInitialized();

		ModMetadataWriter.write(this, writer);
	}

	@Override
	public String toJson() {
		StringWriter sw = new StringWriter(100);

		try {
			toJson(sw);
		} catch (IOException e) {
			throw new UncheckedIOException(e); // shouldn't happen..
		}

		return sw.toString();
	}

	@Override
	public ModMetadataImpl build() {
		checkInitialized();

		IconEntry icon;

		if (icons.isEmpty()) {
			icon = null;
		} else if (icons.size() == 1) {
			icon = new SingleIconEntry(icons.values().iterator().next());
		} else {
			icon = new MapIconEntry(icons);
		}

		return new ModMetadataImpl(schemaVersion,
				id, version,
				providedMods,
				environment, loadPhase,
				entrypoints, nestedMods,
				mixins, accessWidener,
				dependencies,
				name, description,
				authors, contributors, contact, licenses, icon,
				languageAdapters,
				customValues,
				oldInitializers);
	}

	private void checkInitialized() {
		if (id == null) throw new IllegalStateException("modId wasn't set");
		if (version == null) throw new IllegalStateException("version wasn't set");
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
