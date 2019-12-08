/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ververica.ci;

import com.ververica.ci.azure.AzureActionsImpl;

import java.util.function.Function;

public enum CiProvider {
	Travis("Travis", s -> s),
	Azure("Azure", AzureActionsImpl::normalizeUrl),
	Unknown("Unknown", s -> s);

	private final String name;
	private final Function<String, String> urlNormalizer;

	CiProvider(String name, Function<String, String> urlNormalizer) {
		this.name = name;
		this.urlNormalizer = urlNormalizer;
	}

	public String getName() {
		return name;
	}

	public String normalizeUrl(String url) {
		return urlNormalizer.apply(url);
	}

	public static CiProvider fromSlug(String name) {
		if (name.contains("travis-ci")) {
			return Travis;
		}
		if (name.contains("azure-pipelines")) {
			return Azure;
		}
		return Unknown;
	}

	public static CiProvider fromUrl(String url) {
		if (url.contains("travis-ci")) {
			return Travis;
		}
		if (url.contains("azure")) {
			return Azure;
		}
		return Unknown;
	}
}
