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

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.ObjectShare;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.FormattedException;
import net.fabricmc.loader.impl.game.GameProvider;
import net.fabricmc.loader.impl.game.GameProviderHelper;
import net.fabricmc.loader.impl.game.minecraft.LibClassifier.Lib;
import net.fabricmc.loader.impl.game.minecraft.patch.BrandingPatch;
import net.fabricmc.loader.impl.game.minecraft.patch.EntrypointPatch;
import net.fabricmc.loader.impl.game.minecraft.patch.EntrypointPatchFML125;
import net.fabricmc.loader.impl.game.patch.GameTransformer;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.metadata.BuiltinModMetadata;
import net.fabricmc.loader.impl.metadata.ModDependencyImpl;
import net.fabricmc.loader.impl.util.Arguments;
import net.fabricmc.loader.impl.util.ExceptionUtil;
import net.fabricmc.loader.impl.util.LoaderUtil;
import net.fabricmc.loader.impl.util.SystemProperties;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogHandler;

public class MinecraftGameProvider implements GameProvider {
	private static final String[] ALLOWED_EARLY_CLASS_PREFIXES = { "org.apache.logging.log4j.", "com.mojang.util." };

	private static final Set<String> SENSITIVE_ARGS = new HashSet<>(Arrays.asList(
			// all lowercase without --
			"accesstoken",
			"clientid",
			"profileproperties",
			"proxypass",
			"proxyuser",
			"username",
			"userproperties",
			"uuid",
			"xuid"));

	private EnvType envType;
	private String entrypoint;
	private Map<EnvType, String> entrypointMap;
	private Arguments arguments;
	private Map<EnvType, Path> gameJars;
	private Path realmsJar;
	private final Set<Path> logJars = new HashSet<>();
	private boolean log4jAvailable;
	private boolean slf4jAvailable;
	private final List<Path> miscGameLibraries = new ArrayList<>(); // libraries not relevant for loader's uses
	private McVersion versionData;
	private Path loggingGameJar;
	private boolean hasModLoader = false;

	private static final GameTransformer TRANSFORMER = new GameTransformer(
			new EntrypointPatch(),
			new BrandingPatch(),
			new EntrypointPatchFML125());

	@Override
	public String getGameId() {
		return "minecraft";
	}

	@Override
	public String getGameName() {
		return "Minecraft";
	}

	@Override
	public String getRawGameVersion() {
		return versionData.getRaw();
	}

	@Override
	public String getNormalizedGameVersion() {
		return versionData.getNormalized();
	}

	@Override
	public Collection<BuiltinMod> getBuiltinMods() {
		BuiltinModMetadata.Builder metadata = new BuiltinModMetadata.Builder(getGameId(), getNormalizedGameVersion())
				.setName(getGameName());

		if (versionData.getClassVersion().isPresent()) {
			int version = versionData.getClassVersion().getAsInt() - 44;

			try {
				metadata.addDependency(new ModDependencyImpl(ModDependency.Kind.DEPENDS, "java", Collections.singletonList(String.format(">=%d", version))));
			} catch (VersionParsingException e) {
				throw new RuntimeException(e);
			}
		}

		return Collections.singletonList(new BuiltinMod(getGameJars(), metadata.build()));
	}

	public List<Path> getGameJars() {
		return new ArrayList<>(gameJars.values());
	}

	@Override
	public String getEntrypoint() {
		return entrypoint;
	}

	@Override
	public Path getLaunchDirectory() {
		if (arguments == null) {
			return Paths.get(".");
		}

		return getLaunchDirectory(arguments);
	}

	@Override
	public boolean isObfuscated() {
		return true; // generally yes...
	}

	@Override
	public boolean requiresUrlClassLoader() {
		return hasModLoader;
	}

	@Override
	public boolean isEnabled() {
		return System.getProperty(SystemProperties.SKIP_MC_PROVIDER) == null;
	}

