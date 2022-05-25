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

package net.fabricmc.loader.impl.discovery;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import net.fabricmc.accesswidener.AccessWidenerReader;
import net.fabricmc.accesswidener.AccessWidenerRemapper;
import net.fabricmc.accesswidener.AccessWidenerWriter;
import net.fabricmc.loader.impl.FormattedException;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.util.FileSystemUtil;
import net.fabricmc.loader.impl.util.SystemProperties;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;
import net.fabricmc.loader.impl.util.mappings.TinyRemapperMappingsHelper;
import net.fabricmc.tinyremapper.InputTag;
import net.fabricmc.tinyremapper.NonClassCopyMode;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.OutputConsumerPath.ResourceRemapper;
import net.fabricmc.tinyremapper.TinyRemapper;

public final class RuntimeModRemapper {
	public static void remap(Collection<ModCandidateImpl> modCandidates, Collection<ModCandidateImpl> cpMods, Path tmpDir, Path outputDir) {
		Set<ModCandidateImpl> modsToRemap = new HashSet<>();

		for (ModCandidateImpl mod : modCandidates) {
			if (mod.getRequiresRemap()) {
				modsToRemap.add(mod);
			}
		}

		if (modsToRemap.isEmpty()) return;

		FabricLauncher launcher = FabricLauncherBase.getLauncher();

		TinyRemapper remapper = TinyRemapper.newRemapper()
				.withMappings(TinyRemapperMappingsHelper.create(launcher.getMappingConfiguration().getMappings(), "intermediary", launcher.getTargetNamespace()))
				.renameInvalidLocals(false)
				.build();

		try {
			remapper.readClassPathAsync(getRemapClasspath().toArray(new Path[0]));
		} catch (IOException e) {
			throw new RuntimeException("Failed to populate remap classpath", e);
		}

		Map<ModCandidateImpl, RemapInfo> infoMap = new HashMap<>();

		try {
			// gather inputs and class path

			for (ModCandidateImpl mod : cpMods) {
				RemapInfo info = new RemapInfo();
				infoMap.put(mod, info);

				if (mod.hasPath()) {
					info.inputPaths = mod.getPaths();
				} else {
					info.inputPaths = Collections.singletonList(mod.copyToDir(tmpDir, true));
					info.inputIsTemp = true;
				}

				if (modsToRemap.contains(mod)) {
					InputTag tag = remapper.createInputTag();
					info.tag = tag;

					info.outputPath = outputDir.resolve(mod.getDefaultFileName());
					Files.deleteIfExists(info.outputPath);

					remapper.readInputsAsync(tag, info.inputPaths.toArray(new Path[0]));
				} else {
					remapper.readClassPathAsync(info.inputPaths.toArray(new Path[0]));
				}
			}

			// copy non-classes, remap AWs, apply remapping

			for (ModCandidateImpl mod : modsToRemap) {
				RemapInfo info = infoMap.get(mod);
				List<ResourceRemapper> resourceRemappers = NonClassCopyMode.FIX_META_INF.remappers;

				// aw remapping
				ResourceRemapper awRemapper = createAccessWidenerRemapper(mod);

				if (awRemapper != null) {
					resourceRemappers = new ArrayList<>(resourceRemappers);
					resourceRemappers.add(awRemapper);
				}

				try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(info.outputPath).build()) {
					for (Path path : info.inputPaths) {
						FileSystemUtil.FileSystemDelegate delegate = FileSystemUtil.getJarFileSystem(path, false); // TODO: close properly

						if (delegate.get() == null) {
							throw new RuntimeException("Could not open JAR file " + path + " for NIO reading!");
						}

						Path inputJar = delegate.get().getRootDirectories().iterator().next();
						outputConsumer.addNonClassFiles(inputJar, remapper, resourceRemappers);
					}

					remapper.apply(outputConsumer, info.tag);
				}
			}

			remapper.finish();

			// update paths

			for (ModCandidateImpl mod : modsToRemap) {
				RemapInfo info = infoMap.get(mod);

				mod.setPaths(Collections.singletonList(info.outputPath));
			}
		} catch (Throwable t) {
			remapper.finish();

			for (RemapInfo info : infoMap.values()) {
				if (info.outputPath == null) {
					continue;
				}

				try {
					Files.deleteIfExists(info.outputPath);
				} catch (IOException e) {
					Log.warn(LogCategory.MOD_REMAP, "Error deleting failed output jar %s", info.outputPath, e);
				}
			}

			throw new FormattedException("Failed to remap mods!", t);
		} finally {
			for (RemapInfo info : infoMap.values()) {
				try {
					if (info.inputIsTemp) {
						for (Path path : info.inputPaths) {
							Files.deleteIfExists(path);
						}
					}
				} catch (IOException e) {
					Log.warn(LogCategory.MOD_REMAP, "Error deleting temporary input jar %s", info.inputIsTemp, e);
				}
			}
		}
	}

	private static ResourceRemapper createAccessWidenerRemapper(ModCandidateImpl mod) {
		String accessWidener = mod.getMetadata().getAccessWidener();
		if (accessWidener == null) return null;

		return new ResourceRemapper() {
			@Override
			public boolean canTransform(TinyRemapper remapper, Path relativePath) {
				return relativePath.toString().equals(accessWidener);
			}

			@Override
			public void transform(Path destinationDirectory, Path relativePath, InputStream input, TinyRemapper remapper) throws IOException {
				AccessWidenerWriter writer = new AccessWidenerWriter();
				AccessWidenerRemapper remappingDecorator = new AccessWidenerRemapper(writer, remapper.getEnvironment().getRemapper(), "intermediary", "named");
				AccessWidenerReader accessWidenerReader = new AccessWidenerReader(remappingDecorator);
				accessWidenerReader.read(new BufferedReader(new InputStreamReader(input, AccessWidenerReader.ENCODING)), "intermediary");

				Files.write(destinationDirectory.resolve(relativePath.toString()), writer.write());
			}
		};
	}

	private static List<Path> getRemapClasspath() throws IOException {
		String remapClasspathFile = System.getProperty(SystemProperties.REMAP_CLASSPATH_FILE);

		if (remapClasspathFile == null) {
			throw new RuntimeException("No remapClasspathFile provided");
		}

		String content = new String(Files.readAllBytes(Paths.get(remapClasspathFile)), StandardCharsets.UTF_8);

		return Arrays.stream(content.split(File.pathSeparator))
				.map(Paths::get)
				.collect(Collectors.toList());
	}

	private static class RemapInfo {
		InputTag tag;
		List<Path> inputPaths;
		Path outputPath;
		boolean inputIsTemp;
	}
}
