package net.fabricmc.loader.api.plugin;

/**
 * Entrypoint for loader plugins that need to influence other existing loader plugins.
 *
 *  <p>This is intended for tasks like mod set synchronization or updating.
 */
@FunctionalInterface
public interface EarlyLoaderPluginEntrypoint {
	void initPlugin(LoaderPluginApi api);
}
