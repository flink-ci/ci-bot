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

package com.ververica.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Cache;
import okhttp3.OkHttpClient;
import okhttp3.OkUrlFactory;
import okhttp3.Request;
import okhttp3.Response;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.kohsuke.github.extras.OkHttp3Connector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

public class GithubActionsImpl implements GitHubActions {
	private static final Logger LOG = LoggerFactory.getLogger(GithubActionsImpl.class);

	private final Cache cache;
	private final OkHttpClient okHttpClient;
	private final GitHub gitHub;
	private final String authorizationToken;

	public GithubActionsImpl(Path temporaryDirectory, String authorizationToken) throws IOException {
		cache = new Cache(temporaryDirectory.toFile(), 4 * 1024 * 1024);
		okHttpClient = setupOkHttpClient(cache);
		gitHub = setupGitHub(authorizationToken, okHttpClient);
		this.authorizationToken = authorizationToken;
	}

	private static OkHttpClient setupOkHttpClient(Cache cache) {
		LOG.info("Setting up OkHttp client with cache at {}.", cache.directory());

		final OkHttpClient.Builder okHttpClient = new OkHttpClient.Builder();
		okHttpClient.cache(cache);
		return okHttpClient.build();
	}

	private static GitHub setupGitHub(String token, OkHttpClient client) throws IOException {
		LOG.info("Setting up GitHub client.");

		return GitHubBuilder.fromEnvironment()
				.withOAuthToken(token)
				.withConnector(new OkHttp3Connector(new OkUrlFactory(client)))
				.build();
	}

	@Override
	public void submitComment(String repositoryName, int pullRequestID, String comment) throws IOException {
		final GHRepository repository = gitHub.getRepository(repositoryName);
		final GHPullRequest pullRequest = repository.getPullRequest(pullRequestID);
		pullRequest.comment(comment);
	}

	/**
	 * Retrieves the CI status for the given commit.
	 *
	 * <p>This internally retrieves the status via the Checks API, since the Commit Status API is not supported on
	 * {@code travis-ci.com}.
	 * Internally this uses a plain REST client, since the {@code github-api} does not support the
	 * Checks API. (see https://github.com/kohsuke/github-api/issues/520)
	 */
	public Iterable<GitHubCheckerStatus> getCommitState(String repositoryName, String commitHash) {
		try (Response response = okHttpClient.newCall(new Request.Builder()
				.url("https://api.github.com/repos/" + repositoryName + "/commits/" + commitHash + "/check-runs")
				.addHeader("Accept", "application/vnd.github.antiope-preview+json")
				.addHeader("Authorization", "token " + authorizationToken)
				.build()).execute()) {
			final String rawJson = response.body().string();

			try {
				final ObjectMapper objectMapper = new ObjectMapper();
				final JsonNode jsonNode = objectMapper.readTree(rawJson);
				final Iterator<JsonNode> checkJson = jsonNode.get("check_runs").iterator();

				final List<GitHubCheckerStatus> checkerStatusList = new ArrayList<>();
				while (checkJson.hasNext()) {
					final JsonNode next = checkJson.next();

					final JsonNode statusNode = next.get("status");
					final GHStatus status = GHStatus.valueOf(statusNode.asText().toUpperCase());

					final JsonNode conclusionNode = next.get("conclusion");
					final Optional<GHConclusion> conclusion = conclusionNode.isNull()
							? Optional.empty()
							: Optional.of(GHConclusion.valueOf(conclusionNode.asText().toUpperCase()));

					final JsonNode detailsUrlNode = next.get("details_url");
					final String detailsUrl = detailsUrlNode.asText();

					final JsonNode nameNode = next.get("name");
					final String name = nameNode.asText();

					if (status != GHStatus.COMPLETED) {
						checkerStatusList.add(new GitHubCheckerStatus(GitHubCheckerStatus.State.PENDING, detailsUrl, name));
					} else {
						if (!conclusion.isPresent()) {
							LOG.warn("Completed check did not have conclusion attached.");
						} else {
							final GitHubCheckerStatus.State state;
							switch (conclusion.get()) {
								case SUCCESS:
									state = GitHubCheckerStatus.State.SUCCESS;
									break;
								case CANCELLED:
									state = GitHubCheckerStatus.State.CANCELED;
									break;
								default:
									state = GitHubCheckerStatus.State.FAILURE;
									break;
							}
							checkerStatusList.add(new GitHubCheckerStatus(state, detailsUrl, name));
						}
					}
				}
				return checkerStatusList;
			} catch (Exception e) {
				LOG.debug("Raw Check JSON: {}.", rawJson);
				throw e;
			}
		} catch (
				Exception e) {
			// super janky but don't bother handling this in a better way
			// there are just too many failure points here
			LOG.warn("Could not retrieve commit state.", e);
			return Collections.emptyList();
		}
	}

	@Override
	public Iterable<String> getBranches(String repositoryName) throws IOException {
		return gitHub.getRepository(repositoryName).getBranches().keySet();
	}

	@Override
	public Iterable<GitHubComment> getComments(String repositoryName, int pullRequestID, String username) throws IOException {
		final GHRepository repository = gitHub.getRepository(repositoryName);
		final GHPullRequest pullRequest = repository.getPullRequest(pullRequestID);

		final List<GitHubComment> comments = new ArrayList<>();
		for (GHIssueComment listReviewComment : pullRequest.getComments()) {
			if (listReviewComment.getUser().getLogin().equals(username)) {
				comments.add(new GitHubComment(listReviewComment.getBody(), listReviewComment::update));
			}
		}
		return comments;
	}

	@Override
	public Iterable<GithubPullRequest> getRecentlyUpdatedOpenPullRequests(String repositoryName, Date since) throws IOException {
		final GHRepository observedGitHubRepository = gitHub.getRepository(repositoryName);

		final List<GithubPullRequest> pullRequests = new ArrayList<>();
		for (GHPullRequest pullRequest : observedGitHubRepository.getPullRequests(GHIssueState.OPEN)) {
			LOG.trace("Evaluating PR {}.", pullRequest.getNumber());
			if (pullRequest.getUpdatedAt().after(since)) {
				pullRequests.add(new GithubPullRequest(pullRequest.getNumber(), pullRequest.getUpdatedAt(), pullRequest.getHead().getSha()));
			} else {
				LOG.trace("Excluded PR {} due to not being updated recently. LastUpdatedAt={} updateCutoff={}", pullRequest.getNumber(), pullRequest.getUpdatedAt(), since);
			}
		}

		return pullRequests;
	}

	@Override
	public void close() {
		try {
			cache.close();
		} catch (Exception e) {
			LOG.debug("Error while shutting down cache.", e);
		}
	}

	private enum GHConclusion {
		SUCCESS,
		FAILURE,
		NEUTRAL,
		CANCELLED,
		TIMED_OUT,
		ACTION_REQUIRED
	}

	private enum GHStatus {
		QUEUED,
		IN_PROGRESS,
		COMPLETED
	}
}
