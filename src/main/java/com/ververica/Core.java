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
import com.ververica.ci.CiActions;
import com.ververica.ci.CiActionsContainer;
import com.ververica.ci.CiProvider;
import com.ververica.git.GitActions;
import com.ververica.git.GitException;
import com.ververica.github.CommitNotFoundException;
import com.ververica.github.GitHubActions;
import com.ververica.github.GitHubCheckerStatus;
import com.ververica.github.GitHubComment;
import com.ververica.github.GithubPullRequest;
import com.ververica.github.RateLimitInformation;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
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
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static com.ververica.utils.LogUtils.formatPullRequestID;

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

	private static final DateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");

	private final String observedRepository;
	private final String ciRepository;
	private final String username;
	private final String githubToken;
	private final GitActions gitActions;
	private final GitHubActions gitHubActions;
	private final CiActionsContainer ciActions;

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
			.maximumSize(1000)
			.expireAfterWrite(1, TimeUnit.HOURS)
			.build();

	public Core(String observedRepository, String ciRepository, String username, String githubToken, GitActions gitActions, GitHubActions gitHubActions, CiActionsContainer ciActions) throws Exception {
		this.observedRepository = observedRepository;
		this.ciRepository = ciRepository;
		this.username = username;
		this.githubToken = githubToken;
		this.gitActions = gitActions;
		this.gitHubActions = gitHubActions;
		this.ciActions = ciActions;

		setupGit(gitActions, observedRepository, ciRepository);
	}

	private static void setupGit(GitActions gitActions, String observedRepository, String ciRepository) throws GitException {
		gitActions.addRemote(getGitHubURL(observedRepository), REMOTE_NAME_OBSERVED_REPOSITORY);
		gitActions.addRemote(getGitHubURL(ciRepository), REMOTE_NAME_CI_REPOSITORY);

		gitActions.fetchBranch("master", REMOTE_NAME_OBSERVED_REPOSITORY, false);
	}

	/** Remove any unnecessary resources. */
	public void cleanup() {
		try {
			gitActions.cleanup();
		} catch (GitAPIException e) {
			LOG.debug("Error while cleaning up git.", e);
		}
	}

	public void logRateLimitInformation() {
		try {
			final RateLimitInformation rateLimitInformation = gitHubActions.getRateLimitInformation();
			LOG.info("Github Rate limit:\n\t" +
							"Remaining:  {}\n\t" +
							"Reset date: {}",
					rateLimitInformation.getRemainingRequests(),
					dateFormat.format(rateLimitInformation.getResetDate()));
		} catch (IOException e) {
			LOG.debug("Error while retrieving GH rate limit information.", e);
		}
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

		final int pullRequestID = parsedCiReport.getPullRequestID();
		Optional<GitHubComment> ciReport = getCiReportComment(pullRequestID);

		if (ciReport.isPresent()) {
			GitHubComment gitHubComment = ciReport.get();
			LOG.trace("Existing CI report:\n{}", gitHubComment.getCommentText());

			LOG.trace("New CI report:\n{}", comment);

			if (gitHubComment.getCommentText().equals(comment)) {
				LOG.debug("Skipping CI report update for pull request {} since it is up-to-date.", formatPullRequestID(pullRequestID));
			} else {
				LOG.info("Updating CI report for pull request {}.", formatPullRequestID(pullRequestID));
				gitHubComment.update(comment);
			}
		} else {
			LOG.info("Submitting new CI report for pull request {}.", formatPullRequestID(pullRequestID));
			gitHubActions.submitComment(observedRepository, pullRequestID, comment);
		}
	}

	private Optional<GitHubComment> getCiReportComment(int pullRequestID) throws IOException {
		LOG.debug("Retrieving CI report for pull request {}.", formatPullRequestID(pullRequestID));
		return StreamSupport.stream(gitHubActions.getComments(observedRepository, pullRequestID, username).spliterator(), false)
				.filter(comment -> CiReport.isCiReportComment(comment.getCommentText()))
				.findAny();
	}

	public boolean isPullRequestClosed(int pullRequestID) throws IOException {
		return gitHubActions.isPullRequestClosed(observedRepository, pullRequestID);
	}

	public void deleteCiBranch(Build finishedBuild) throws GitException {
		String ciBranchName = getCiBranchName(finishedBuild.pullRequestID, finishedBuild.commitHash);
		deleteCiBranch(ciBranchName);
	}

	public void deleteCiBranch(String ciBranchName) throws GitException {
		if (pendingBranchDeletions.getIfPresent(ciBranchName) != null) {
			LOG.debug("Ignoring deletion of {} due to being cached.", ciBranchName);
			return;
		} else {
			pendingBranchDeletions.put(ciBranchName, true);
		}
		LOG.info("Deleting branch {}.", ciBranchName);
		gitActions.deleteRemoteBranch(
				ciBranchName,
				REMOTE_NAME_CI_REPOSITORY,
				true,
				githubToken);
	}

	public Map<Integer, Collection<String>> getBranches() throws IOException {
		final Map<Integer, Collection<String>> branchesByPrID = new HashMap<>();
		gitHubActions.getBranches(ciRepository).forEach(branch -> {
			final Matcher matcher = REGEX_PATTERN_CI_BRANCH.matcher(branch);
			if (matcher.matches()) {
				final int pullRequestId = Integer.parseInt(matcher.group(REGEX_GROUP_PULL_REQUEST_ID));
				branchesByPrID
						.computeIfAbsent(pullRequestId, ignored -> new ArrayList<>())
						.add(branch);
			}
		});
		return branchesByPrID;
	}

	public Stream<GithubPullRequest> getPullRequests(Date lastUpdatedAtCutoff, Set<Integer> pullRequestWithPendingBuilds) throws IOException {
		LOG.info("Retrieving observed repository state ({}).", observedRepository);

		final Map<Integer, GithubPullRequest> pullRequestsToProcessByID = new TreeMap<>(Integer::compareTo);

		pullRequestsToProcessByID.putAll(getRecentlyUpdatedPullRequests(lastUpdatedAtCutoff));

		deriveActivePrsFromCIBranches().forEach(pr  ->{
			if (pullRequestWithPendingBuilds.contains(pr.getID())) {
				pullRequestsToProcessByID.putIfAbsent(pr.getID(), pr);
			}
		});

		return pullRequestsToProcessByID.values().stream();
	}

	private Map<Integer, GithubPullRequest> getRecentlyUpdatedPullRequests(Date lastUpdatedAtCutoff) throws IOException {
		return gitHubActions.getRecentlyUpdatedOpenPullRequests(observedRepository, lastUpdatedAtCutoff)
				.collect(Collectors.toMap(GithubPullRequest::getID, pr -> pr));
	}

	private Stream<GithubPullRequest> deriveActivePrsFromCIBranches() throws IOException {
		return gitHubActions.getBranches(ciRepository)
				.map(REGEX_PATTERN_CI_BRANCH::matcher)
				.filter(Matcher::matches)
				.map(matcher -> new GithubPullRequest(
						Integer.parseInt(matcher.group(REGEX_GROUP_PULL_REQUEST_ID)),
						Date.from(Instant.now()),
						matcher.group(REGEX_GROUP_COMMIT_HASH)))
				.collect(Collectors.toMap(GithubPullRequest::getID, pr -> pr, (first, ignored) -> first)).values().stream();
	}

	private static boolean requiresCheckerStatusRetrieval(Build build) {
		return build.status
				.map(GitHubCheckerStatus::getState)
				.map(state -> {
					switch (state) {
						case PENDING: // we need the current state
						case UNKNOWN: // we need the build url
						case FAILURE: // failed runs may be retried
							return true;
						default:
							return false;
					}
				})
				.orElse(false);
	}

	public CiReport processPullRequest(GithubPullRequest pullRequest) throws IOException {
		LOG.debug("Processing PR {}@{}.", formatPullRequestID(pullRequest.getID()), pullRequest.getHeadCommitHash());
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
			final Collection<Build> buildsToRemove = new ArrayList<>();
			ciReport.getBuilds()
					.filter(Core::requiresCheckerStatusRetrieval)
					.forEach(build -> {
						String commitHash = build.commitHash;

						LOG.debug("Checking commit state for {}.", commitHash);

						// CI services may override previous checker runs (e.g., in case of a manual build)
						// make sure we also try to retrieve the state for the original details URL
						final AtomicBoolean originalUrlWasProcessed = new AtomicBoolean(false);
						final Consumer<GitHubCheckerStatus> checkerStatusProcessor = gitHubCheckerStatus -> {
							if (gitHubCheckerStatus.getDetailsUrl().equals(build.status.get().getDetailsUrl())) {
								originalUrlWasProcessed.set(true);
							}

							// try retrieving the state directly from the CiProvider, as they tend to be more accurate
							final Optional<GitHubCheckerStatus> directlyRetrievedStatus = ciActions
									.getActionsForProvider(gitHubCheckerStatus.getCiProvider())
									.filter(CiActions::supportsDirectBuildStatusRetrieval)
									.flatMap(ciActions -> ciActions.getBuildStatus(gitHubCheckerStatus.getDetailsUrl()))
									.map(status -> new GitHubCheckerStatus(status, gitHubCheckerStatus.getDetailsUrl(), gitHubCheckerStatus.getCiProvider()));

							directlyRetrievedStatus.ifPresent(status -> LOG.trace("Retrieved status {} for {} ({}@{}) from {}.", status.getState(), status.getDetailsUrl(), build.pullRequestID, build.commitHash, build.status.get().getCiProvider().getName()));

							final GitHubCheckerStatus finalGitHubCheckerStatus = directlyRetrievedStatus.orElse(gitHubCheckerStatus);

							if (finalGitHubCheckerStatus.getState() != build.status.get().getState()) {
								LOG.trace("Updating state for {}@{} from {} to {}.", build.pullRequestID, build.commitHash, build.status.get().getState(), finalGitHubCheckerStatus.getState());
								buildsToAdd.add(new Build(build.pullRequestID, build.commitHash, Optional.of(finalGitHubCheckerStatus), build.trigger));
							} else {
								LOG.trace("Unchanged state for {}@{} at {}.", build.pullRequestID, build.commitHash, finalGitHubCheckerStatus.getState());
							}
						};

						Iterable<GitHubCheckerStatus> commitState;
						try {
						  commitState = gitHubActions.getCommitState(ciRepository, commitHash);
						} catch (CommitNotFoundException cnfe) {
							buildsToRemove.add(build);
							return;
						}
						StreamSupport.stream(commitState.spliterator(), false)
								.filter(status -> status.getCiProvider() != CiProvider.Unknown)
								.forEach(checkerStatusProcessor);

						if (!originalUrlWasProcessed.get()) {
							checkerStatusProcessor.accept(build.status.get());
						}
					});
			buildsToAdd.forEach(ciReport::add);
			buildsToRemove.forEach(ciReport::remove);

			processManualTriggers(ciReport, pullRequestID)
					.map(triggerComment -> new Build(
							pullRequestID,
							headCommitHash,
							Optional.empty(),
							new Trigger(Trigger.Type.MANUAL, String.valueOf(triggerComment.getCommentId()), triggerComment.getCommand())
					))
					.forEach(ciReport::add);
		} else {
			LOG.debug("No CIReport comment found.");
			ciReport = CiReport.empty(pullRequestID);
		}

		if (!reportedCommitHashes.contains(headCommitHash)) {
			ciReport.add(new Build(pullRequestID, headCommitHash, Optional.empty(), new Trigger(Trigger.Type.PUSH, headCommitHash, null)));
		}
		return ciReport;
	}

	private Stream<TriggerComment> processManualTriggers(CiReport ciReport, int pullRequestID) {
		final Stream<GitHubComment> comments;
		try {
			comments = gitHubActions.getComments(observedRepository, pullRequestID, REGEX_PATTERN_COMMAND_MENTION);
		} catch (IOException e) {
			LOG.debug("Could not retrieve comments for pull request {}.", formatPullRequestID(pullRequestID), e);
			return Stream.empty();
		}

		Set<Long> processedComments = ciReport.getBuilds()
				.map(build -> build.trigger)
				.filter(trigger -> trigger.getType() == Trigger.Type.MANUAL)
				.map(Trigger::getId)
				.mapToLong(Long::parseLong)
				.boxed()
				.collect(Collectors.toSet());

		LOG.debug("Processed comments: {}.", processedComments);

		return comments
				.filter(gitHubComment -> !CiReport.isCiReportComment(gitHubComment.getCommentText()))
				.peek(comment -> LOG.trace("Processing comment {}.", comment.getId()))
				.filter(comment -> !processedComments.contains(comment.getId()))
				.filter(comment -> {
					if (pendingTriggers.getIfPresent(comment.getId()) != null) {
						LOG.debug("Ignoring trigger {} due to being cached.", comment.getId());
						return false;
					}
					return true;
				})
				.flatMap(comment -> {
					final Matcher matcher = REGEX_PATTERN_COMMAND_MENTION.matcher(comment.getCommentText());
					if (matcher.find()) {
						return Stream.of(new TriggerComment(comment.getId(), matcher.group(REGEX_GROUP_COMMAND)));
					}
					return Stream.empty();
				});
	}

	public Optional<Build> runBuild(CiReport ciReport, Build build) {
		switch (build.trigger.getType()) {
			case PUSH:
				return runPushBuild(build);
			case MANUAL:
				return runManualBuild(build.trigger, ciReport);

		}
		return Optional.empty();
	}

	private Optional<Build> runPushBuild(Build build) {
		mirrorPullRequest(build.pullRequestID);
		return Optional.of(new Build(
				build.pullRequestID,
				build.commitHash,
				Optional.of(new GitHubCheckerStatus(GitHubCheckerStatus.State.UNKNOWN, "TBD", CiProvider.Unknown)),
				build.trigger));
	}

	private Optional<Build> runManualBuild(Trigger trigger, CiReport ciReport) {
		final String[] command = trigger.getCommand().get().split(" ");

		final AzureCommand azureCommand = new AzureCommand();

		JCommander jCommander = new JCommander();
		jCommander.addCommand(azureCommand);

		try {
			jCommander.parse(command);
		} catch (Exception e) {
			LOG.warn("Invalid command ({}), ignoring.", command);
			return Optional.empty();
		}

		switch (jCommander.getParsedCommand()) {
			case AzureCommand.COMMAND_NAME:
				return runManualBuild(CiProvider.Azure, ciReport, trigger, azureCommand.args);
			default:
				throw new RuntimeException("Unhandled valid command " + Arrays.toString(command) + " .");
		}
	}

	private Optional<Build> runManualBuild(CiProvider ciProvider, CiReport ciReport, Trigger trigger, List<String> arguments) {
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

				return ciActions.getActionsForProvider(gitHubCheckerStatus.getCiProvider())
						.map(ciAction -> {
							ciAction.retryBuild(
										gitHubCheckerStatus.getDetailsUrl(),
										getCiBranchName(lastBuild.pullRequestID, lastBuild.commitHash));
							return gitHubCheckerStatus.getDetailsUrl();
						}
						)
						.map(url -> new Build(
								lastBuild.pullRequestID,
								lastBuild.commitHash,
								Optional.of(new GitHubCheckerStatus(
										GitHubCheckerStatus.State.PENDING,
										url,
										gitHubCheckerStatus.getCiProvider())),
								trigger));
			}
		}
		return Optional.empty();
	}

	public void mirrorPullRequest(int pullRequestID) throws GitException {
		if (pendingMirrors.getIfPresent(pullRequestID) != null) {
			LOG.debug("Ignoring mirroring for {} due to being cached.", formatPullRequestID(pullRequestID));
			return;
		}
		pendingMirrors.put(pullRequestID, true);

		LOG.info("Mirroring PullRequest {}.", pullRequestID);

		gitActions.fetchBranch(String.valueOf(pullRequestID), REMOTE_NAME_OBSERVED_REPOSITORY, true);

		// the PR may have been updated in between the state fetch and this point
		// determine actual HEAD commit
		String commitHash = gitActions.getHeadCommitSHA(String.valueOf(pullRequestID));
		LOG.debug("Using commitHash {} for PR {}.", commitHash, formatPullRequestID(pullRequestID));

		LOG.info("Pushing PullRequest {}.", formatPullRequestID(pullRequestID));
		gitActions.pushBranch(
				String.valueOf(pullRequestID),
				getCiBranchName(pullRequestID, commitHash),
				REMOTE_NAME_CI_REPOSITORY,
				false,
				githubToken);

		gitActions.deleteLocalBranch(
				String.valueOf(pullRequestID),
				true);
	}

	public void cancelBuild(Build buildToCancel) {
		if (buildToCancel.status.isPresent()) {
			final GitHubCheckerStatus status = buildToCancel.status.get();
			if (recentCancellations.getIfPresent(status.getDetailsUrl()) != null) {
				LOG.debug("Ignoring cancellation {}@{} due to being cached.", formatPullRequestID(buildToCancel.pullRequestID), buildToCancel.commitHash);
				return;
			}
			recentCancellations.put(status.getDetailsUrl(), true);
			LOG.info("Canceling build {}@{}.", formatPullRequestID(buildToCancel.pullRequestID), buildToCancel.commitHash);
			ciActions.getActionsForProvider(status.getCiProvider()).ifPresent(ciAction -> ciAction.cancelBuild(status.getDetailsUrl()));
		}
	}

	private static String getCiBranchName(long pullRequestID, String commitHash) {
		return "ci_" + pullRequestID + "_" + commitHash;
	}

	private static String getGitHubURL(String repository) {
		return "https://github.com/" + repository + ".git";
	}

	@Parameters(commandNames = AzureCommand.COMMAND_NAME)
	private static final class AzureCommand {
		static final String COMMAND_NAME = "azure";

		@Parameter
		private List<String> args = Collections.emptyList();
	}
}
