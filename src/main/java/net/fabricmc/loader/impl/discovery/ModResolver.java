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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

import org.sat4j.specs.ContradictionException;
import org.sat4j.specs.TimeoutException;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModDependency.Kind;
import net.fabricmc.loader.api.metadata.ProvidedMod;
import net.fabricmc.loader.impl.discovery.ModSolver.InactiveReason;
import net.fabricmc.loader.impl.metadata.ModDependencyImpl;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

public class ModResolver {
	public static List<ModCandidateImpl> resolve(Collection<ModCandidateImpl> candidates, EnvType envType, Map<String, Set<ModCandidateImpl>> envDisabledMods) throws ModResolutionException {
		long startTime = System.nanoTime();
		List<ModCandidateImpl> result = findCompatibleSet(candidates, envType, envDisabledMods);

		long endTime = System.nanoTime();
		Log.debug(LogCategory.RESOLUTION, "Mod resolution time: %.1f ms", (endTime - startTime) * 1e-6);

		return result;
	}

	private static List<ModCandidateImpl> findCompatibleSet(Collection<ModCandidateImpl> candidates, EnvType envType, Map<String, Set<ModCandidateImpl>> envDisabledMods) throws ModResolutionException {
		// sort all mods by priority

		List<ModCandidateImpl> allModsSorted = new ArrayList<>(candidates);

		allModsSorted.sort(modPrioComparator);

		// group/index all mods by id

		Map<String, List<ModCandidateImpl>> modsById = new LinkedHashMap<>(); // linked to ensure consistent execution

		for (ModCandidateImpl mod : allModsSorted) {
			modsById.computeIfAbsent(mod.getId(), ignore -> new ArrayList<>()).add(mod);

			for (ProvidedMod provided : mod.getAdditionallyProvidedMods()) {
				modsById.computeIfAbsent(provided.getId(), ignore -> new ArrayList<>()).add(mod);
			}
		}

		// soften positive deps from schema 0 or 1 mods on mods that are present but disabled for the current env
		// this is a workaround necessary due to many mods declaring deps that are unsatisfiable in some envs and loader before 0.12x not verifying them properly

		for (ModCandidateImpl mod : allModsSorted) {
			if (mod.getMetadata().getSchemaVersion() >= 2) continue;

			for (ModDependency dep : mod.getMetadata().getDependencies()) {
				if (!dep.getKind().isPositive() || dep.getKind() == Kind.SUGGESTS) continue; // no positive dep or already suggests
				if (!(dep instanceof ModDependencyImpl)) continue; // can't modify dep kind
				if (modsById.containsKey(dep.getModId())) continue; // non-disabled match available

				Collection<ModCandidateImpl> disabledMatches = envDisabledMods.get(dep.getModId());
				if (disabledMatches == null) continue; // no disabled id matches

				for (ModCandidateImpl m : disabledMatches) {
					if (dep.matches(m.getVersion())) { // disabled version match -> remove dep
						((ModDependencyImpl) dep).setKind(Kind.SUGGESTS);
						break;
					}
				}
			}
		}

		// preselect mods, check for builtin mod collisions

		List<ModCandidateImpl> preselectedMods = new ArrayList<>();

		for (List<ModCandidateImpl> mods : modsById.values()) {
			ModCandidateImpl builtinMod = null;

			for (ModCandidateImpl mod : mods) {
				if (mod.isBuiltin()) {
					builtinMod = mod;
					break;
				}
			}

			if (builtinMod == null) continue;

			if (mods.size() > 1) {
				mods.remove(builtinMod);
				throw new ModResolutionException("Mods share ID with builtin mod %s: %s", builtinMod, mods);
			}

			preselectedMods.add(builtinMod);
		}

		Map<String, ModCandidateImpl> selectedMods = new HashMap<>(allModsSorted.size());
		List<ModCandidateImpl> uniqueSelectedMods = new ArrayList<>(allModsSorted.size());

		for (ModCandidateImpl mod : preselectedMods) {
			preselectMod(mod, allModsSorted, modsById, selectedMods, uniqueSelectedMods);
		}

		// solve

		ModSolver.Result result;

		try {
			result = ModSolver.solve(allModsSorted, modsById,
					selectedMods, uniqueSelectedMods);
		} catch (ContradictionException | TimeoutException e) {
			throw new ModResolutionException("Solving failed", e);
		}

		if (!result.success) {
			Log.warn(LogCategory.RESOLUTION, "Mod resolution failed");
			Log.info(LogCategory.RESOLUTION, "Immediate reason: %s%n", result.immediateReason);
			Log.info(LogCategory.RESOLUTION, "Reason: %s%n", result.reason);
			if (!envDisabledMods.isEmpty()) Log.info(LogCategory.RESOLUTION, "%s environment disabled: %s%n", envType.name(), envDisabledMods.keySet());

			if (result.fix == null) {
				Log.info(LogCategory.RESOLUTION, "No fix?");
			} else {
				Log.info(LogCategory.RESOLUTION, "Fix: add %s, remove %s, replace [%s]%n",
						result.fix.modsToAdd,
						result.fix.modsToRemove,
						result.fix.modReplacements.entrySet().stream().map(e -> String.format("%s -> %s", e.getValue(), e.getKey())).collect(Collectors.joining(", ")));

				for (Collection<ModCandidateImpl> mods : envDisabledMods.values()) {
					for (ModCandidateImpl m : mods) {
						result.fix.inactiveMods.put(m, InactiveReason.WRONG_ENVIRONMENT);
					}
				}
			}

			throw new ModResolutionException("Mod resolution encountered an incompatible mod set!%s",
					ResultAnalyzer.gatherErrors(result, selectedMods, modsById, envDisabledMods, envType));
		}

		uniqueSelectedMods.sort(Comparator.comparing(ModCandidateImpl::getId));

		// clear cached data and inbound refs for unused mods, set minNestLevel for used non-root mods to max, queue root mods

		Queue<ModCandidateImpl> queue = new ArrayDeque<>();

		for (ModCandidateImpl mod : allModsSorted) {
			if (selectedMods.get(mod.getId()) == mod) { // mod is selected
				if (!mod.resetMinNestLevel()) { // -> is root
					queue.add(mod);
				}
			} else {
				mod.clearCachedData();

				for (ModCandidateImpl m : mod.getNestedMods()) {
					m.getParentMods().remove(mod);
				}

				for (ModCandidateImpl m : mod.getParentMods()) {
					m.getNestedMods().remove(mod);
				}
			}
		}

		// recompute minNestLevel (may have changed due to parent associations having been dropped by the above step)

		{
			ModCandidateImpl mod;

			while ((mod = queue.poll()) != null) {
				for (ModCandidateImpl child : mod.getNestedMods()) {
					if (child.updateMinNestLevel(mod)) {
						queue.add(child);
					}
				}
			}
		}

		String warnings = ResultAnalyzer.gatherWarnings(uniqueSelectedMods, selectedMods,
				envDisabledMods, envType);

		if (warnings != null) {
			Log.warn(LogCategory.RESOLUTION, "Warnings were found!%s", warnings);
		}

		return uniqueSelectedMods;
	}

