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

package net.fabricmc.loader.impl.game.minecraft;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Objects;
import java.util.regex.Pattern;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.launch.MappingConfiguration;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;
import net.fabricmc.mappingio.MappedElementKind;
import net.fabricmc.mappingio.MappingVisitor;
import net.fabricmc.mappingio.adapter.ForwardingMappingVisitor;
import net.fabricmc.mappingio.adapter.MappingNsCompleter;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.format.ProGuardReader;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

public final class MojangMappingConfiguration implements MappingConfiguration {
	public static final String MAPPING_URL_SYS_KEY = "fabric.mojangMappingsUrl";

	//https://piston-data.mojang.com/v1/objects/f14771b764f943c154d3a6fcb47694477e328148/client.txt;

	private final MappingTree mappings;

	private MojangMappingConfiguration(MappingTree mappings) {
		this.mappings = Objects.requireNonNull(mappings);

		if (FabricLauncherBase.getLauncher().isDevelopment()) {
			throw new IllegalStateException();
		}
	}

	public static MojangMappingConfiguration create(MappingConfiguration intermediaryConfig, EnvType envType, Path gameDir, String mcVersion) {
		if (envType == EnvType.SERVER) {
			throw new UnsupportedOperationException("TODO: support server");
		}

		String url = System.getProperty(MAPPING_URL_SYS_KEY);
		Path mappingsCacheDir = gameDir.resolve(FabricLoaderImpl.CACHE_DIR_NAME)
				.resolve("mappings");
		Path mappingsPath = mappingsCacheDir
				.resolve(mcVersion + "." + envType.name() + ".txt");

		if (Files.notExists(mappingsPath)) {
			Log.info(LogCategory.GAME_REMAP, "Downloading mappings, this may take a few seconds...");

			try (BufferedInputStream in = new BufferedInputStream(new URL(url).openStream())) {
				Files.createDirectories(mappingsCacheDir);
				Files.copy(in, mappingsPath);
			} catch (IOException e) {
				throw new UncheckedIOException("Failed to download mappings", e);
			}
		}

		MemoryMappingTree mappings = new MemoryMappingTree();

		try (BufferedReader reader = Files.newBufferedReader(mappingsPath)) {
			// First we need to load in intermediary
			MappingNsCompleter nsCompleter = new MappingNsCompleter(mappings, Collections.singletonMap("named", "intermediary"), true);
			intermediaryConfig.getMappings().accept(nsCompleter);

			DstNameFilterMappingVisitor nameFilter = new DstNameFilterMappingVisitor(mappings);
			MappingSourceNsSwitch nsSwitch = new MappingSourceNsSwitch(nameFilter, "official");
			ProGuardReader.read(reader, "named", "official", nsSwitch);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to read mappings", e);
		}

		return new MojangMappingConfiguration(mappings);
	}

	public static boolean isAvailable() {
		return System.getProperty(MAPPING_URL_SYS_KEY) != null;
	}

	@Override
	public String getConfigurationName() {
		return "mojang";
	}

	@Override
	public MappingTree getMappings() {
		return mappings;
	}

	@Override
	public String getTargetNamespace() {
		return "named";
	}

	@Override
	public boolean requiresPackageAccessHack() {
		return false;
	}

	@Override
	public String getGameId() {
		return null;
	}

	@Override
	public String getGameVersion() {
		return null;
	}

	@Override
	public boolean matches(String gameId, String gameVersion) {
		return true;
	}

	private static class DstNameFilterMappingVisitor extends ForwardingMappingVisitor {
		private static final Pattern SYNTHETIC_NAME_PATTERN = Pattern.compile("^(access|this|val\\$this|lambda\\$.*)\\$[0-9]+$");

		DstNameFilterMappingVisitor(MappingVisitor next) {
			super(next);
		}

		@Override
		public void visitDstName(MappedElementKind targetKind, int namespace, String name) throws IOException {
			if ((targetKind == MappedElementKind.FIELD || targetKind == MappedElementKind.METHOD) && SYNTHETIC_NAME_PATTERN.matcher(name).matches()) {
				return;
			}

			super.visitDstName(targetKind, namespace, name);
		}
	}
}