	@Override
	public boolean locateGame(FabricLauncher launcher, String[] args) {
		this.envType = launcher.getEnvironmentType();
		this.arguments = new Arguments();
		arguments.parse(args);

		Path envGameJar;

		try {
			String[] gameJarProperty = System.getProperty(SystemProperties.GAME_JAR_PATH).split(",");
			List<Path> lookupPaths;

			if (gameJarProperty.length > 0) {
				lookupPaths = new ArrayList<>();

				for (String gameJarPath : gameJarProperty) {
					Path path = Paths.get(gameJarPath).toAbsolutePath().normalize();
					if (!Files.exists(path)) throw new RuntimeException("Game jar "+path+" configured through "+SystemProperties.GAME_JAR_PATH+" system property doesn't exist");

					lookupPaths.add(path);
				}

				lookupPaths.addAll(launcher.getClassPath());
			} else {
				lookupPaths = launcher.getClassPath();
			}

			LibClassifier classifier = new LibClassifier();
			classifier.process(lookupPaths, envType);

			if (envType == EnvType.CLIENT) {
				// Go looking for the server/common jar as well.
				classifier.process(lookupPaths, EnvType.SERVER);
			}

			if (classifier.has(Lib.MC_BUNDLER)) {
				BundlerProcessor.process(classifier);
			}

			Path clientJar = classifier.getOrigin(Lib.MC_CLIENT);

			gameJars = new HashMap<>();
			entrypointMap = new HashMap<>();

			if (clientJar != null) {
				gameJars.put(EnvType.CLIENT, clientJar);
				entrypointMap.put(EnvType.CLIENT, classifier.getClassName(Lib.MC_CLIENT));
			}

			Path serverJar = classifier.getOrigin(Lib.MC_SERVER);

			if (serverJar != null) {
				gameJars.put(EnvType.SERVER, serverJar);
				entrypointMap.put(EnvType.SERVER, classifier.getClassName(Lib.MC_SERVER));
			}

			assert gameJarProperty.length <= 0 || gameJarProperty.length == gameJars.size();

			envGameJar = gameJars.get(envType);
			if (envGameJar == null) return false;

			entrypoint = entrypointMap.get(envType);
			realmsJar = classifier.getOrigin(Lib.REALMS);

			for (Path gameJar : getGameJars()) {
				if (classifier.is(gameJar, Lib.LOGGING)) {
					loggingGameJar = gameJar;
					break;
				}
			}

			hasModLoader = classifier.has(Lib.MODLOADER);
			log4jAvailable = classifier.has(Lib.LOG4J_API) && classifier.has(Lib.LOG4J_CORE);
			slf4jAvailable = classifier.has(Lib.SLF4J_API) && classifier.has(Lib.SLF4J_CORE);

			for (Lib lib : Lib.LOGGING) {
				Path path = classifier.getOrigin(lib);

				if (path != null && !getGameJars().contains(path) && !lookupPaths.contains(path)) {
					logJars.add(path);
				}
			}

			for (Path path : classifier.getUnmatchedOrigins()) {
				if (!lookupPaths.contains(path)) miscGameLibraries.add(path);
			}
		} catch (IOException e) {
			throw ExceptionUtil.wrap(e);
		}

		if (loggingGameJar == null && logJars.isEmpty()) { // use Log4J/SLF4J log handler directly if it is not shaded into the game jar, otherwise delay it to initialize() after deobfuscation
			setupLogHandler(launcher, false);
		}

		// expose obfuscated jar locations for mods to more easily remap code from obfuscated to intermediary
		ObjectShare share = FabricLoaderImpl.INSTANCE.getObjectShare();
		share.put("fabric-loader:inputGameJars", gameJars); // TODO this is a breaking change
		if (realmsJar != null) share.put("fabric-loader:inputRealmsJar", realmsJar);

		String version = arguments.remove(Arguments.GAME_VERSION);
		if (version == null) version = System.getProperty(SystemProperties.GAME_VERSION);
		versionData = McVersionLookup.getVersion(envGameJar, entrypoint, version);

		processArgumentMap(arguments, envType);

		return true;
	}

	private static void processArgumentMap(Arguments argMap, EnvType envType) {
		switch (envType) {
		case CLIENT:
			if (!argMap.containsKey("accessToken")) {
				argMap.put("accessToken", "FabricMC");
			}

			if (!argMap.containsKey("version")) {
				argMap.put("version", "Fabric");
			}

			String versionType = "";

			if (argMap.containsKey("versionType") && !argMap.get("versionType").equalsIgnoreCase("release")) {
				versionType = argMap.get("versionType") + "/";
			}

			argMap.put("versionType", versionType + "Fabric");

			if (!argMap.containsKey("gameDir")) {
				argMap.put("gameDir", getLaunchDirectory(argMap).toAbsolutePath().normalize().toString());
			}

			break;
		case SERVER:
			argMap.remove("version");
			argMap.remove("gameDir");
			argMap.remove("assetsDir");
			break;
		}
	}

	private static Path getLaunchDirectory(Arguments argMap) {
		return Paths.get(argMap.getOrDefault("gameDir", "."));
	}

