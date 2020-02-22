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

package com.ververica;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.ververica.ci.CiActionsContainer;
import com.ververica.ci.CiProvider;
import com.ververica.git.GitActions;
import com.ververica.github.GitHubActions;
import com.ververica.github.GitHubCheckerStatus;
import com.ververica.github.GitHubComment;
import com.ververica.github.GithubPullRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * A bot that mirrors pull requests opened against one repository (so called "observed repository") to branches in
 * another repository (so called "ci repository"), and report back the Checker status once the checks have completed.
 */
public class Core implements AutoCloseable {

	private static final Logger LOG = LoggerFactory.getLogger(Core.class);

	private static final String REMOTE_NAME_OBSERVED_REPOSITORY = "observed";
	private static final String REMOTE_NAME_CI_REPOSITORY = "ci";

	private static final String REGEX_GROUP_PULL_REQUEST_ID = "PullRequestID";
	private static final String REGEX_GROUP_COMMIT_HASH = "CommitHash";
	private static final Pattern REGEX_PATTERN_CI_BRANCH = Pattern.compile(
			"ci_(?<" + REGEX_GROUP_PULL_REQUEST_ID + ">[0-9]+)_(?<" + REGEX_GROUP_COMMIT_HASH + ">[0-9a-f]+)", Pattern.DOTALL);

	private static final String REGEX_GROUP_COMMAND = "command";
	private static final Pattern REGEX_PATTERN_COMMAND_MENTION = Pattern.compile("@flinkbot run (?<" + REGEX_GROUP_COMMAND + ">[\\w ]+)", Pattern.CASE_INSENSITIVE);

	private final String observedRepository;
	private final String ciRepository;
	private final String username;
	private final String githubToken;
	private final GitActions gitActions;
	private final GitHubActions gitHubActions;
	private final CiActionsContainer ciActions;

	private final Pattern githubCheckerNamePattern;

	private final Cache<Integer, Boolean> pendingMirrors = CacheBuilder.newBuilder()
			.maximumSize(50)
			.expireAfterWrite(1, TimeUnit.HOURS)
			.build();

	private final Cache<Long, Boolean> pendingTriggers = CacheBuilder.newBuilder()
			.maximumSize(1000)
			.expireAfterWrite(1, TimeUnit.HOURS)
			.build();

	private final Cache<String, Boolean> recentCancellations = CacheBuilder.newBuilder()
			.maximumSize(50)
			.expireAfterWrite(1, TimeUnit.HOURS)
			.build();

	private final Cache<String, Boolean> pendingBranchDeletions = CacheBuilder.newBuilder()
			.maximumSize(50)
			.expireAfterWrite(1, TimeUnit.HOURS)
			.build();

	private final Cache<Long, Boolean> pendingCiReportUpdates = CacheBuilder.newBuilder()
			.maximumSize(1000)
			.expireAfterWrite(1, TimeUnit.HOURS)
			.build();

	public Core(String observedRepository, String ciRepository, String username, String githubToken, GitActions gitActions, GitHubActions gitHubActions, CiActionsContainer ciActions, String gitHubCheckerNameFilter) throws Exception {
		this.observedRepository = observedRepository;
		this.ciRepository = ciRepository;
		this.username = username;
		this.githubToken = githubToken;
		this.gitActions = gitActions;
		this.gitHubActions = gitHubActions;
		this.ciActions = ciActions;
		this.githubCheckerNamePattern = Pattern.compile(gitHubCheckerNameFilter);

		setupGit(gitActions, observedRepository, ciRepository);
	}

	private static void setupGit(GitActions gitActions, String observedRepository, String ciRepository) throws Exception {
		gitActions.addRemote(getGitHubURL(observedRepository), REMOTE_NAME_OBSERVED_REPOSITORY);
		gitActions.addRemote(getGitHubURL(ciRepository), REMOTE_NAME_CI_REPOSITORY);

		gitActions.fetchBranch("master", REMOTE_NAME_OBSERVED_REPOSITORY, false);
	}

	@Override
	public void close() {
		gitActions.close();
		gitHubActions.close();
		ciActions.close();
		LOG.info("Shutting down.");
	}

