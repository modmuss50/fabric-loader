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

package net.fabricmc.loader.impl.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;

import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/**
 * Contains phase-sorting logic for {@link ArrayBackedEvent}.
 */
public final class PhaseSorting<P extends Comparable<P>, E> {
	private static boolean ENABLE_CYCLE_WARNING = true;

	/**
	 * Registered phases.
	 */
	private final Map<P, PhaseData<P, E>> phases = new HashMap<>();
	/**
	 * Phases sorted in the correct dependency order.
	 */
	private final List<PhaseData<P, E>> sortedPhases = new ArrayList<>();

	public void add(P phaseIdentifier, E element) {
		Objects.requireNonNull(phaseIdentifier, "Tried to register an element for a null phase!");
		Objects.requireNonNull(element, "Tried to register a null element!");

		getOrCreatePhase(phaseIdentifier, true).addElement(element);
	}

	public List<E> get(P phase) {
		PhaseData<P, E> data = phases.get(phase);

		return data != null ? data.elements : Collections.emptyList();
	}

	public List<E> getAll() {
		if (sortedPhases.size() == 1) {
			// Special case with a single phase: use the array of the phase directly.
			return sortedPhases.get(0).elements;
		} else {
			List<E> ret = new ArrayList<>();

			for (PhaseData<P, E> existingPhase : sortedPhases) {
				ret.addAll(existingPhase.elements);
			}

			return ret;
		}
	}

	private PhaseData<P, E> getOrCreatePhase(P id, boolean sortIfCreate) {
		PhaseData<P, E> phase = phases.get(id);

		if (phase == null) {
			phase = new PhaseData<>(id);
			phases.put(id, phase);
			sortedPhases.add(phase);

			if (sortIfCreate) {
				sortPhases();
			}
		}

		return phase;
	}

	public void addPhaseOrdering(P firstPhase, P secondPhase) {
		Objects.requireNonNull(firstPhase, "Tried to add an ordering for a null phase.");
		Objects.requireNonNull(secondPhase, "Tried to add an ordering for a null phase.");
		if (firstPhase.equals(secondPhase)) throw new IllegalArgumentException("Tried to add a phase that depends on itself.");

		PhaseData<P, E> first = getOrCreatePhase(firstPhase, false);
		PhaseData<P, E> second = getOrCreatePhase(secondPhase, false);
		first.subsequentPhases.add(second);
		second.previousPhases.add(first);
		sortPhases();
	}

	public List<P> getUsedPhases() {
		List<P> ret = new ArrayList<>(sortedPhases.size());

		for (PhaseData<P, E> phase : sortedPhases) {
			if (!phase.elements.isEmpty()) {
				ret.add(phase.id);
			}
		}

		return ret;
	}

	public int getPhaseIndex(P phase) {
		for (int i = 0; i < sortedPhases.size(); i++) {
			if (sortedPhases.get(i).id.equals(phase)) return i;
		}

		return -1;
	}