	@Override
	public void initialize(FabricLauncher launcher) {
		Map<String, Path> gameJars = new HashMap<>(2);
		List<String> names = new ArrayList<>();

		for (Map.Entry<EnvType, Path> entry : this.gameJars.entrySet()) {
			String name = entry.getKey().name().toLowerCase(Locale.ENGLISH);
			gameJars.put(name, entry.getValue());
			names.add(name);
		}

		if (realmsJar != null) {
			gameJars.put("realms", realmsJar);
		}

		if (isObfuscated()) {
			gameJars = GameProviderHelper.deobfuscate(gameJars,
					getGameId(), getNormalizedGameVersion(),
					getLaunchDirectory(),
					launcher);

			this.gameJars = new HashMap<>();

			for (String name : names) {
				this.gameJars.put(EnvType.valueOf(name.toUpperCase(Locale.ENGLISH)), gameJars.get(name));
			}

			realmsJar = gameJars.get("realms");
		}

		if (loggingGameJar != null || !logJars.isEmpty()) {
			if (loggingGameJar != null) {
				launcher.addToClassPath(loggingGameJar, ALLOWED_EARLY_CLASS_PREFIXES);
			}

			if (!logJars.isEmpty()) {
				for (Path jar : logJars) {
					launcher.addToClassPath(jar);
				}
			}

			setupLogHandler(launcher, true);
		}

		for (Map.Entry<String, Path> entry : gameJars.entrySet()) {
			EnvType envType = EnvType.valueOf(entry.getKey().toUpperCase(Locale.ENGLISH));
			TRANSFORMER.locateEntrypoints(entry.getValue(), envType, Objects.requireNonNull(entrypointMap.get(envType)));
		}
	}

	private void setupLogHandler(FabricLauncher launcher, boolean useTargetCl) {
		System.setProperty("log4j2.formatMsgNoLookups", "true"); // lookups are not used by mc and cause issues with older log4j2 versions

		try {
			final String logHandlerClsName;

			if (log4jAvailable) {
				logHandlerClsName = "net.fabricmc.loader.impl.game.minecraft.Log4jLogHandler";
			} else if (slf4jAvailable) {
				logHandlerClsName = "net.fabricmc.loader.impl.game.minecraft.Slf4jLogHandler";
			} else {
				return;
			}

			ClassLoader prevCl = Thread.currentThread().getContextClassLoader();
			Class<?> logHandlerCls;

			if (useTargetCl) {
				Thread.currentThread().setContextClassLoader(launcher.getTargetClassLoader());
				logHandlerCls = launcher.loadIntoTarget(logHandlerClsName);
			} else {
				logHandlerCls = Class.forName(logHandlerClsName);
			}

			Log.init((LogHandler) logHandlerCls.getConstructor().newInstance(), true);
			Thread.currentThread().setContextClassLoader(prevCl);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public Arguments getArguments() {
		return arguments;
	}

	@Override
	public String[] getLaunchArguments(boolean sanitize) {
		if (arguments == null) return new String[0];

		String[] ret = arguments.toArray();
		if (!sanitize) return ret;

		int writeIdx = 0;

		for (int i = 0; i < ret.length; i++) {
			String arg = ret[i];

			if (i + 1 < ret.length
					&& arg.startsWith("--")
					&& SENSITIVE_ARGS.contains(arg.substring(2).toLowerCase(Locale.ENGLISH))) {
				i++; // skip value
			} else {
				ret[writeIdx++] = arg;
			}
		}

		if (writeIdx < ret.length) ret = Arrays.copyOf(ret, writeIdx);

		return ret;
	}

	@Override
	public GameTransformer getEntrypointTransformer() {
		return TRANSFORMER;
	}

	@Override
	public boolean canOpenErrorGui() {
		if (arguments == null || envType == EnvType.CLIENT) {
			return true;
		}

		List<String> extras = arguments.getExtraArgs();
		return !extras.contains("nogui") && !extras.contains("--nogui");
	}

	@Override
	public boolean hasAwtSupport() {
		// MC always sets -XstartOnFirstThread for LWJGL
		return !LoaderUtil.hasMacOs();
	}

	@Override
	public void unlockClassPath(FabricLauncher launcher) {
		for (Path gameJar : getGameJars()) {
			if (gameJar == loggingGameJar) {
				launcher.setAllowedPrefixes(gameJar);
			} else {
				launcher.addToClassPath(gameJar);
			}
		}

		if (realmsJar != null) launcher.addToClassPath(realmsJar);

		for (Path lib : miscGameLibraries) {
			launcher.addToClassPath(lib);
		}
	}

	@Override
	public void launch(ClassLoader loader) {
		String targetClass = entrypoint;

		if (envType == EnvType.CLIENT && targetClass.contains("Applet")) {
			targetClass = "net.fabricmc.loader.impl.game.minecraft.applet.AppletMain";
		}

		try {
			Class<?> c = loader.loadClass(targetClass);
			Method m = c.getMethod("main", String[].class);
			m.invoke(null, (Object) arguments.toArray());
		} catch (InvocationTargetException e) {
			throw new FormattedException("Minecraft has crashed!", e.getCause());
		} catch (ReflectiveOperationException e) {
			throw new FormattedException("Failed to start Minecraft", e);
		}
	}
}