	public void updateCiReport(final CiReport parsedCiReport) throws IOException {
		final String comment = parsedCiReport.toString();
		final long cacheKey = (long) parsedCiReport.getPullRequestID() << 32 | comment.hashCode();
		if (pendingCiReportUpdates.getIfPresent(cacheKey) != null) {
			LOG.debug("Ignoring ci report update for PR {} due to being cached.", parsedCiReport.getPullRequestID());
			return;
		}
		pendingCiReportUpdates.put(cacheKey, true);

		final int pullRequestID = parsedCiReport.getPullRequestID();
		Optional<GitHubComment> ciReport = getCiReportComment(pullRequestID);

		if (ciReport.isPresent()) {
			GitHubComment gitHubComment = ciReport.get();
			LOG.trace("Existing CI report:\n{}", gitHubComment.getCommentText());

			LOG.trace("New CI report:\n{}", comment);

			if (gitHubComment.getCommentText().equals(comment)) {
				LOG.debug("Skipping CI report update for pull request {} since it is up-to-date.", pullRequestID);
			} else {
				LOG.info("Updating CI report for pull request {}.", pullRequestID);
				gitHubComment.update(comment);
			}
		} else {
			LOG.info("Submitting new CI report for pull request {}.", pullRequestID);
			gitHubActions.submitComment(observedRepository, pullRequestID, comment);
		}
	}

	private Optional<GitHubComment> getCiReportComment(int pullRequestID) throws IOException {
		LOG.info("Retrieving CI report for pull request {}.", pullRequestID);
		return StreamSupport.stream(gitHubActions.getComments(observedRepository, pullRequestID, username).spliterator(), false)
				.filter(comment -> CiReport.isCiReportComment(comment.getCommentText()))
				.findAny();
	}

	public boolean isPullRequestClosed(int pullRequestID) throws IOException {
		return gitHubActions.isPullRequestClosed(observedRepository, pullRequestID);
	}

	public void deleteCiBranch(Build finishedBuild) throws Exception {
		String ciBranchName = getCiBranchName(finishedBuild.pullRequestID, finishedBuild.commitHash);
		if (pendingBranchDeletions.getIfPresent(ciBranchName) != null) {
			LOG.debug("Ignoring deletion of {} due to being cached.", ciBranchName);
			return;
		} else {
			pendingBranchDeletions.put(ciBranchName, true);
		}
		LOG.info("Deleting CI branch for {}@{}.", finishedBuild.pullRequestID, finishedBuild.commitHash);
		gitActions.deleteBranch(
				ciBranchName,
				REMOTE_NAME_CI_REPOSITORY,
				true,
				githubToken);
	}

