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

package net.fabricmc.loader.api.extension;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModMetadata;

public interface LoaderExtensionApi { // one instance per extension, binding the caller mod id
	void addPathToCacheKey(Path path);
	void setExternalModSource(); // referenced loader extension must run every time, even if all cache keys match

	ModCandidate readMod(Path path, /*@Nullable*/ String namespace);
	ModCandidate readMod(List<Path> paths, /*@Nullable*/ String namespace);
	ModCandidate createMod(List<Path> paths, ModMetadata metadata, Collection<ModCandidate> nestedMods);

	Collection<ModCandidate> getMods(String modId);
	Collection<ModCandidate> getMods();
	boolean addMod(ModCandidate mod);
	boolean removeMod(ModCandidate mod);

	void addModSource(Function<ModDependency, ModCandidate> source);

	void addToClassPath(Path path);
	// TODO: add a way to add virtual resources (name + content) and classes

	void addMixinConfig(ModCandidate mod, String location);

	// TODO: resource transformers
}