	private static final Comparator<ModCandidateImpl> modPrioComparator = new Comparator<ModCandidateImpl>() {
		@Override
		public int compare(ModCandidateImpl a, ModCandidateImpl b) {
			// descending sort prio (less/earlier is higher prio):
			// root mods first, lower id first, higher version first, less nesting first, parent cmp

			if (a.isRoot()) {
				if (!b.isRoot()) {
					return -1; // only a is root
				}
			} else if (b.isRoot()) {
				return 1; // only b is root
			}

			// sort id desc
			int idCmp = a.getId().compareTo(b.getId());
			if (idCmp != 0) return idCmp;

			// sort version desc (lower version later)
			int versionCmp = b.getVersion().compareTo(a.getVersion());
			if (versionCmp != 0) return versionCmp;

			// sort nestLevel asc
			int nestCmp = a.getMinNestLevel() - b.getMinNestLevel(); // >0 if nest(a) > nest(b)
			if (nestCmp != 0) return nestCmp;

			if (a.isRoot()) return 0; // both root

			List<ModCandidateImpl> parents = new ArrayList<>(a.getParentMods().size() + b.getParentMods().size());
			parents.addAll(a.getParentMods());
			parents.addAll(b.getParentMods());
			parents.sort(this);

			if (a.getParentMods().contains(parents.get(0))) {
				if (b.getParentMods().contains(parents.get(0))) {
					return 0;
				} else {
					return -1;
				}
			} else {
				return 1;
			}
		}
	};

	static void preselectMod(ModCandidateImpl mod, List<ModCandidateImpl> allModsSorted, Map<String, List<ModCandidateImpl>> modsById,
			Map<String, ModCandidateImpl> selectedMods, List<ModCandidateImpl> uniqueSelectedMods) throws ModResolutionException {
		selectMod(mod, selectedMods, uniqueSelectedMods);

		allModsSorted.removeAll(modsById.remove(mod.getId()));

		for (ProvidedMod provided : mod.getAdditionallyProvidedMods()) {
			String id = provided.getId();

			if (provided.isExclusive()) {
				allModsSorted.removeAll(modsById.remove(id));
			} else {
				List<ModCandidateImpl> mods = modsById.get(id);
				mods.remove(mod);
				allModsSorted.remove(mod);

				for (Iterator<ModCandidateImpl> it = mods.iterator(); it.hasNext(); ) {
					ModCandidateImpl m = it.next();

					if (!hasExclusiveId(m, id)) {
						it.remove();
						allModsSorted.remove(m);
					}
				}

				if (mods.isEmpty()) modsById.remove(id);
			}
		}
	}

	static void selectMod(ModCandidateImpl mod, Map<String, ModCandidateImpl> selectedMods, List<ModCandidateImpl> uniqueSelectedMods) throws ModResolutionException {
		ModCandidateImpl prev = selectedMods.put(mod.getId(), mod);
		if (prev != null && hasExclusiveId(prev, mod.getId())) throw new ModResolutionException("duplicate mod %s", mod.getId());

		for (ProvidedMod provided : mod.getAdditionallyProvidedMods()) {
			String id = provided.getId();

			if (provided.isExclusive()) {
				prev = selectedMods.put(id, mod);
				if (prev != null && hasExclusiveId(prev, id)) throw new ModResolutionException("duplicate provided mod %s", id);
			} else {
				prev = selectedMods.putIfAbsent(id, mod);
			}
		}

		uniqueSelectedMods.add(mod);
	}

	static boolean hasExclusiveId(ModCandidateImpl mod, String id) {
		if (mod.getId().equals(id)) return true;

		for (ProvidedMod provided : mod.getAdditionallyProvidedMods()) {
			if (provided.isExclusive() && provided.getId().equals(id)) return true;
		}

		return false;
	}
}
