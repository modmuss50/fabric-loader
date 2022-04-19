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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
import net.fabricmc.loader.impl.util.PhaseSorting;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

public class ModResolver {
	public static List<ModCandidateImpl> resolve(ResolutionContext context) throws ModResolutionException {
		long startTime = System.nanoTime();

		List<ModCandidateImpl> result = findCompatibleSet(context);

		long endTime = System.nanoTime();
		Log.debug(LogCategory.RESOLUTION, "Mod resolution time: %.1f ms", (endTime - startTime) * 1e-6);

		return result;
	}

	private static List<ModCandidateImpl> findCompatibleSet(ResolutionContext context) throws ModResolutionException {
		addMods(context.initialMods, context);

		// preselect mods, check for builtin mod collisions

		List<ModCandidateImpl> preselectedMods = new ArrayList<>();

		for (List<ModCandidateImpl> mods : context.modsById.values()) {
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

		for (ModCandidateImpl mod : preselectedMods) {
			selectMod(mod, context);
		}

		// phase sorting

		PhaseSorting<String, ModCandidateImpl> sorting = new PhaseSorting<>();

		for (ModCandidateImpl mod : context.allModsSorted) {
			sorting.add(mod.getMetadata().getLoadPhase(), mod);
		}

		// solve

		Iterator<String> phaseIterator = sorting.getUsedPhases().iterator();
		boolean advancePhase = true;
		String phase = null;

		while (!context.allModsSorted.isEmpty()) {
			if (advancePhase) {
				if (!phaseIterator.hasNext()) break;

				phase = phaseIterator.next();

				for (ModCandidateImpl mod : sorting.get(phase)) {
					mod.enableGreedyLoad = true;
				}
			}

			ModSolver.Result result;

			try {
				result = ModSolver.solve(context);
			} catch (ContradictionException | TimeoutException e) {
				throw new ModResolutionException("Solving failed", e);
			}

			if (!result.success) {
				Log.warn(LogCategory.RESOLUTION, "Mod resolution failed");
				Log.info(LogCategory.RESOLUTION, "Immediate reason: %s%n", result.immediateReason);
				Log.info(LogCategory.RESOLUTION, "Reason: %s%n", result.reason);
				if (!context.envDisabledMods.isEmpty()) Log.info(LogCategory.RESOLUTION, "%s environment disabled: %s%n", context.envType.name(), context.envDisabledMods.keySet());

				if (result.fix == null) {
					Log.info(LogCategory.RESOLUTION, "No fix?");
				} else {
					Log.info(LogCategory.RESOLUTION, "Fix: add %s, remove %s, replace [%s]%n",
							result.fix.modsToAdd,
							result.fix.modsToRemove,
							result.fix.modReplacements.entrySet().stream().map(e -> String.format("%s -> %s", e.getValue(), e.getKey())).collect(Collectors.joining(", ")));

					for (Collection<ModCandidateImpl> mods : context.envDisabledMods.values()) {
						for (ModCandidateImpl m : mods) {
							result.fix.inactiveMods.put(m, InactiveReason.WRONG_ENVIRONMENT);
						}
					}
				}

				throw new ModResolutionException("Mod resolution encountered an incompatible mod set!%s",
						ResultAnalyzer.gatherErrors(result, context));
			}

			if (!context.currentSelectedMods.isEmpty()) {
				if (context.phaseSelectHandler != null) context.phaseSelectHandler.onSelect(context.currentSelectedMods, phase, context);
				context.currentSelectedMods.clear();
			}

			if (context.addedMods.isEmpty()) {
				advancePhase = true;
			} else {
				addMods(context.addedMods, context);

				Set<String> addedPhases = new HashSet<>();

				for (ModCandidateImpl mod : context.addedMods) {
					String modPhase = mod.getMetadata().getLoadPhase();
					sorting.add(modPhase, mod);
					addedPhases.add(modPhase);
				}

				int addedPhaseCount = addedPhases.size();

				// remove phases that aren't beyond the current phase
				addedPhases.remove(phase);

				if (!addedPhases.isEmpty()) {
					int idx = sorting.getPhaseIndex(phase);

					for (Iterator<String> it = addedPhases.iterator(); it.hasNext(); ) {
						if (sorting.getPhaseIndex(it.next()) <= idx) it.remove();
					}
				}

				advancePhase = addedPhases.size() == addedPhaseCount; // all added mods use phases beyond the current phase

				if (!advancePhase) {
					for (ModCandidateImpl mod : context.addedMods) {
						if (!addedPhases.contains(mod.getMetadata().getLoadPhase())) {
							mod.enableGreedyLoad = true;
						}
					}
				}

				// reset iterator since it's likely invalid
				phaseIterator = sorting.getUsedPhases().iterator();
				while (!phaseIterator.next().equals(phase)) { }

				context.addedMods.clear();
			}
		}

		context.uniqueSelectedMods.sort(Comparator.comparing(ModCandidateImpl::getId));

		// clear cached data and inbound refs for unused mods, set minNestLevel for used non-root mods to max, queue root mods

		Queue<ModCandidateImpl> queue = new ArrayDeque<>();

		for (ModCandidateImpl mod : context.allModsSorted) {
			if (context.selectedMods.get(mod.getId()) == mod) { // mod is selected
				if (!mod.resetMinNestLevel()) { // -> is root
					queue.add(mod);
				}
			} else {
				mod.clearCachedData();

				for (ModCandidateImpl m : mod.getContainedMods()) {
					m.getContainingMods().remove(mod);
				}

				for (ModCandidateImpl m : mod.getContainingMods()) {
					m.getContainedMods().remove(mod);
				}
			}
		}

		// recompute minNestLevel (may have changed due to parent associations having been dropped by the above step)

		{
			ModCandidateImpl mod;

			while ((mod = queue.poll()) != null) {
				for (ModCandidateImpl child : mod.getContainedMods()) {
					if (child.updateMinNestLevel(mod)) {
						queue.add(child);
					}
				}
			}
		}

		String warnings = ResultAnalyzer.gatherWarnings(context);

		if (warnings != null) {
			Log.warn(LogCategory.RESOLUTION, "Warnings were found!%s", warnings);
		}

		return context.uniqueSelectedMods;
	}

	private static void addMods(Collection<ModCandidateImpl> mods, ResolutionContext context) {
		context.allModsSorted.addAll(mods);

		// sort all mods by priority

		context.allModsSorted.sort(modPrioComparator);

		// group/index all mods by id

		for (ModCandidateImpl mod : mods) {
			context.modsById.computeIfAbsent(mod.getId(), ignore -> new ArrayList<>()).add(mod);

			for (ProvidedMod provided : mod.getAdditionallyProvidedMods()) {
				context.modsById.computeIfAbsent(provided.getId(), ignore -> new ArrayList<>()).add(mod);
			}
		}

		// soften positive deps from schema 0 or 1 mods on mods that are present but disabled for the current env
		// this is a workaround necessary due to many mods declaring deps that are unsatisfiable in some envs and loader before 0.12x not verifying them properly

		for (ModCandidateImpl mod : mods) {
			if (mod.getMetadata().getSchemaVersion() >= 2) continue;

			for (ModDependency dep : mod.getMetadata().getDependencies()) {
				if (!dep.getKind().isPositive() || dep.getKind() == Kind.SUGGESTS) continue; // no positive dep or already suggests
				if (!(dep instanceof ModDependencyImpl)) continue; // can't modify dep kind
				if (context.modsById.containsKey(dep.getModId())) continue; // non-disabled match available

				Collection<ModCandidateImpl> disabledMatches = context.envDisabledMods.get(dep.getModId());
				if (disabledMatches == null) continue; // no disabled id matches

				for (ModCandidateImpl m : disabledMatches) {
					if (dep.matches(m.getVersion())) { // disabled version match -> remove dep
						((ModDependencyImpl) dep).setKind(Kind.SUGGESTS);
						break;
					}
				}
			}
		}
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

			List<ModCandidateImpl> parents = new ArrayList<>(a.getContainingMods().size() + b.getContainingMods().size());
			parents.addAll(a.getContainingMods());
			parents.addAll(b.getContainingMods());
			parents.sort(this);

			if (a.getContainingMods().contains(parents.get(0))) {
				if (b.getContainingMods().contains(parents.get(0))) {
					return 0;
				} else {
					return -1;
				}
			} else {
				return 1;
			}
		}
	};

