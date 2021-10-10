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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ververica.ci.CiActions;
import com.ververica.ci.CiProvider;
import com.ververica.github.GitHubCheckerStatus;
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
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AzureActionsImpl implements CiActions {
	private static final Logger LOG = LoggerFactory.getLogger(AzureActionsImpl.class);

	private static final Pattern BUILD_ID_PATTERN = Pattern.compile(".*buildId=([0-9]+)&?.*");
	private static final Pattern PROJECT_SLUG_PATTERN = Pattern.compile(".*dev.azure.com/(.*)/_build.*");
	private static final Pattern NORMALIZED_URL_PATTERN = Pattern.compile("(.*buildId=[0-9]+).*");

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private final OkHttpClient okHttpClient;

	public static AzureActionsImpl create(Path temporaryDirectory, String authorizationToken) {
		// dhjf4zurpriacwvp74vuxiavnm5xoprlqddalfyg5q7bat7buuka
		final String pat64 = Base64.getEncoder().encodeToString((":" + authorizationToken).getBytes());
		final Cache cache = new Cache(temporaryDirectory.toFile(), 4 * 1024 * 1024);
		final OkHttpClient okHttpClient = setupOkHttpClient(cache, pat64);

		return new AzureActionsImpl(okHttpClient);
	}

	AzureActionsImpl(OkHttpClient okHttpClient) {
		this.okHttpClient = okHttpClient;
	}

	private static OkHttpClient setupOkHttpClient(Cache cache, String pat64) {
		LOG.info("Setting up OkHttp client with cache at {}.", cache.directory());

		final OkHttpClient.Builder okHttpClient = new OkHttpClient.Builder();
		okHttpClient.cache(cache);
		okHttpClient.addInterceptor(chain -> {
			Request original = chain.request();

			Request request = original.newBuilder()
					.header("Accept", "application/json;api-version=6.0-preview.5")
					.header("Authorization", "Basic " + pat64)
					.method(original.method(), original.body())
					.build();

			return chain.proceed(request);
		});
		return okHttpClient.build();
	}

	@Override
	public void close() {
		Optional.ofNullable(okHttpClient.cache())
				.ifPresent(cache -> {
					try {
						cache.close();
					} catch (IOException e) {
						LOG.debug("Error while shutting down cache.", e);
					}
				});
	}

	@Override
	public void cancelBuild(String detailsUrl) {
		final String projectSlug = extractProjectSlug(detailsUrl);
		final String buildId = extractBuildId(detailsUrl);

		submitRequest(
				"https://dev.azure.com/" + projectSlug + "/_apis/build/builds/" + buildId,
				"PATCH",
				RequestBody.create(MediaType.get("application/json"), "{\"status\":4}"));
	}

	@Override
	public void retryBuild(String detailsUrl, String branch) {
		LOG.debug("Triggering build for branch {}.", branch);

		final String projectSlug = extractProjectSlug(detailsUrl);
		final String buildId = extractBuildId(detailsUrl);

		Optional<Integer> definitionId = getDefinitionId(projectSlug, buildId);
		if (!definitionId.isPresent()) {
			LOG.error("Failed to trigger build; could not retrieve definition id.");
			return;
		}

		submitRequest(
				"https://dev.azure.com/" + projectSlug + "/_apis/build/builds/" + buildId + "?retry=true",
				"PATCH",
				RequestBody.create(MediaType.get("application/json"), "{}"));
	}

	@Override
	public boolean supportsDirectBuildStatusRetrieval() {
		return true;
	}

	@Override
	public Optional<GitHubCheckerStatus.State> getBuildStatus(String detailsUrl) {
		final String projectSlug = extractProjectSlug(detailsUrl);
		final String buildId = extractBuildId(detailsUrl);

		return submitRequest("https://dev.azure.com/" + projectSlug + "/_apis/build/builds/" + buildId, "GET", null)
				.flatMap(buildDetails -> {
					try {
						final JsonNode json = OBJECT_MAPPER.readTree(buildDetails);

						final Optional<AzureBuildStatus> azureBuildStatus = getAzureBuildStatus(json);

						if (!azureBuildStatus.isPresent()) {
							return Optional.of(GitHubCheckerStatus.State.UNKNOWN);
						}

						switch (azureBuildStatus.get()) {
							case COMPLETED:
								final Optional<AzureBuildResult> azureBuildResult = getAzureBuildResult(json);
								if (!azureBuildResult.isPresent()) {
									// this shouldn't happen; use PENDING to ensure we try fetching the state again
									return Optional.of(GitHubCheckerStatus.State.PENDING);
								} else {
									switch (azureBuildResult.get()) {
										case PARTIALLYSUCCEEDED:
										case FAILED:
											return Optional.of(GitHubCheckerStatus.State.FAILURE);
										case CANCELED:
											return Optional.of(GitHubCheckerStatus.State.CANCELED);
										case SUCCEEDED:
											return Optional.of(GitHubCheckerStatus.State.SUCCESS);
									}
								}
							default:
								return Optional.of(GitHubCheckerStatus.State.PENDING);
						}
					} catch (IOException e) {
						LOG.error("Failed to process response.", e);
						return Optional.empty();
					}
				});
	}

	private static Optional<AzureBuildStatus> getAzureBuildStatus(JsonNode json) {
		return getEnum(AzureBuildStatus::valueOf, json, "status");
	}

	private static Optional<AzureBuildResult> getAzureBuildResult(JsonNode json) {
		return getEnum(AzureBuildResult::valueOf, json, "result");
	}

	private static <X> Optional<X> getEnum(Function<String, X> fun, JsonNode json, String field) {
		JsonNode rawField = json.get(field);

		if (rawField == null) {
			return Optional.empty();
		}
		try {
			return Optional.of(fun.apply(rawField.asText().toUpperCase()));
		} catch (IllegalArgumentException iae) {
			return Optional.empty();
		}
	}

	// based on https://docs.microsoft.com/en-us/rest/api/azure/devops/build/builds/queue?view=azure-devops-rest-6.0#buildstatus
	private enum AzureBuildStatus {
		NONE,
		NOTSTARTED,
		INPROGRESS,
		CANCELLING,
		COMPLETED,
		POSTPONED
	}

	// based on https://docs.microsoft.com/en-us/rest/api/azure/devops/build/builds/queue?view=azure-devops-rest-6.0#buildresult
	private enum AzureBuildResult {
		CANCELED,
		FAILED,
		PARTIALLYSUCCEEDED,
		SUCCEEDED
	}

	private Optional<Integer> getDefinitionId(String projectSlug, String buildId) {
		return submitRequest("https://dev.azure.com/" + projectSlug + "/_apis/build/builds/" + buildId, "GET", null)
				.flatMap(buildDetails -> {
					try {
						int definitionId = OBJECT_MAPPER.readTree(buildDetails).get("definition").get("id").asInt();
						return Optional.of(definitionId);
					} catch (IOException e) {
						LOG.error("Failed to process response.", e);
						return Optional.empty();
					}
				});
	}

	private Optional<String> submitRequest(String url, String method, RequestBody requestBody) {
		try {
			try (Response response = okHttpClient.newCall(
					new Request.Builder()
							.url(url)
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

	@Override
	public CiProvider getCiProvider() {
		return CiProvider.Azure;
	}

	@Override
	public String normalizeUrl(String detailsUrl) {
		return internalNormalizeUrl(detailsUrl);
	}

	static String internalNormalizeUrl(String detailsUrl) {
		return extractFromUrl(NORMALIZED_URL_PATTERN, detailsUrl, "Could not normalize url (" + detailsUrl + ").");
	}

	static String extractProjectSlug(String detailsUrl) {
		return extractFromUrl(PROJECT_SLUG_PATTERN, detailsUrl, "Could not extract project slug from url (" + detailsUrl + ").");
	}

	static String extractBuildId(String detailsUrl) {
		return extractFromUrl(BUILD_ID_PATTERN, detailsUrl, "Could not extract build ID from url (" + detailsUrl + ").");
	}

	private static String extractFromUrl(Pattern pattern, String detailsUrl, String errorMessage) {
		// example urls:
		// https://dev.azure.com/chesnay/0f3463e8-185e-423b-aa88-6cc39182caea/_build/results?buildId=1
		// https://dev.azure.com/chesnay/0f3463e8-185e-423b-aa88-6cc39182caea/_build/results?buildId=1&view=logs&jobId=c6e12662-7e76-5fac-6ded-4b654ce98c1b
		Matcher matcher = pattern.matcher(detailsUrl);
		if (matcher.find()) {
			return matcher.group(1);
		} else {
			throw new IllegalArgumentException(errorMessage);
		}
	}
}
