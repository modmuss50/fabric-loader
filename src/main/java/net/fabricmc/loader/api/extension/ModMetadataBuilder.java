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

package net.fabricmc.loader.api.extension;

import java.io.IOException;
import java.io.Writer;
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
	static ModMetadataBuilder create() {
		return new ModMetadataBuilderImpl();
	}

	ModMetadataBuilder setId(String modId);
	ModMetadataBuilder setVersion(String version) throws VersionParsingException;
	ModMetadataBuilder setVersion(Version version);

	ModMetadataBuilder addProvidedMod(String modId, /* @Nullable */ Version version, boolean exclusive);

	ModMetadataBuilder setEnvironment(ModEnvironment environment);
	ModMetadataBuilder addEntrypoint(String key, String value, /* @Nullable */ String adapter);
	ModMetadataBuilder addNestedMod(String location);
	ModMetadataBuilder addMixinConfig(String location, /* @Nullable */ ModEnvironment environment);
	ModMetadataBuilder setAccessWidener(String location);

	ModMetadataBuilder addDependency(ModDependency.Kind kind, String modId, Collection<VersionPredicate> versionOptions);

	ModMetadataBuilder setName(String name);
	ModMetadataBuilder setDescription(String description);
	ModMetadataBuilder addAuthor(String name, /* @Nullable */ ContactInformation contact);
	ModMetadataBuilder addContributor(String name, /* @Nullable */ ContactInformation contact);
	ModMetadataBuilder setContact(/* @Nullable */ ContactInformation contact);
	ModMetadataBuilder addLicense(String name);
	ModMetadataBuilder addIcon(int size, String location);

	ModMetadataBuilder addLanguageAdapter(String name, String cls);

	ModMetadataBuilder addCustomValue(String key, CustomValue value);

	void toJson(Writer writer) throws IOException;
	String toJson();
	ModMetadata build();

	interface ContactInformationBuilder {
		static ContactInformationBuilder create() {
			return new ContactInformationBuilderImpl();
		}

		ContactInformationBuilder set(String key, String value);

		ContactInformation build();
	}
}