	public ObservedState fetchGithubState(Date lastUpdatedAtCutoff) throws IOException {
		LOG.info("Retrieving observed repository state ({}).", observedRepository);

		Iterable<GithubPullRequest> recentlyUpdatedOpenPullRequests = gitHubActions.getRecentlyUpdatedOpenPullRequests(observedRepository, lastUpdatedAtCutoff);
		Map<Integer, GithubPullRequest> pullRequestsToProcessByID = new HashMap<>();
		recentlyUpdatedOpenPullRequests.forEach(pr -> pullRequestsToProcessByID.put(pr.getID(), pr));
		StreamSupport.stream(gitHubActions.getBranches(ciRepository).spliterator(), false)
				.map(REGEX_PATTERN_CI_BRANCH::matcher)
				.filter(Matcher::matches)
				.map(matcher -> new GithubPullRequest(
						Integer.parseInt(matcher.group(REGEX_GROUP_PULL_REQUEST_ID)),
						Date.from(Instant.now()),
						matcher.group(REGEX_GROUP_COMMIT_HASH)))
				.filter(pr -> !pullRequestsToProcessByID.containsKey(pr.getID()))
				.forEach(pr -> pullRequestsToProcessByID.put(pr.getID(), pr));

		final List<CiReport> ciReports = new ArrayList<>();
		for (GithubPullRequest pullRequest : pullRequestsToProcessByID.values()) {
			LOG.debug("Processing PR{}@{},", pullRequest.getID(), pullRequest.getHeadCommitHash());
			final int pullRequestID = pullRequest.getID();
			final String headCommitHash = pullRequest.getHeadCommitHash();
			final Collection<String> reportedCommitHashes = new ArrayList<>();

			Optional<GitHubComment> ciReportComment = getCiReportComment(pullRequestID);
			final CiReport ciReport;
			if (ciReportComment.isPresent()) {
				LOG.debug("CiReport comment found.");
				ciReport = CiReport.fromComment(pullRequestID, ciReportComment.get().getCommentText(), ciActions);
				ciReport.getBuilds().map(build -> build.commitHash).forEach(reportedCommitHashes::add);

				final Collection<Build> buildsToAdd = new ArrayList<>();
				ciReport.getBuilds()
						.filter(build -> build.status.isPresent())
						.filter(build -> build.status.get().getState() == GitHubCheckerStatus.State.PENDING || build.status.get().getState() == GitHubCheckerStatus.State.UNKNOWN)
						.forEach(build -> {
							String commitHash = build.commitHash;

							LOG.debug("Checking commit state for {}.", commitHash);
							Iterable<GitHubCheckerStatus> commitState = gitHubActions.getCommitState(ciRepository, commitHash, githubCheckerNamePattern);
							StreamSupport.stream(commitState.spliterator(), false)
									.filter(status -> status.getCiProvider() != CiProvider.Unknown)
									.forEach(gitHubCheckerStatus -> {
										if (gitHubCheckerStatus.getState() != build.status.get().getState()) {
											buildsToAdd.add(new Build(build.pullRequestID, build.commitHash, Optional.of(gitHubCheckerStatus), build.trigger));
										}
									});
						});
				buildsToAdd.forEach(ciReport::add);

				processManualTriggers(ciReport, pullRequestID);
			} else {
				LOG.debug("No CIReport comment found.");
				ciReport = CiReport.empty(pullRequestID);
			}

			if (!reportedCommitHashes.contains(headCommitHash)) {
				ciReport.add(new Build(pullRequestID, headCommitHash, Optional.empty(), new Trigger(Trigger.Type.PUSH, headCommitHash)));
			}
			ciReports.add(ciReport);
		}

		return new ObservedState(ciReports);
	}

	private void processManualTriggers(CiReport ciReport, int pullRequestID) {
		final Stream<GitHubComment> comments;
		try {
			comments = gitHubActions.getComments(observedRepository, pullRequestID, REGEX_PATTERN_COMMAND_MENTION);
		} catch (IOException e) {
			LOG.debug("Could not retrieve comments for pull request {}.", pullRequestID, e);
			return;
		}

		Set<Long> processedComments = ciReport.getBuilds()
				.map(build -> build.trigger)
				.filter(trigger -> trigger.getType() == Trigger.Type.MANUAL)
				.map(Trigger::getId)
				.mapToLong(Long::parseLong)
				.boxed()
				.collect(Collectors.toSet());

		LOG.debug("Processed comments: {}.", processedComments);

		comments.filter(gitHubComment -> !CiReport.isCiReportComment(gitHubComment.getCommentText())).forEach(comment -> {
			LOG.trace("Processing comment {}.", comment.getId());
			if (processedComments.contains(comment.getId())) {
				return;
			}
			if (pendingTriggers.getIfPresent(comment.getId()) != null) {
				LOG.debug("Ignoring trigger {} due to being cached.", comment.getId());
				return;
			}
			pendingTriggers.put(comment.getId(), true);

			final Matcher matcher = REGEX_PATTERN_COMMAND_MENTION.matcher(comment.getCommentText());
			if (matcher.find()) {
				final String[] command = matcher.group(REGEX_GROUP_COMMAND).split(" ");

				final AzureCommand azureCommand = new AzureCommand();

				JCommander jCommander = new JCommander();
				jCommander.addCommand(new TravisCommand());
				jCommander.addCommand(azureCommand);

				try {
					jCommander.parse(command);
				} catch (Exception e) {
					LOG.warn("Invalid command ({}), ignoring.", command);
					return;
				}

				switch (jCommander.getParsedCommand()) {
					case AzureCommand.COMMAND_NAME:
						runBuild(CiProvider.Azure, ciReport, comment, azureCommand.args);
						break;
					case TravisCommand.COMMAND_NAME:
						runBuild(CiProvider.Travis, ciReport, comment, Collections.emptyList());
						break;
					default:
						throw new RuntimeException("Unhandled valid command " + Arrays.toString(command) + " .");
				}
				pendingTriggers.put(comment.getId(), true);
			}
		});
	}

