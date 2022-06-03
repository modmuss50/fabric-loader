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
import java.util.List;
import java.util.Map;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.Version;

/**
 * Internal variant of the ModMetadata interface.
 */
@SuppressWarnings("deprecation")
public interface LoaderModMetadata extends net.fabricmc.loader.metadata.LoaderModMetadata {
	int getSchemaVersion();

	String getLoadPhase();

	default String getOldStyleLanguageAdapter() {
		return "net.fabricmc.loader.language.JavaLanguageAdapter";
	}

	Map<String, String> getLanguageAdapterDefinitions();
	Collection<NestedJarEntry> getJars();
	Collection<String> getMixinConfigs(EnvType type);
	Collection<String> getClassTweakers();
	@Override
	boolean loadsInEnvironment(EnvType type);
	@Override
	Collection<ModDependencyImpl> getDependencies();

	Collection<String> getOldInitializers();
	@Override
	List<EntrypointMetadata> getEntrypoints(String type);
	@Override
	Collection<String> getEntrypointKeys();

	void setVersion(Version version);
	void setDependencies(Collection<ModDependencyImpl> dependencies);

	/**
	 * Adjust the metadata for the environment, stripping unsuitable deps.
	 */
	default void applyEnvironment(EnvType envType) {
		Collection<ModDependencyImpl> deps = getDependencies();
		boolean affected = false;

		for (ModDependencyImpl dep : deps) {
			if (!dep.appliesInEnvironment(envType)) {
				affected = true;
				break;
			}
		}

		if (!affected) return;

		List<ModDependencyImpl> newDeps = new ArrayList<>(deps.size() - 1);

		for (ModDependencyImpl dep : deps) {
			if (dep.appliesInEnvironment(envType)) {
				newDeps.add(dep);
			}
		}

		setDependencies(newDeps);
	}
}
