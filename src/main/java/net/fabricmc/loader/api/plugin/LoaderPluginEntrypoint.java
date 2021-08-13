package net.fabricmc.loader.api.plugin;

@FunctionalInterface
public interface LoaderPluginEntrypoint {
	void initPlugin(LoaderPluginApi api);
}