	private void runBuild(CiProvider ciProvider, CiReport ciReport, GitHubComment comment, List<String> arguments) {
		Optional<Build> lastBuildOptional = ciReport.getBuilds()
				.filter(build -> build.status.map(s -> s.getCiProvider() == ciProvider).orElse(false))
				.reduce((first, second) -> second);
		if (!lastBuildOptional.isPresent()) {
			LOG.debug("Ignoring {} run command since no build was triggered yet.", ciProvider.getName());
		} else {
			Build lastBuild = lastBuildOptional.get();
			if (!lastBuild.status.isPresent()) {
				LOG.debug("Ignoring {} run command since no build was triggered yet.", ciProvider.getName());
			} else {
				GitHubCheckerStatus gitHubCheckerStatus = lastBuild.status.get();
				Optional<String> newUrl = ciActions.getActionsForProvider(gitHubCheckerStatus.getCiProvider())
						.flatMap(ciAction -> ciAction.runBuild(
						gitHubCheckerStatus.getDetailsUrl(),
						getCiBranchName(lastBuild.pullRequestID, lastBuild.commitHash),
						arguments
				));
				newUrl.ifPresent(url -> ciReport.add(new Build(
						lastBuild.pullRequestID,
						lastBuild.commitHash,
						Optional.of(new GitHubCheckerStatus(
								GitHubCheckerStatus.State.PENDING,
								url,
								gitHubCheckerStatus.getCiProvider())),
						new Trigger(Trigger.Type.MANUAL, String.valueOf(comment.getId())))));
			}
		}
	}

	public void mirrorPullRequest(int pullRequestID) throws Exception {
		if (pendingMirrors.getIfPresent(pullRequestID) != null) {
			LOG.debug("Ignoring mirroring for {} due to being cached.", pullRequestID);
			return;
		}
		pendingMirrors.put(pullRequestID, true);

		LOG.info("Mirroring PullRequest {}.", pullRequestID);

		gitActions.fetchBranch(String.valueOf(pullRequestID), REMOTE_NAME_OBSERVED_REPOSITORY, true);

		// the PR may have been updated in between the state fetch and this point
		// determine actual HEAD commit
		String commitHash = gitActions.getHeadCommitSHA(String.valueOf(pullRequestID));
		LOG.debug("Using commitHash {} for PR {}.", commitHash, pullRequestID);

		LOG.info("Pushing PullRequest {}.", pullRequestID);
		gitActions.pushBranch(
				String.valueOf(pullRequestID),
				getCiBranchName(pullRequestID, commitHash),
				REMOTE_NAME_CI_REPOSITORY,
				false,
				githubToken);

		gitActions.deleteBranch(
				String.valueOf(pullRequestID),
				true);
	}

	public void cancelBuild(Build buildToCancel) {
		if (buildToCancel.status.isPresent()) {
			final GitHubCheckerStatus status = buildToCancel.status.get();
			if (recentCancellations.getIfPresent(status.getDetailsUrl()) != null) {
				LOG.debug("Ignoring cancellation {}@{} due to being cached.", buildToCancel.pullRequestID, buildToCancel.commitHash);
				return;
			}
			recentCancellations.put(status.getDetailsUrl(), true);
			LOG.info("Canceling build {}@{}.", buildToCancel.pullRequestID, buildToCancel.commitHash);
			ciActions.getActionsForProvider(status.getCiProvider()).ifPresent(ciAction -> ciAction.cancelBuild(status.getDetailsUrl()));
		}
	}

	private static String getCiBranchName(long pullRequestID, String commitHash) {
		return "ci_" + pullRequestID + "_" + commitHash;
	}

	private static String getGitHubURL(String repository) {
		return "https://github.com/" + repository + ".git";
	}

	@Parameters(commandNames = TravisCommand.COMMAND_NAME)
	private static final class TravisCommand {
		static final String COMMAND_NAME = "travis";
	}

	@Parameters(commandNames = AzureCommand.COMMAND_NAME)
	private static final class AzureCommand {
		static final String COMMAND_NAME = "azure";

		@Parameter
		private List<String> args = Collections.emptyList();
	}
}
