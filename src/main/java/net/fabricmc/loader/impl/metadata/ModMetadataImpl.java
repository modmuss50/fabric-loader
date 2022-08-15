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
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.ContactInformation;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import net.fabricmc.loader.api.metadata.ModLoadCondition;
import net.fabricmc.loader.api.metadata.Person;
import net.fabricmc.loader.api.metadata.ProvidedMod;
import net.fabricmc.loader.impl.FormattedException;
import net.fabricmc.loader.impl.util.Expression;
import net.fabricmc.loader.impl.util.Expression.DynamicFunction;
import net.fabricmc.loader.impl.util.Expression.ExpressionEvaluateException;
import net.fabricmc.loader.impl.util.StringUtil;

final class ModMetadataImpl extends AbstractModMetadata implements LoaderModMetadata {
	static final IconEntry NO_ICON = size -> Optional.empty();

	private final int schemaVersion;

	// Required values
	private final String id;
	private Version version;

	// Optional (id provides)
	private final Collection<ProvidedModImpl> providedMods;

	// Optional (mod loading)
	private final ModEnvironment environment;
	private final ModLoadCondition loadCondition;
	private final String loadPhase;
	private final Map<String, List<EntrypointMetadata>> entrypoints;
	private final Collection<NestedJarEntry> jars;
	private final Collection<ConditionalConfigEntry> mixins;
	private final Collection<ConditionalConfigEntry> classTweakers;

	// Optional (dependency resolution)
	private Collection<ModDependencyImpl> dependencies;

	// Optional (metadata)
	/* @Nullable */
	private final String name;
	private final String description;
	private final Collection<Person> authors;
	private final Collection<Person> contributors;
	private final ContactInformation contact;
	private final Collection<String> license;
	private final IconEntry icon;

	// Optional (language adapter providers)
	private final Map<String, String> languageAdapters;

	// Optional (custom values)
	private final Map<String, CustomValue> customValues;

	// old (v0 metadata)
	private final Collection<String> oldInitializers;

	ModMetadataImpl(int schemaVersion,
			String id, Version version, Collection<ProvidedModImpl> providedMods,
			ModEnvironment environment, ModLoadCondition loadCondition, String loadPhase,
			Map<String, List<EntrypointMetadata>> entrypoints, Collection<NestedJarEntry> jars,
			Collection<ConditionalConfigEntry> mixins, Collection<ConditionalConfigEntry> classTweakers,
			Collection<ModDependencyImpl> dependencies,
			/* @Nullable */ String name, /* @Nullable */String description,
			Collection<Person> authors, Collection<Person> contributors, /* @Nullable */ContactInformation contact, Collection<String> license, IconEntry icon,
			Map<String, String> languageAdapters,
			Map<String, CustomValue> customValues,
			Collection<String> oldInitializers) {
		this.schemaVersion = schemaVersion;
		this.id = id;
		this.version = version;
		this.providedMods = unmodifiable(providedMods);
		this.environment = environment;
		this.loadCondition = loadCondition;
		this.loadPhase = loadPhase;
		this.entrypoints = unmodifiable(entrypoints);
		this.jars = unmodifiable(jars);
		this.mixins = unmodifiable(mixins);
		this.classTweakers = unmodifiable(classTweakers);
		this.dependencies = unmodifiable(dependencies);
		this.name = name;

		// Empty description if not specified
		if (description != null) {
			this.description = description;
		} else {
			this.description = "";
		}

		this.authors = unmodifiable(authors);
		this.contributors = unmodifiable(contributors);

		if (contact != null) {
			this.contact = contact;
		} else {
			this.contact = ContactInformation.EMPTY;
		}

		this.license = unmodifiable(license);

		if (icon != null) {
			this.icon = icon;
		} else {
			this.icon = NO_ICON;
		}

		this.languageAdapters = unmodifiable(languageAdapters);
		this.customValues = unmodifiable(customValues);

		this.oldInitializers = unmodifiable(oldInitializers);
	}

	private static <T> Collection<T> unmodifiable(Collection<? extends T> c) {
		return c.isEmpty() ? Collections.emptyList() : Collections.unmodifiableCollection(c);
	}

	private static <K, V> Map<K, V> unmodifiable(Map<? extends K, ? extends V> m) {
		return m.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(m);
	}

	@Override
	public int getSchemaVersion() {
		return schemaVersion;
	}

	@Override
	public String getType() {
		return TYPE_FABRIC_MOD; // Fabric Mod
	}

	@Override
	public String getId() {
		return this.id;
	}

	@Override
	public Collection<? extends ProvidedMod> getAdditionallyProvidedMods() {
		return providedMods;
	}

	@Override
	public Version getVersion() {
		return this.version;
	}

	@Override
	public void setVersion(Version version) {
		this.version = version;

		for (ProvidedModImpl m : providedMods) {
			if (!m.hasOwnVersion) m.setVersion(version);
		}
	}

	@Override
	public ModEnvironment getEnvironment() {
		return this.environment;
	}

	@Override
	public boolean loadsInEnvironment(EnvType type) {
		return this.environment.matches(type);
	}

	@Override
	public ModLoadCondition getLoadCondition() {
		return loadCondition;
	}

	@Override
	public String getLoadPhase() {
		return loadPhase;
	}

	@Override
	public Collection<ModDependencyImpl> getDependencies() {
		return dependencies;
	}

	@Override
	public void setDependencies(Collection<ModDependencyImpl> dependencies) {
		this.dependencies = Collections.unmodifiableCollection(dependencies);
	}

