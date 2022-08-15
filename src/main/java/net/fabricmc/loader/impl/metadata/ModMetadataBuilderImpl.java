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
import java.io.Reader;
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
import net.fabricmc.loader.api.metadata.ModEnvironment;
import net.fabricmc.loader.api.metadata.ModLoadCondition;
import net.fabricmc.loader.api.metadata.Person;
import net.fabricmc.loader.api.metadata.ProvidedMod;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.discovery.LoadPhases;
import net.fabricmc.loader.impl.metadata.ModMetadataImpl.ConditionalConfigEntry;
import net.fabricmc.loader.impl.metadata.ModMetadataImpl.EntrypointMetadataImpl;
import net.fabricmc.loader.impl.metadata.ModMetadataImpl.IconEntry;
import net.fabricmc.loader.impl.metadata.ModMetadataImpl.MapIconEntry;
import net.fabricmc.loader.impl.metadata.ModMetadataImpl.ProvidedModImpl;
import net.fabricmc.loader.impl.metadata.ModMetadataImpl.SingleIconEntry;
import net.fabricmc.loader.impl.util.Expression;

public final class ModMetadataBuilderImpl implements ModMetadataBuilder {
	static final String DEFAULT_ENTRYPOINT_ADAPTER = "default";

	int schemaVersion = ModMetadataParser.LATEST_VERSION;

	String id;
	Version version;

	// Optional (id provides)
	final List<ProvidedModImpl> providedMods = new ArrayList<>();

	// Optional (mod loading)
	ModEnvironment environment = ModEnvironment.UNIVERSAL; // Default is always universal
	ModLoadCondition loadCondition;
	String loadPhase = LoadPhases.DEFAULT;
	final Map<String, List<EntrypointMetadata>> entrypoints = new HashMap<>();
	final List<String> oldInitializers = new ArrayList<>();
	final List<NestedJarEntry> nestedMods = new ArrayList<>();
	final List<ConditionalConfigEntry> mixins = new ArrayList<>();
	final List<ConditionalConfigEntry> classTweakers = new ArrayList<>();

	// Optional (dependency resolution)
	List<ModDependencyImpl> dependencies = new ArrayList<>();

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
			if (!mod.hasOwnVersion) mod.setVersion(version);
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

		boolean hasOwnVersion = version != null;
		providedMods.add(new ProvidedModImpl(modId, hasOwnVersion ? version : this.version, hasOwnVersion, exclusive));

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
	public ModLoadCondition getLoadCondition() {
		return loadCondition;
	}

	@Override
	public ModMetadataBuilder setLoadCondition(/* @Nullable */ ModLoadCondition loadCondition) {
		this.loadCondition = loadCondition;

		return this;
	}

	@Override
	public ModMetadataBuilder setLoadPhase(/* @Nullable */ String loadPhase) {
		if (loadPhase == null) loadPhase = LoadPhases.DEFAULT;

		this.loadPhase = loadPhase;

		return this;
	}

	@Override
	public ModMetadataBuilder addEntrypoint(String key, String value) {
		return addEntrypoint(key, value, null, null);
	}

