package net.fabricmc.loader.impl.discovery;

import net.fabricmc.loader.impl.util.PhaseSorting;

public final class LoadPhases {
	public static final String BUILTIN = "builtin";
	public static final String DEFAULT = "default";

	public static void setDefaultOrder(PhaseSorting<String, ?> sorting) {
		sorting.addPhaseOrdering(BUILTIN, DEFAULT);
	}
}
