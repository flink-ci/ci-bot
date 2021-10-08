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

import com.google.common.base.Preconditions;
import com.ververica.ci.CiActions;
import com.ververica.ci.CiActionsLookup;
import okhttp3.Cache;
import okhttp3.OkHttpClient;
import org.kohsuke.github.GHCheckRun;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.kohsuke.github.PagedIterable;
import org.kohsuke.github.extras.okhttp3.OkHttpConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class GithubActionsImpl implements GitHubActions {
	private static final Logger LOG = LoggerFactory.getLogger(GithubActionsImpl.class);

	private final CiActionsLookup ciActionsLookup;
	private final Cache cache;
	private final GitHub gitHub;

	public GithubActionsImpl(CiActionsLookup ciActionsLookup, Path temporaryDirectory, String authorizationToken) throws IOException {
		this.ciActionsLookup = ciActionsLookup;
		cache = new Cache(temporaryDirectory.toFile(), 4 * 1024 * 1024);
		final OkHttpClient okHttpClient = setupOkHttpClient(cache);
		gitHub = setupGitHub(authorizationToken, okHttpClient);
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
				.withConnector(new OkHttpConnector(client))
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
	 */
	public Iterable<GitHubCheckerStatus> getCommitState(String repositoryName, String commitHash, Pattern checkerNamePattern) throws CommitNotFoundException {
		final PagedIterable<GHCheckRun> checkRuns;
		try {
			checkRuns = gitHub.getRepository(repositoryName)
					.getCommit(commitHash)
					.getCheckRuns();
		} catch (GHFileNotFoundException e) {
			throw new CommitNotFoundException();
		} catch (IOException e) {
			// retry later
			LOG.warn("Could not retrieve commit state.", e);
			return Collections.emptyList();
		}

		try {
			final Map<String, GitHubCheckerStatus> checksByUrl = new HashMap<>();
			for (GHCheckRun checkRun : checkRuns) {
				final String name = checkRun.getName();
				if (!checkerNamePattern.matcher(name).matches()) {
					LOG.trace("Excluded checker with name {}.", name);
					continue;
				}
				LOG.trace("Processing checker run with name {}.", name);

				final GHCheckRun.Status status = checkRun.getStatus();

				final Optional<GHCheckRun.Conclusion> conclusion = Optional.ofNullable(checkRun.getConclusion());

				final String appName = checkRun.getApp().getName().toLowerCase(Locale.ROOT);
				final Optional<CiActions> ciActionsOptional = ciActionsLookup.getActionsForString(appName);
				if (!ciActionsOptional.isPresent()) {
					LOG.warn("Skipping checker since CI provider could not be determined. Slug={}.", appName);
					continue;
				}
				CiActions ciActions = ciActionsOptional.get();

				final String detailsUrl = ciActions.normalizeUrl(checkRun.getDetailsUrl().toString());

				final GitHubCheckerStatus checkerStatus;
				if (status != GHCheckRun.Status.COMPLETED) {
					checkerStatus = new GitHubCheckerStatus(GitHubCheckerStatus.State.PENDING, detailsUrl, ciActions.getCiProvider());
				} else {
					if (!conclusion.isPresent()) {
						LOG.warn("Completed check did not have conclusion attached.");
						checkerStatus = null;
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
						checkerStatus = new GitHubCheckerStatus(state, detailsUrl, ciActions.getCiProvider());
					}
				}
				if (checkerStatus != null) {
					checksByUrl.compute(detailsUrl, (s, gitHubCheckerStatus) ->
							gitHubCheckerStatus == null
									? checkerStatus
									: merge(checkerStatus, gitHubCheckerStatus));
				}
			}
			return new ArrayList<>(checksByUrl.values());
		} catch (Exception e) {
			// super janky but don't bother handling this in a better way
			// there are just too many failure points here
			LOG.warn("Could not retrieve commit state.", e);
			return Collections.emptyList();
		}
	}

	private static GitHubCheckerStatus merge(GitHubCheckerStatus a, GitHubCheckerStatus b) {
		Preconditions.checkArgument(a.getDetailsUrl().equals(b.getDetailsUrl()));
		Preconditions.checkArgument(a.getCiProvider().getName().equals(b.getCiProvider().getName()));

		final GitHubCheckerStatus.State mergedState;
		if (a.getState() == GitHubCheckerStatus.State.FAILURE || b.getState() == GitHubCheckerStatus.State.FAILURE) {
			mergedState = GitHubCheckerStatus.State.FAILURE;
		} else if (a.getState() == GitHubCheckerStatus.State.CANCELED || b.getState() == GitHubCheckerStatus.State.CANCELED) {
			mergedState = GitHubCheckerStatus.State.CANCELED;
		} else if (a.getState() == GitHubCheckerStatus.State.PENDING || b.getState() == GitHubCheckerStatus.State.PENDING) {
			mergedState = GitHubCheckerStatus.State.PENDING;
		} else if (a.getState() == GitHubCheckerStatus.State.SUCCESS && b.getState() == GitHubCheckerStatus.State.SUCCESS) {
			mergedState = GitHubCheckerStatus.State.SUCCESS;
		} else {
			LOG.warn("Unaccounted case for merging checker states. {} + {}", a.getState(), b.getState());
			mergedState = GitHubCheckerStatus.State.UNKNOWN;
		}
		return new GitHubCheckerStatus(mergedState, a.getDetailsUrl(), a.getCiProvider());
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
				comments.add(new GitHubComment(listReviewComment.getId(), listReviewComment.getBody(), listReviewComment::update));
			}
		}
		pullRequest.listReviewComments().forEach(reviewComment ->
				comments.add(new GitHubComment(reviewComment.getId(), reviewComment.getBody(), reviewComment::update)));
		return comments;
	}

	@Override
	public Stream<GitHubComment> getComments(String repositoryName, int pullRequestID, Pattern pattern) throws IOException {
		final GHRepository repository = gitHub.getRepository(repositoryName);
		final GHPullRequest pullRequest = repository.getPullRequest(pullRequestID);

		final List<GitHubComment> comments = new ArrayList<>();
		for (GHIssueComment listReviewComment : pullRequest.getComments()) {
			Matcher matcher = pattern.matcher(listReviewComment.getBody());
			if (matcher.find() || matcher.matches()) {
				comments.add(new GitHubComment(listReviewComment.getId(), listReviewComment.getBody(), listReviewComment::update));
			}
		}
		return comments.stream();
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
	public boolean isPullRequestClosed(String repositoryName, int pullRequestID) throws IOException {
		final GHRepository observedGitHubRepository = gitHub.getRepository(repositoryName);

		return observedGitHubRepository.getPullRequest(pullRequestID).getState() == GHIssueState.CLOSED;
	}

	@Override
	public void close() {
		try {
			cache.close();
		} catch (Exception e) {
			LOG.debug("Error while shutting down cache.", e);
		}
	}
}
