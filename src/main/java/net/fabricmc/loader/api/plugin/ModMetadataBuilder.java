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

package net.fabricmc.loader.api.plugin;

import java.util.Collection;

import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.ContactInformation;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import net.fabricmc.loader.impl.metadata.ModMetadataBuilderImpl;
import net.fabricmc.loader.impl.metadata.ModMetadataBuilderImpl.ContactInformationBuilderImpl;

public interface ModMetadataBuilder {
	static ModMetadataBuilder create(String modId, String version) throws VersionParsingException {
		return create(modId, Version.parse(version));
	}

	static ModMetadataBuilder create(String modId, Version version) {
		return new ModMetadataBuilderImpl(modId, version);
	}

	ModMetadataBuilder provides(String modId, /* @Nullable */ Version version, boolean exclusive);

	ModMetadataBuilder environment(ModEnvironment environment);
	ModMetadataBuilder entrypoint(String key, String value, /* @Nullable */ String adapter);
	ModMetadataBuilder nestedJar(String location);
	ModMetadataBuilder mixinConfig(String location, ModEnvironment environment);
	ModMetadataBuilder accessWidener(String location);

	ModMetadataBuilder dependency(ModDependency.Kind kind, String modId, Collection<VersionPredicate> versionOptions);

	ModMetadataBuilder name(String name);
	ModMetadataBuilder description(String description);
	ModMetadataBuilder author(String name, /* @Nullable */ ContactInformation contact);
	ModMetadataBuilder contributor(String name, /* @Nullable */ ContactInformation contact);
	ModMetadataBuilder contact(ContactInformation contact);
	ModMetadataBuilder license(String name);
	ModMetadataBuilder icon(int size, String location);

	ModMetadataBuilder languageAdapter(String name, String cls);

	ModMetadataBuilder customValue(String key, CustomValue value);

	ModMetadata build();

	interface ContactInformationBuilder {
		static ContactInformationBuilder create() {
			return new ContactInformationBuilderImpl();
		}

		ContactInformationBuilder set(String key, String value);

		ContactInformation build();
	}
}