	@Override
	public ModMetadataBuilder addEntrypoint(String key, String value, /* @Nullable */ String adapter, /* @Nullable */ Expression condition) {
		Objects.requireNonNull(key, "null key");
		Objects.requireNonNull(value, "null value");

		if (adapter == null) adapter = DEFAULT_ENTRYPOINT_ADAPTER;
		entrypoints.computeIfAbsent(key, ignore -> new ArrayList<>()).add(new EntrypointMetadataImpl(adapter, value, condition));

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
	public ModMetadataBuilder addMixinConfig(String location) {
		return addMixinConfig(location, null, null);
	}

	@Override
	public ModMetadataBuilder addMixinConfig(String location, /* @Nullable */ ModEnvironment environment, /* @Nullable */ Expression condition) {
		Objects.requireNonNull(location, "null location");

		mixins.add(new ConditionalConfigEntry(location, environment != null ? environment : ModEnvironment.UNIVERSAL, condition));

		return this;
	}

	@Override
	public ModMetadataBuilder addClassTweaker(String location) {
		return addClassTweaker(location, null, null);
	}

	@Override
	public ModMetadataBuilder addClassTweaker(String location, /* @Nullable */ ModEnvironment environment, /* @Nullable */ Expression condition) {
		Objects.requireNonNull(location, "null location");

		classTweakers.add(new ConditionalConfigEntry(location, environment != null ? environment : ModEnvironment.UNIVERSAL, condition));

		return this;
	}

	@Override
	public Collection<? extends ModDependency> getDependencies() {
		return dependencies;
	}

	@Override
	public ModMetadataBuilder addDependency(ModDependency dependency) {
		Objects.requireNonNull(dependency, "null dependency");
		if (dependency.getClass() != ModDependencyImpl.class) throw new IllegalArgumentException("invalid dependency class "+dependency.getClass().getName());

		dependencies.add((ModDependencyImpl) dependency);

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
	public void fromJson(Reader reader) throws IOException {
		try {
			ModMetadataParser.readModMetadata(reader, FabricLoaderImpl.INSTANCE.isDevelopmentEnvironment(), new ArrayList<>(), this);
		} catch (ParseMetadataException e) {
			throw new IOException(e);
		}
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
				environment, loadCondition, loadPhase,
				entrypoints, nestedMods,
				mixins, classTweakers,
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

	public static final class ModDependencyBuilderImpl implements ModDependencyBuilder {
		private ModDependency.Kind kind;
		private String modId;
		private final Collection<VersionPredicate> versionOptions = new ArrayList<>();
		private ModEnvironment environment = ModEnvironment.UNIVERSAL;
		private boolean inferEnvironment;
		private Expression condition;
		private String reason;
		private ModDependency.Metadata metadata;
		private ModDependency.Metadata rootMetadata;

		@Override
		public ModDependencyBuilder setKind(ModDependency.Kind kind) {
			Objects.requireNonNull(kind, "null kind");

			this.kind = kind;

			return this;
		}

		@Override
		public ModDependencyBuilder setModId(String modId) {
			Objects.requireNonNull(modId, "null modId");

			this.modId = modId;

			return this;
		}

		@Override
		public ModDependencyBuilder addVersion(String predicate) throws VersionParsingException {
			return addVersion(VersionPredicate.parse(predicate));
		}

		@Override
		public ModDependencyBuilder addVersion(VersionPredicate predicate) {
			Objects.requireNonNull(predicate, "null predicate");

			versionOptions.add(predicate);

			return this;
		}

		@Override
		public ModDependencyBuilder addVersions(Collection<VersionPredicate> predicates) {
			versionOptions.addAll(predicates);

			return this;
		}

		@Override
		public ModDependencyBuilder setEnvironment(ModEnvironment environment) {
			this.environment = environment;

			return this;
		}

		@Override
		public ModDependencyBuilder setInferEnvironment(boolean value) {
			this.inferEnvironment = value;

			return this;
		}

		@Override
		public ModDependencyBuilder setCondition(/* @Nullable */ Expression condition) {
			this.condition = condition;

			return this;
		}

		@Override
		public ModDependencyBuilder setReason(/* @Nullable */ String reason) {
			this.reason = reason;

			return this;
		}

		@Override
		public ModDependencyBuilder setMetadata(/* @Nullable */ ModDependency.Metadata metadata) {
			if (metadata != null && metadata.getClass() != ModDependencyImpl.Metadata.class) throw new IllegalArgumentException("invalid metadata class "+metadata.getClass().getName());

			this.metadata = metadata;

			return this;
		}

		@Override
		public ModDependencyBuilder setRootMetadata(/* @Nullable */ ModDependency.Metadata metadata) {
			if (metadata != null && metadata.getClass() != ModDependencyImpl.Metadata.class) throw new IllegalArgumentException("invalid metadata class "+metadata.getClass().getName());

			this.rootMetadata = metadata;

			return this;
		}

		@Override
		public ModDependency build() {
			if (kind == null) throw new IllegalStateException("kind is not set");
			if (modId == null) throw new IllegalStateException("modId is not set");
			if (versionOptions.isEmpty()) versionOptions.add(VersionPredicate.any());

			return new ModDependencyImpl(kind, modId, versionOptions,
					environment, inferEnvironment, condition, reason,
					metadata, rootMetadata);
		}
	}

	public static final class ModDependencyMetadataBuilderImpl implements ModDependencyMetadataBuilder {
		private String modId;
		private String name;
		private String description;
		private ContactInformation contact;

		@Override
		public ModDependencyMetadataBuilder setModId(/* @Nullable */ String modId) {
			this.modId = modId;

			return this;
		}

		@Override
		public ModDependencyMetadataBuilder setName(/* @Nullable */ String name) {
			this.name = name;

			return this;
		}

		@Override
		public ModDependencyMetadataBuilder setDescription(/* @Nullable */ String description) {
			this.description = description;

			return this;
		}

		@Override
		public ModDependencyMetadataBuilder setContact(/* @Nullable */ ContactInformation contact) {
			this.contact = contact;

			return this;
		}

		@Override
		public ModDependency.Metadata build() {
			return new ModDependencyImpl.Metadata(modId, name, description, contact);
		}
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
