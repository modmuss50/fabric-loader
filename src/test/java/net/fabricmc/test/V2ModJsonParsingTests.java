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

package net.fabricmc.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Collections;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.impl.metadata.DependencyOverrides;
import net.fabricmc.loader.impl.metadata.ModMetadataParser;
import net.fabricmc.loader.impl.metadata.ParseMetadataException;
import net.fabricmc.loader.impl.metadata.VersionOverrides;

public class V2ModJsonParsingTests {
	@Test
	void readMetadata() throws ParseMetadataException, VersionParsingException {
		var meta = readFmj("""
{
	"schemaVersion": 2,
	"id": "test",
	"version": "1.0.0",
	"provides": [
		"other-mod",
		{
			"id": "other-id",
			"version": "2.0.0",
			"exclusive": false
		}
	],
	"environment": "client",
	"loadCondition": "if_needed",
	"loadPhase": "what is this?",
	"entrypoints": {
		"main": "com.my.Entrypoint",
		"client": [
			{
				"value": "com.other.Entrypoint",
				"adapter": "kotlin",
				"condition": "mod(kotlin)"
			}
		]
	},
	"jars": [
		{
			"file": "nested.jar"
		}
	],
	"mixins": [
		{
		  "config": "test.client.mixins.json",
		  "environment": "client"
		},
		{
		  "config": "test.server.mixins.json",
		  "environment": "server",
		  "condition": "!mod(kotlin)"
		},
		"test.mixins.json"
	],
	"classTweakers": [
		{
		  "config": "client.ct",
		  "environment": "client"
		},
		{
		  "config": "server.ct",
		  "environment": "server"
		},
		"universal.ct"
	],
	"depends": {
		"fabricloader": {
			"environment": "client"
		},
		"fabric-api": {
			"versions": [
				"1.0.0",
				"1.1.0"
			],
			"environment": "auto",
			"reason": "Oh nice we havea reason",
			"condition": "!mod(fabric)",
			"root": {
				"id": "what"
			}
		}
	},
	"recommends": {
		"fabricloader": "*"
	},
	"suggests": {
		"fabricloader": "*"
	},
	"conflicts": {
		"fabricloader": "*"
	},
	"breaks": {
		"fabricloader": "*"
	},
	"name": "Mod name",
	"description": "A test mod",
	"authors": [
		"Just a name",
		{
			"name": "A cool name",
			"contact": {
				"email": "name@example.com",
				"discord": "name"
			}
		}
	],
	"contributors": [
		"Same as authors"
	],
	"contact": {
		"just": "a map"
	},
	"licenses": [
		"now",
		"an",
		"array"
	],
	"icon": "assets/icon.png",
	"languageAdapters": {
		"kotlin": "net.fabricmc.language.kotlin.KotlinAdapter"
	},
	"custom": {
		"same_as_before": true
	}
}
		""");

		assertEquals("test", meta.getId());
		assertEquals(SemanticVersion.parse("1.0.0"), meta.getVersion());
	}

	private static ModMetadata readFmj(@Language("json") String json) throws ParseMetadataException {
		try (InputStream is = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))) {
			return ModMetadataParser.parseMetadata(is, "", Collections.emptyList(), new VersionOverrides(), new DependencyOverrides(Paths.get(".")), true);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
