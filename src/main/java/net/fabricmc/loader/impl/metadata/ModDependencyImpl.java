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

package net.fabricmc.loader.impl.metadata;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.ContactInformation;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import net.fabricmc.loader.api.metadata.version.VersionInterval;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;

public final class ModDependencyImpl implements ModDependency {
	private Kind kind;
	private final String modId;
	private final Collection<VersionPredicate> ranges;
	private final ModEnvironment environment;
	private final String reason;
	private final ModDependency.Metadata metadata;
	private final ModDependency.Metadata rootMetadata;

	ModDependencyImpl(Kind kind,
			String modId, Collection<VersionPredicate> versionOptions,
			ModEnvironment environment, String reason,
			ModDependency.Metadata metadata, ModDependency.Metadata rootMetadata) {
		this.kind = kind;
		this.modId = modId;
		this.ranges = versionOptions;
		this.environment = environment;
		this.reason = reason;
		this.metadata = metadata;
		this.rootMetadata = rootMetadata;
	}

	@Override
	public Kind getKind() {
		return kind;
	}

	public void setKind(Kind kind) {
		this.kind = kind;
	}

	@Override
	public String getModId() {
		return this.modId;
	}

	ModEnvironment getEnvironment() {
		return environment;
	}

	boolean appliesInEnvironment(EnvType type) {
		return environment.matches(type);
	}

	String getReason() {
		return reason;
	}

	ModDependency.Metadata getMetadata() {
		return metadata;
	}

	ModDependency.Metadata getRootMetadata() {
		return rootMetadata;
	}

	@Override
	public boolean matches(Version version) {
		for (VersionPredicate predicate : ranges) {
			if (predicate.test(version)) return true;
		}

		return false;
	}

	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof ModDependency)) return false;

		ModDependency o = (ModDependency) obj;

		return kind == o.getKind()
				&& modId.equals(o.getModId())
				&& ranges.equals(o.getVersionRequirements());
	}

	@Override
	public int hashCode() {
		return (kind.ordinal() * 31 + modId.hashCode()) * 257 + ranges.hashCode();
	}

	@Override
	public String toString() {
		final StringBuilder builder = new StringBuilder("{");
		builder.append(kind.getKey());
		builder.append(' ');
		builder.append(this.modId);
		builder.append(" @ [");

		boolean first = true;

		for (VersionPredicate range : ranges) {
			if (first) {
				first = false;
			} else {
				builder.append(" || ");
			}

			builder.append(range);
		}

		builder.append("]}");
		return builder.toString();
	}

	@Override
	public Collection<VersionPredicate> getVersionRequirements() {
		return ranges;
	}

	@Override
	public List<VersionInterval> getVersionIntervals() {
		List<VersionInterval> ret = Collections.emptyList();

		for (VersionPredicate predicate : ranges) {
			ret = VersionInterval.or(ret, predicate.getInterval());
		}

		return ret;
	}

	static final class Metadata implements ModDependency.Metadata {
		private final String id;
		private final String name;
		private final String description;
		private final ContactInformation contact;

		Metadata(String id, String name, String description, ContactInformation contact) {
			this.id = id;
			this.name = name;
			this.description = description;

			if (contact != null) {
				this.contact = contact;
			} else {
				this.contact = ContactInformation.EMPTY;
			}
		}

		@Override
		public String getId() {
			return id;
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public String getDescription() {
			return description;
		}

		@Override
		public ContactInformation getContact() {
			return contact;
		}
	}
}
