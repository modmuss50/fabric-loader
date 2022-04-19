package net.fabricmc.loader.impl;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.tree.ClassNode;

import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.plugin.LoaderPluginApi;
import net.fabricmc.loader.api.plugin.ModCandidate;
import net.fabricmc.loader.impl.discovery.ModCandidateImpl;
import net.fabricmc.loader.impl.discovery.ModDiscoverer;
import net.fabricmc.loader.impl.discovery.ModResolver.ResolutionContext;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;

public final class LoaderPluginApiImpl implements LoaderPluginApi {
	static final List<Function<ModDependency, ModCandidate>> modSources = new ArrayList<>(); // TODO: use this
	static final List<MixinConfigEntry> mixinConfigs = new ArrayList<>();
	// TODO: use these:
	static final List<TransformerEntry<ByteBuffer>> byteBufferTransformers = new ArrayList<>();
	static final List<TransformerEntry<ClassVisitor>> classVisitorProviders = new ArrayList<>();
	static final List<TransformerEntry<ClassNode>> classNodeTransformers = new ArrayList<>();

	private final String pluginModId;
	private final ResolutionContext context;

	public LoaderPluginApiImpl(String pluginModId, ResolutionContext context) {
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
	public ModCandidate readMod(Path path) {
		Objects.requireNonNull(path, "null path");

		return readMod(Collections.singletonList(path));
	}

	@Override
	public ModCandidate readMod(List<Path> paths) {
		checkFrozen();
		if (paths.isEmpty()) throw new IllegalArgumentException("empty paths");

		ModDiscoverer discoverer = FabricLoaderImpl.INSTANCE.getDiscoverer();
		if (discoverer == null) throw new IllegalStateException("createMod is only available during mod discovery");

		return discoverer.scan(normalizePaths(paths), false);
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
		} else { // TODO: wrap other types
			throw new IllegalArgumentException("invalid ModMetadata class: "+metadata.getClass());
		}

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
		return addMod(mod, true);
	}

	@Override
	public boolean addMod(ModCandidate mod, boolean includeNested) {
		checkFrozen();
		Objects.requireNonNull(mod, "null mod");
		if (!(mod instanceof ModCandidateImpl)) throw new IllegalArgumentException("invalid ModCandidate class: "+mod.getClass());

		if (!context.addMod((ModCandidateImpl) mod)) return false;

		if (includeNested) {
			for (ModCandidate m : mod.getContainedMods()) {
				addMod(m, true);
			}
		}

		return true;
	}

	@Override
	public boolean removeMod(String modId) {
		checkFrozen();
		Objects.requireNonNull(modId, "null modId");

		// TODO Auto-generated method stub
		return false;
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

	@Override
	public void addClassByteBufferTransformer(ClassTransformer<ByteBuffer> transformer, TransformPhase phase) {
		checkFrozen();
		Objects.requireNonNull(transformer, "null transformer");
		Objects.requireNonNull(phase, "null phase");
		if (transformer.getName().isEmpty()) throw new IllegalArgumentException("transformer without name");

		byteBufferTransformers.add(new TransformerEntry<>(pluginModId, phase, transformer));
	}

	@Override
	public void addClassVisitorProvider(ClassTransformer<ClassVisitor> provider, TransformPhase phase) {
		checkFrozen();
		Objects.requireNonNull(provider, "null provider");
		Objects.requireNonNull(phase, "null phase");
		if (provider.getName().isEmpty()) throw new IllegalArgumentException("provider without name");

		classVisitorProviders.add(new TransformerEntry<>(pluginModId, phase, provider));
	}

	@Override
	public void addClassNodeTransformer(ClassTransformer<ClassNode> transformer, TransformPhase phase) {
		checkFrozen();
		Objects.requireNonNull(transformer, "null transformer");
		Objects.requireNonNull(phase, "null phase");
		if (transformer.getName().isEmpty()) throw new IllegalArgumentException("transformer without name");

		classNodeTransformers.add(new TransformerEntry<>(pluginModId, phase, transformer));
	}

	private static void checkFrozen() {
		if (FabricLoaderImpl.INSTANCE.isFrozen()) throw new IllegalStateException("loading progress advanced beyond where loader plugins may act");
	}

	public static List<MixinConfigEntry> getMixinConfigs() {
		return mixinConfigs;
	}

	public static final class MixinConfigEntry {
		public final String pluginModId;
		public final String modId;
		public final String location;

		MixinConfigEntry(String pluginModId, String modId, String location) {
			this.pluginModId = pluginModId;
			this.modId = modId;
			this.location = location;
		}
	}

	static final class TransformerEntry<T> {
		final String pluginModId;
		final TransformPhase phase;
		final ClassTransformer<T> transformer;

		TransformerEntry(String pluginModId, TransformPhase phase, ClassTransformer<T> transformer) {
			this.pluginModId = pluginModId;
			this.phase = phase;
			this.transformer = transformer;
		}
	}
}
