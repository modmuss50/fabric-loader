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

import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.ModMetadata;

/**
 * Representation of a mod that might get chosen to be loaded.
 *
 * <p>The data exposed here is read only, mutating it is not supported!
 */
public interface ModCandidate {
	ModMetadata getMetadata();
	String getId();
	Version getVersion();

	boolean hasPath();
	List<Path> getPaths();
	String getLocalPath();

	boolean isRoot();
	Collection<? extends ModCandidate> getContainingMods();
	Collection<? extends ModCandidate> getContainedMods();
}