	static void selectMod(ModCandidateImpl mod, ResolutionContext context) throws ModResolutionException {
		ModCandidateImpl prev = context.selectedMods.put(mod.getId(), mod);
		if (prev != null && hasExclusiveId(prev, mod.getId())) throw new ModResolutionException("duplicate mod %s", mod.getId());

		for (ProvidedMod provided : mod.getAdditionallyProvidedMods()) {
			String id = provided.getId();

			if (provided.isExclusive()) {
				prev = context.selectedMods.put(id, mod);
				if (prev != null && hasExclusiveId(prev, id)) throw new ModResolutionException("duplicate provided mod %s", id);
			} else {
				prev = context.selectedMods.putIfAbsent(id, mod);
			}
		}

		context.uniqueSelectedMods.add(mod);
		context.currentSelectedMods.add(mod);

		// remove from allModsSorted and modsById

		context.allModsSorted.removeAll(context.modsById.remove(mod.getId()));

		for (ProvidedMod provided : mod.getAdditionallyProvidedMods()) {
			String id = provided.getId();

			if (provided.isExclusive()) {
				context.allModsSorted.removeAll(context.modsById.remove(id));
			} else {
				List<ModCandidateImpl> mods = context.modsById.get(id);
				mods.remove(mod);
				context.allModsSorted.remove(mod);

				for (Iterator<ModCandidateImpl> it = mods.iterator(); it.hasNext(); ) {
					ModCandidateImpl m = it.next();

					if (!hasExclusiveId(m, id)) {
						it.remove();
						context.allModsSorted.remove(m);
					}
				}

				if (mods.isEmpty()) context.modsById.remove(id);
			}
		}
	}

