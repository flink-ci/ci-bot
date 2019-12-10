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

package com.ververica.ci.azure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ververica.ci.CiActions;
import okhttp3.Cache;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

public class AzureActionsImpl implements CiActions {
	private static final Logger LOG = LoggerFactory.getLogger(AzureActionsImpl.class);

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private final Cache cache;
	private final OkHttpClient okHttpClient;
	private final String pat64;

	public AzureActionsImpl(Path temporaryDirectory, String authorizationToken) {
		// dhjf4zurpriacwvp74vuxiavnm5xoprlqddalfyg5q7bat7buuka
		pat64 = Base64.getEncoder().encodeToString((":" + authorizationToken).getBytes());
		cache = new Cache(temporaryDirectory.toFile(), 4 * 1024 * 1024);
		okHttpClient = setupOkHttpClient(cache);
	}

	@Override
	public void close() {
		try {
			cache.close();
		} catch (Exception e) {
			LOG.debug("Error while shutting down cache.", e);
		}
	}

	@Override
	public void cancelBuild(String detailsUrl) {
		final String projectSlug = extractProjectUrl(detailsUrl);
		final String buildId = extractBuildId(detailsUrl);

		submitRequest(
				"https://dev.azure.com/" + projectSlug + "/_apis/build/builds/" + buildId,
				"PATCH",
				RequestBody.create(MediaType.get("application/json"), "{\"status\":4}"));
	}

	@Override
	public Optional<String> runBuild(String detailsUrl, String branch, List<String> arguments) {
		final String projectSlug = extractProjectUrl(detailsUrl);
		final String args = arguments.size() == 0
						? ""
						: "\"parameters\":\"{\\\"args\\\":\\\"" + String.join(" ", arguments) + "\\\"}\"";

		Optional<String> response = submitRequest(
				"https://dev.azure.com/" + projectSlug + "/_apis/build/builds",
				"POST",
				RequestBody.create(
						MediaType.get("application/json"),
						"{" +
								"\"definition\": {\"id\": 1}," +
								"\"sourceBranch\": \"" + branch + "\"," +
								args +
								"}"));

		if (response.isPresent()) {
			try {
				String newDetailsUrl = OBJECT_MAPPER.readTree(response.get()).get("_links").get("web").get("href").asText();
				return Optional.of(newDetailsUrl);
			} catch (IOException e) {
				LOG.error("Failed to process response.", e);
			}
		}

		return Optional.empty();
	}

	private Optional<String> submitRequest(String url, String method, RequestBody requestBody) {
		try {
			try (Response response = okHttpClient.newCall(
					new Request.Builder()
							.url(url)
							.header("Accept", "application/json;api-version=6.0-preview.5")
							.header("Authorization", "Basic " + pat64)
							.method(method, requestBody)
							.build()
			).execute()) {
				if (response.isSuccessful()) {
					LOG.debug("Successfully submitted request. {}", response.toString());
					return Optional.of(response.body().string());
				} else {
					LOG.debug("Request failed. {} {}.", response.toString(), response.body().string());
				}
			}
		} catch (IOException e) {
			LOG.error("Failed to submit request.", e);
		}

		return Optional.empty();
	}

	public static String normalizeUrl(String detailsUrl) {
		// example urls:
		// https://dev.azure.com/chesnay/0f3463e8-185e-423b-aa88-6cc39182caea/_build/results?buildId=1
		// https://dev.azure.com/chesnay/0f3463e8-185e-423b-aa88-6cc39182caea/_build/results?buildId=1&view=logs&jobId=c6e12662-7e76-5fac-6ded-4b654ce98c1b
		final String prefix = "buildId=";

		// https://dev.azure.com/chesnay/0f3463e8-185e-423b-aa88-6cc39182caea/_build/results?
		final int prefixIndex = detailsUrl.indexOf(prefix);

		// 1&view=logs&jobId=c6e12662-7e76-5fac-6ded-4b654ce98c1b
		final String buildAndTail = detailsUrl.substring(prefixIndex + prefix.length());

		final String tailPrefix = "&";
		// 1&
		final int tailIndex = buildAndTail.indexOf(tailPrefix);

		if (tailIndex < 0) {
			return detailsUrl;
		} else {
			return detailsUrl.substring(0, prefixIndex + prefix.length() + tailIndex);
		}
	}

	private static OkHttpClient setupOkHttpClient(Cache cache) {
		LOG.info("Setting up OkHttp client with cache at {}.", cache.directory());

		final OkHttpClient.Builder okHttpClient = new OkHttpClient.Builder();
		okHttpClient.cache(cache);
		return okHttpClient.build();
	}

	private static String extractProjectUrl(String detailsUrl) {
		// example urls:
		// https://dev.azure.com/chesnay/0f3463e8-185e-423b-aa88-6cc39182caea/_build/results?buildId=1
		final String prefix = "dev.azure.com/";
		final String suffix = "/_build";

		return detailsUrl.substring(
				detailsUrl.indexOf(prefix) + prefix.length(),
				detailsUrl.indexOf(suffix)
		);
	}

	private static String extractBuildId(String detailsUrl) {
		// example urls:
		// https://dev.azure.com/chesnay/0f3463e8-185e-423b-aa88-6cc39182caea/_build/results?buildId=1
		// https://dev.azure.com/chesnay/0f3463e8-185e-423b-aa88-6cc39182caea/_build/results?buildId=1&view=logs&jobId=c6e12662-7e76-5fac-6ded-4b654ce98c1b
		final String prefix = "buildId=";

		final String buildAndTail = detailsUrl.substring(detailsUrl.indexOf(prefix) + prefix.length());

		final String tailPrefix = "&";
		final int tailIndex = buildAndTail.indexOf(tailPrefix);

		if (tailIndex < 0) {
			// 1
			return buildAndTail;
		} else {
			// 1&view=logs&jobId=c6e12662-7e76-5fac-6ded-4b654ce98c1b
			return buildAndTail.substring(0, tailIndex);
		}
	}
}