	/**
	 * Deterministically sort a list of phases.
	 * 1) Compute phase SCCs (i.e. cycles).
	 * 2) Sort phases by id within SCCs.
	 * 3) Sort SCCs with respect to each other by respecting constraints, and by id in case of a tie.
	 */
	void sortPhases() {
		// FIRST KOSARAJU SCC VISIT
		List<PhaseData<P, E>> toposort = new ArrayList<>(sortedPhases.size());

		for (PhaseData<P, E> phase : sortedPhases) {
			forwardVisit(phase, null, toposort);
		}

		clearStatus(toposort);
		Collections.reverse(toposort);

		// SECOND KOSARAJU SCC VISIT
		Map<PhaseData<P, E>, PhaseScc<P, E>> phaseToScc = new IdentityHashMap<>();

		for (PhaseData<P, E> phase : toposort) {
			if (phase.visitStatus == 0) {
				List<PhaseData<P, E>> sccPhases = new ArrayList<>();
				// Collect phases in SCC.
				backwardVisit(phase, sccPhases);
				// Sort phases by id.
				sccPhases.sort(Comparator.comparing(p -> p.id));
				// Mark phases as belonging to this SCC.
				PhaseScc<P, E> scc = new PhaseScc<>(sccPhases);

				for (PhaseData<P, E> phaseInScc : sccPhases) {
					phaseToScc.put(phaseInScc, scc);
				}
			}
		}

		clearStatus(toposort);

		// Build SCC graph
		for (PhaseScc<P, E> scc : phaseToScc.values()) {
			for (PhaseData<P, E> phase : scc.phases) {
				for (PhaseData<P, E> subsequentPhase : phase.subsequentPhases) {
					PhaseScc<P, E> subsequentScc = phaseToScc.get(subsequentPhase);

					if (subsequentScc != scc) {
						scc.subsequentSccs.add(subsequentScc);
						subsequentScc.inDegree++;
					}
				}
			}
		}

		// Order SCCs according to priorities. When there is a choice, use the SCC with the lowest id.
		// The priority queue contains all SCCs that currently have 0 in-degree.
		PriorityQueue<PhaseScc<P, E>> pq = new PriorityQueue<>(Comparator.comparing(scc -> scc.phases.get(0).id));
		sortedPhases.clear();

		for (PhaseScc<P, E> scc : phaseToScc.values()) {
			if (scc.inDegree == 0) {
				pq.add(scc);
				// Prevent adding the same SCC multiple times, as phaseToScc may contain the same value multiple times.
				scc.inDegree = -1;
			}
		}

		while (!pq.isEmpty()) {
			PhaseScc<P, E> scc = pq.poll();
			sortedPhases.addAll(scc.phases);

			for (PhaseScc<P, E> subsequentScc : scc.subsequentSccs) {
				subsequentScc.inDegree--;

				if (subsequentScc.inDegree == 0) {
					pq.add(subsequentScc);
				}
			}
		}
	}

	private void forwardVisit(PhaseData<P, E> phase, PhaseData<P, E> parent, List<PhaseData<P, E>> toposort) {
		if (phase.visitStatus == 0) {
			// Not yet visited.
			phase.visitStatus = 1;

			for (PhaseData<P, E> data : phase.subsequentPhases) {
				forwardVisit(data, phase, toposort);
			}

			toposort.add(phase);
			phase.visitStatus = 2;
		} else if (phase.visitStatus == 1 && ENABLE_CYCLE_WARNING) {
			// Already visiting, so we have found a cycle.
			Log.warn(LogCategory.GENERAL,
					"Phase ordering conflict detected.%nPhase %s is ordered both before and after phase %s.",
					phase.id,
					parent.id);
		}
	}

	private void clearStatus(List<PhaseData<P, E>> phases) {
		for (PhaseData<P, E> phase : phases) {
			phase.visitStatus = 0;
		}
	}

	private void backwardVisit(PhaseData<P, E> phase, List<PhaseData<P, E>> sccPhases) {
		if (phase.visitStatus == 0) {
			phase.visitStatus = 1;
			sccPhases.add(phase);

			for (PhaseData<P, E> data : phase.previousPhases) {
				backwardVisit(data, sccPhases);
			}
		}
	}

	@Override
	public String toString() {
		return sortedPhases.toString();
	}

	private static final class PhaseScc<P extends Comparable<P>, E> {
		final List<PhaseData<P, E>> phases;
		final List<PhaseScc<P, E>> subsequentSccs = new ArrayList<>();
		int inDegree = 0;

		private PhaseScc(List<PhaseData<P, E>> phases) {
			this.phases = phases;
		}
	}

	private static final class PhaseData<P extends Comparable<P>, E> {
		final P id;
		List<E> elements = new ArrayList<>();
		final List<PhaseData<P, E>> subsequentPhases = new ArrayList<>();
		final List<PhaseData<P, E>> previousPhases = new ArrayList<>();
		int visitStatus = 0; // 0: not visited, 1: visiting, 2: visited

		PhaseData(P id) {
			this.id = id;
		}

		void addElement(E element) {
			elements.add(element);
		}

		@Override
		public String toString() {
			return String.format("%s:%s", id, elements);
		}
	}
}
