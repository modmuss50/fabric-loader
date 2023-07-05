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

package net.fabricmc.loader.impl;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import net.fabricmc.loader.api.extension.LoaderExtensionApi;
import net.fabricmc.loader.api.extension.ModCandidate;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.impl.discovery.ModCandidateImpl;
import net.fabricmc.loader.impl.discovery.ModDiscoverer;
import net.fabricmc.loader.impl.discovery.ModResolver.ResolutionContext;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.fabricmc.loader.impl.metadata.ModMetadataBuilderImpl;

public final class LoaderExtensionApiImpl implements LoaderExtensionApi {
	static final List<Function<ModDependency, ModCandidate>> modSources = new ArrayList<>(); // TODO: use this
	static final List<MixinConfigEntry> mixinConfigs = new ArrayList<>();

	private final String pluginModId;
	private final ResolutionContext context;

	public LoaderExtensionApiImpl(String pluginModId, ResolutionContext context) {
		this.pluginModId = pluginModId;
		this.context = context;
	}

	@Override
	public void addPathToCacheKey(Path path) {
		checkFrozen();
		Objects.requireNonNull(path, "null path");

		// TODO Auto-generated method stub
	}

	@Override
	public void setExternalModSource() {
		checkFrozen();

		// TODO Auto-generated method stub
	}

	@Override
	public ModCandidate readMod(Path path, /*@Nullable*/ String namespace) {
		Objects.requireNonNull(path, "null path");

		return readMod(Collections.singletonList(path), namespace);
	}

	@Override
	public ModCandidate readMod(List<Path> paths, /*@Nullable*/ String namespace) {
		checkFrozen();
		if (paths.isEmpty()) throw new IllegalArgumentException("empty paths");

		ModDiscoverer discoverer = FabricLoaderImpl.INSTANCE.getDiscoverer();
		if (discoverer == null) throw new IllegalStateException("createMod is only available during mod discovery");

		boolean remap = namespace != null && !namespace.equals(FabricLauncherBase.getLauncher().getMappingConfiguration().getRuntimeNamespace());

		return discoverer.scan(normalizePaths(paths), remap);
	}

	@Override
	public ModCandidate createMod(List<Path> paths, ModMetadata metadata, Collection<ModCandidate> nestedMods) {
		checkFrozen();
		if (paths.isEmpty()) throw new IllegalArgumentException("empty paths");
		Objects.requireNonNull(metadata, "null metadata");
		Objects.requireNonNull(nestedMods, "null nestedMods");

		LoaderModMetadata loaderMeta;

		if (metadata instanceof LoaderModMetadata) {
			loaderMeta = (LoaderModMetadata) metadata;
		} else if (metadata instanceof ModMetadataBuilderImpl) {
			loaderMeta = ((ModMetadataBuilderImpl) metadata).build();
		} else { // TODO: wrap other types
			throw new IllegalArgumentException("invalid ModMetadata class: "+metadata.getClass());
		}

		loaderMeta.applyEnvironment(context.envType, context.expressionFunctions);

		Collection<ModCandidateImpl> nestedModsCopy;

		if (nestedMods.isEmpty()) {
			nestedModsCopy = Collections.emptyList();
		} else {
			nestedModsCopy = new ArrayList<>(nestedMods.size());

			for (ModCandidate mod : nestedMods) {
				if (!(mod instanceof ModCandidateImpl)) throw new IllegalArgumentException("invalid ModCandidate class: "+mod.getClass());
				nestedModsCopy.add((ModCandidateImpl) mod);
			}
		}

		return ModCandidateImpl.createPlain(normalizePaths(paths), loaderMeta, false, nestedModsCopy);
	}

	private static List<Path> normalizePaths(List<Path> paths) {
		List<Path> ret = new ArrayList<>(paths.size());

		for (Path p : paths) {
			ret.add(p.toAbsolutePath().normalize());
		}

		return ret;
	}

	@Override
	public Collection<ModCandidate> getMods(String modId) {
		checkFrozen();
		Objects.requireNonNull(modId, "null modId");

		return Collections.unmodifiableCollection(context.getMods(modId));
	}

	@Override
	public Collection<ModCandidate> getMods() {
		checkFrozen();

		return Collections.unmodifiableCollection(context.getMods());
	}

	@Override
	public boolean addMod(ModCandidate mod) {
		checkFrozen();
		Objects.requireNonNull(mod, "null mod");
		if (!(mod instanceof ModCandidateImpl)) throw new IllegalArgumentException("invalid ModCandidate class: "+mod.getClass());

		if (!context.addMod((ModCandidateImpl) mod)) return false;

		for (ModCandidate m : mod.getContainedMods()) {
			addMod(m);
		}

		return true;
	}

	@Override
	public boolean removeMod(ModCandidate mod) {
		checkFrozen();
		Objects.requireNonNull(mod, "null mod");
		if (!(mod instanceof ModCandidateImpl)) throw new IllegalArgumentException("invalid ModCandidate class: "+mod.getClass());

		return context.removeMod((ModCandidateImpl) mod);
	}

	@Override
	public void addModSource(Function<ModDependency, ModCandidate> source) {
		checkFrozen();
		Objects.requireNonNull(source, "null source");

		modSources.add(source);
	}

	@Override
	public void addToClassPath(Path path) {
		checkFrozen();
		Objects.requireNonNull(path, "null path");

		FabricLauncherBase.getLauncher().addToClassPath(path);
	}

	@Override
	public void addMixinConfig(ModCandidate mod, String location) {
		checkFrozen();
		Objects.requireNonNull(mod, "null mod");
		Objects.requireNonNull(location, "null location");

		mixinConfigs.add(new MixinConfigEntry(pluginModId, mod.getId(), location));
	}

	private static void checkFrozen() {
		if (FabricLoaderImpl.INSTANCE.isFrozen()) throw new IllegalStateException("loading progress advanced beyond where loader plugins may act");
	}

	public static List<MixinConfigEntry> getMixinConfigs() {
		return mixinConfigs;
	}

	public static final class MixinConfigEntry {
		public final String extensionModId;
		public final String modId;
		public final String location;

		MixinConfigEntry(String extensionModId, String modId, String location) {
			this.extensionModId = extensionModId;
			this.modId = modId;
			this.location = location;
		}
	}
}