	// General metadata

	@Override
	public String getName() {
		if (this.name == null || this.name.isEmpty()) {
			return this.id;
		}

		return this.name;
	}

	@Override
	public String getDescription() {
		return this.description;
	}

	@Override
	public Collection<Person> getAuthors() {
		return this.authors;
	}

	@Override
	public Collection<Person> getContributors() {
		return this.contributors;
	}

	@Override
	public ContactInformation getContact() {
		return this.contact;
	}

	@Override
	public Collection<String> getLicense() {
		return this.license;
	}

	@Override
	public Optional<String> getIconPath(int size) {
		return this.icon.getIconPath(size);
	}

	@Override
	public Map<String, CustomValue> getCustomValues() {
		return this.customValues;
	}

	// Internal stuff

	@Override
	public Map<String, String> getLanguageAdapterDefinitions() {
		return this.languageAdapters;
	}

	@Override
	public Collection<NestedJarEntry> getJars() {
		return this.jars;
	}

	@Override
	public Collection<String> getMixinConfigs(EnvType env, Map<String, DynamicFunction> expressionFunctions) {
		return processConditionalConfigs("mixin", mixins, env, expressionFunctions);
	}

	@Override
	public Collection<String> getClassTweakers(EnvType env, Map<String, DynamicFunction> expressionFunctions) {
		return processConditionalConfigs("class tweaker", classTweakers, env, expressionFunctions);
	}

	private Collection<String> processConditionalConfigs(String name, Collection<ConditionalConfigEntry> entries,
			EnvType type, Map<String, DynamicFunction> expressionFunctions) {
		final List<String> ret = new ArrayList<>();

		// This is only ever called once, so no need to store the result of this.
		for (ConditionalConfigEntry entry : entries) {
			try {
				if (entry.environment.matches(type)
						&& (entry.condition == null || entry.condition.evaluateBoolean(expressionFunctions))) {
					ret.add(entry.config);
				}
			} catch (ExpressionEvaluateException e) {
				throw new FormattedException(StringUtil.capitalize(name)+" config condition evaluation failed",
						"The mod "+getId()+" supplied a "+name+" config condition that couldn't be evaluated",
						e);
			}
		}

		return ret;
	}

	@Override
	public Collection<String> getOldInitializers() {
		return oldInitializers;
	}

	@Override
	public List<EntrypointMetadata> getEntrypoints(String type) {
		if (type == null) {
			return Collections.emptyList();
		}

		final List<EntrypointMetadata> entrypoints = this.entrypoints.get(type);

		if (entrypoints != null) {
			return entrypoints;
		}

		return Collections.emptyList();
	}

	@Override
	public Collection<String> getEntrypointKeys() {
		return this.entrypoints.keySet();
	}

	@Override
	public String toString() {
		return String.format("%s %s", id, version);
	}

	static final class ProvidedModImpl implements ProvidedMod {
		private final String id;
		private Version version;
		final boolean hasOwnVersion;
		private final boolean exclusive;

		ProvidedModImpl(String id, Version version, boolean hasOwnVersion, boolean exclusive) {
			this.id = id;
			this.version = version;
			this.hasOwnVersion = hasOwnVersion;
			this.exclusive = exclusive;
		}

		@Override
		public String getId() {
			return id;
		}

		@Override
		public Version getVersion() {
			return version;
		}

		void setVersion(Version version) {
			this.version = version;
		}

		@Override
		public boolean isExclusive() {
			return exclusive;
		}

		@Override
		public String toString() {
			return String.format("%s %s (%s%s)",
					id,
					version,
					(hasOwnVersion ? "" : "inherited, "),
					(exclusive ? "exclusive" : "shared"));
		}
	}

	static final class EntrypointMetadataImpl implements EntrypointMetadata {
		private final String adapter;
		private final String value;
		private final Expression condition;

		EntrypointMetadataImpl(String adapter, String value, Expression condition) {
			this.adapter = adapter;
			this.value = value;
			this.condition = condition;
		}

		@Override
		public String getAdapter() {
			return this.adapter;
		}

		@Override
		public String getValue() {
			return this.value;
		}

		public Expression getCondition() {
			return condition;
		}
	}

	static final class NestedJarEntryImpl implements NestedJarEntry {
		private final String file;

		NestedJarEntryImpl(String file) {
			this.file = file;
		}

		@Override
		public String getFile() {
			return this.file;
		}
	}

	static final class ConditionalConfigEntry {
		final String config;
		final ModEnvironment environment;
		final Expression condition;

		ConditionalConfigEntry(String config, ModEnvironment environment, Expression condition) {
			this.config = config;
			this.environment = environment;
			this.condition = condition;
		}
	}

	interface IconEntry {
		Optional<String> getIconPath(int size);
	}

	static final class SingleIconEntry implements IconEntry {
		private final String icon;

		SingleIconEntry(String icon) {
			this.icon = icon;
		}

		@Override
		public Optional<String> getIconPath(int size) {
			return Optional.of(this.icon);
		}
	}

	static final class MapIconEntry implements IconEntry {
		private final NavigableMap<Integer, String> icons;

		MapIconEntry(NavigableMap<Integer, String> icons) {
			this.icons = icons;
		}

		@Override
		public Optional<String> getIconPath(int size) {
			if (icons.isEmpty()) return Optional.empty();

			Map.Entry<Integer, String> entry = icons.ceilingEntry(size);
			if (entry == null) entry = icons.lastEntry();

			return Optional.of(entry.getValue());
		}
	}
}