	static boolean hasExclusiveId(ModCandidateImpl mod, String id) {
		if (mod.getId().equals(id)) return true;

		for (ProvidedMod provided : mod.getAdditionallyProvidedMods()) {
			if (provided.isExclusive() && provided.getId().equals(id)) return true;
		}

		return false;
	}

	public static final class ResolutionContext {
		final Collection<ModCandidateImpl> initialMods;
		final EnvType envType;
		final Map<String, Set<ModCandidateImpl>> envDisabledMods;
		final PhaseSelectHandler phaseSelectHandler;

		final List<ModCandidateImpl> allModsSorted;
		final Map<String, List<ModCandidateImpl>> modsById = new LinkedHashMap<>(); // linked to ensure consistent execution
		final Map<String, ModCandidateImpl> selectedMods;
		final List<ModCandidateImpl> uniqueSelectedMods;

		final List<ModCandidateImpl> addedMods = new ArrayList<>();
		final List<ModCandidateImpl> currentSelectedMods = new ArrayList<>();

		public ResolutionContext(Collection<ModCandidateImpl> candidates, EnvType envType, Map<String, Set<ModCandidateImpl>> envDisabledMods,
				PhaseSelectHandler phaseSelectHandler) {
			this.initialMods = candidates;
			this.envType = envType;
			this.envDisabledMods = envDisabledMods;
			this.phaseSelectHandler = phaseSelectHandler;

			this.allModsSorted = new ArrayList<>(candidates.size());
			this.selectedMods = new HashMap<>(candidates.size());
			this.uniqueSelectedMods = new ArrayList<>(candidates.size());
		}

		public Collection<ModCandidateImpl> getMods(String id) {
			List<ModCandidateImpl> ret = new ArrayList<>();
			ret.addAll(modsById.getOrDefault(id, Collections.emptyList()));
			ModCandidateImpl mod = selectedMods.get(id);
			if (mod != null) ret.add(mod);

			return ret;
		}

		public Collection<ModCandidateImpl> getMods() {
			List<ModCandidateImpl> ret = new ArrayList<>(allModsSorted.size() + uniqueSelectedMods.size());
			ret.addAll(allModsSorted);
			ret.addAll(uniqueSelectedMods);

			return ret;
		}

		public boolean addMod(ModCandidateImpl mod) {
			for (ModCandidateImpl m : modsById.getOrDefault(mod.getId(), Collections.emptyList())) {
				if (m == mod || m.getVersion().equals(mod.getVersion())) {
					return false;
				}
			}

			if (selectedMods.containsKey(mod.getId())) return false;

			for (ModCandidateImpl m : addedMods) {
				if (m == mod || m.getId().equals(mod.getId()) && m.getVersion().equals(mod.getVersion())) {
					return false;
				}
			}

			addedMods.add(mod);

			return true;
		}
	}

	public interface PhaseSelectHandler {
		void onSelect(List<ModCandidateImpl> mods, String phase, ResolutionContext context);
	}
}
