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

import com.ververica.git.GitActions;
import com.ververica.github.GitHubActions;
import com.ververica.github.GitHubCheckerStatus;
import com.ververica.github.GitHubComment;
import com.ververica.github.GithubPullRequest;
import com.ververica.travis.TravisActions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
	private static final String REGEX_GROUP_BUILD_STATUS = "BuildStatus";
	private static final String REGEX_GROUP_BUILD_URL = "URL";
	private static final Pattern REGEX_PATTERN_CI_BRANCH = Pattern.compile(
			"ci_(?<" + REGEX_GROUP_PULL_REQUEST_ID + ">[0-9]+)_(?<" + REGEX_GROUP_COMMIT_HASH + ">[0-9a-f]+)", Pattern.DOTALL);

	private static final String TEMPLATE_MESSAGE = "" +
			"## CI report:\n" +
			"\n" +
			"%s";
	private static final String TEMPLATE_MESSAGE_LINE = "* %s : %s [Build](%s)\n";

	private static final Pattern REGEX_PATTERN_CI_REPORT_LINES = Pattern.compile(String.format(escapeRegex(TEMPLATE_MESSAGE_LINE),
			"(?<" + REGEX_GROUP_COMMIT_HASH + ">[0-9a-f]+)",
			"(?<" + REGEX_GROUP_BUILD_STATUS + ">[A-Z]+)",
			"(?<" + REGEX_GROUP_BUILD_URL + ">.+)"));

	private final String observedRepository;
	private final String ciRepository;
	private final String username;
	private final String githubToken;
	private final GitActions gitActions;
	private final GitHubActions gitHubActions;
	private final TravisActions travisActions;
	private int operationDelay;

	public Core(String observedRepository, String ciRepository, String username, String githubToken, GitActions gitActions, GitHubActions gitHubActions, TravisActions travisActions, int operationDelay) throws Exception {
		this.observedRepository = observedRepository;
		this.ciRepository = ciRepository;
		this.username = username;
		this.githubToken = githubToken;
		this.gitActions = gitActions;
		this.gitHubActions = gitHubActions;
		this.travisActions = travisActions;
		this.operationDelay = operationDelay;

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
		travisActions.close();
		LOG.info("Shutting down.");
	}

	public CIState fetchCiState() throws Exception {
		LOG.info("Retrieving CI repository state ({}).", ciRepository);

		final List<Build> pendingBuilds = new ArrayList<>();
		final List<Build> finishedBuilds = new ArrayList<>();
		for (String branch : gitHubActions.getBranches(ciRepository)) {
			Matcher matcher = REGEX_PATTERN_CI_BRANCH.matcher(branch);
			if (matcher.matches()) {
				String commitHash = matcher.group(REGEX_GROUP_COMMIT_HASH);
				int pullRequestID = Integer.parseInt(matcher.group(REGEX_GROUP_PULL_REQUEST_ID));

				Iterable<GitHubCheckerStatus> commitState = gitHubActions.getCommitState(ciRepository, commitHash);
				Optional<GitHubCheckerStatus> travisCheck = StreamSupport.stream(commitState.spliterator(), false)
						.filter(status -> status.getName().contains("Travis CI"))
						.findAny();

				if (!travisCheck.isPresent()) {
					LOG.warn("CI branch {} had no Travis check attached.", getCiBranchName(pullRequestID, commitHash));
					// we can't ignore simply these as otherwise we will mirror these pull requests again
					pendingBuilds.add(new Build(pullRequestID, commitHash, Optional.empty()));
				} else {
					GitHubCheckerStatus gitHubCheckerStatus = travisCheck.get();

					Build build = new Build(pullRequestID, commitHash, Optional.of(gitHubCheckerStatus));
					if (gitHubCheckerStatus.getState() == GitHubCheckerStatus.State.PENDING) {
						pendingBuilds.add(build);
					} else {
						finishedBuilds.add(build);
					}
				}
			}
			Thread.sleep(operationDelay);
		}

		final CIState ciState = new CIState(pendingBuilds, finishedBuilds);
		LOG.info(ciState.toString());
		return ciState;
	}

	public void updateCiReport(int pullRequestID, List<Build> builds) throws IOException {
		Map<String, String> reportsPerCommit = new LinkedHashMap<>();
		for (Build build : builds) {
			GitHubCheckerStatus status = build.status.get();
			String commitHash = build.commitHash;
			reportsPerCommit.put(commitHash, String.format(TEMPLATE_MESSAGE_LINE, commitHash, status.getState(), status.getDetailsUrl()));
		}
		logReports(String.format("New reports for pull request %s:", pullRequestID), reportsPerCommit);

		Optional<GitHubComment> ciReport = getCiReportComment(pullRequestID);

		if (ciReport.isPresent()) {
			GitHubComment gitHubComment = ciReport.get();
			LOG.trace("Existing CI report:\n{}", gitHubComment.getCommentText());

			Map<String, String> existingReportsPerCommit = extractCiReport(gitHubComment);

			existingReportsPerCommit.putAll(reportsPerCommit);

			String comment = String.format(TEMPLATE_MESSAGE, String.join("", existingReportsPerCommit.values()));
			LOG.trace("New CI report:\n{}", comment);

			if (gitHubComment.getCommentText().equals(comment)) {
				LOG.debug("Skipping CI report update for pull request {} since it is up-to-date.", pullRequestID);
			} else {
				LOG.info("Updating CI report for pull request {}.", pullRequestID);
				gitHubComment.update(comment);
			}
		} else {
			String comment = String.format(TEMPLATE_MESSAGE, String.join("", reportsPerCommit.values()));
			LOG.info("Submitting new CI report for pull request {}.", pullRequestID);
			gitHubActions.submitComment(observedRepository, pullRequestID, comment);
		}
	}

	private Optional<GitHubComment> getCiReportComment(int pullRequestID) throws IOException {
		LOG.info("Retrieving CI report for pull request {}.", pullRequestID);
		return StreamSupport.stream(gitHubActions.getComments(observedRepository, pullRequestID, username).spliterator(), false)
				.filter(comment -> REGEX_PATTERN_CI_REPORT_LINES.matcher(comment.getCommentText()).find())
				.findAny();
	}

	private static Map<String, String> extractCiReport(GitHubComment ciReportComment) {
		Map<String, String> ciReport = new LinkedHashMap<>();

		Matcher matcher = REGEX_PATTERN_CI_REPORT_LINES.matcher(ciReportComment.getCommentText());
		while (matcher.find()) {
			ciReport.put(matcher.group(REGEX_GROUP_COMMIT_HASH), matcher.group(0));
		}
		return ciReport;
	}

	public void deleteCiBranch(Build finishedBuild) throws Exception {
		LOG.info("Deleting CI branch for {}@{}.", finishedBuild.pullRequestID, finishedBuild.commitHash);
		gitActions.deleteBranch(
				getCiBranchName(finishedBuild.pullRequestID, finishedBuild.commitHash),
				REMOTE_NAME_CI_REPOSITORY,
				true,
				githubToken);
	}

	public ObservedState fetchGithubState(Date lastUpdatedAtCutoff) throws IOException {
		LOG.info("Retrieving observed repository state ({}).", observedRepository);

		final List<Build> pullRequestsRequiringBuild = new ArrayList<>();
		List<Build> pendingBuilds = new ArrayList<>();
		List<Build> finishedBuilds = new ArrayList<>();
		for (GithubPullRequest pullRequest : gitHubActions.getRecentlyUpdatedOpenPullRequests(observedRepository, lastUpdatedAtCutoff)) {
			final int pullRequestID = pullRequest.getID();
			final String headCommitHash = pullRequest.getHeadCommitHash();
			final Collection<String> reportedCommitHashes = new ArrayList<>();

			Optional<GitHubComment> ciReport = getCiReportComment(pullRequestID);
			ciReport.ifPresent(comment -> {
				Map<String, String> reports = extractCiReport(comment);
				for (String report : reports.values()) {
					Matcher matcher = REGEX_PATTERN_CI_REPORT_LINES.matcher(report);
					if (matcher.matches()) {
						String commitHash = matcher.group(REGEX_GROUP_COMMIT_HASH);
						String status = matcher.group(REGEX_GROUP_BUILD_STATUS);
						if (GitHubCheckerStatus.State.valueOf(status) == GitHubCheckerStatus.State.PENDING) {
							pendingBuilds.add(new Build(pullRequestID, commitHash, Optional.empty()));
						} else {
							finishedBuilds.add(new Build(pullRequestID, commitHash, Optional.empty()));
						}
					}
				}
				reportedCommitHashes.addAll(reports.keySet());
			});

			if (!reportedCommitHashes.contains(headCommitHash)) {
				pullRequestsRequiringBuild.add(new Build(pullRequestID, headCommitHash, Optional.empty()));
			}
		}

		return new ObservedState(pullRequestsRequiringBuild, pendingBuilds, finishedBuilds);
	}

	public Build mirrorPullRequest(int pullRequestID) throws Exception {
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

		return new Build(pullRequestID, commitHash, Optional.empty());
	}

	public void cancelBuild(Build buildToCancel) {
		final GitHubCheckerStatus status = buildToCancel.status.get();
		LOG.info("Canceling build {}@{}.", buildToCancel.pullRequestID, buildToCancel.commitHash);
		travisActions.cancelBuild(status.getDetailsUrl());
	}

	private static void logReports(String prefix, Map<String, String> reports) {
		final StringWriter sw = new StringWriter();
		try (PrintWriter pw = new PrintWriter(sw)) {
			pw.println(prefix);

			reports.values().forEach(pw::print);
		}
		LOG.debug(sw.toString());
	}

	private static String getCiBranchName(long pullRequestID, String commitHash) {
		return "ci_" + pullRequestID + "_" + commitHash;
	}

	private static String getGitHubURL(String repository) {
		return "https://github.com/" + repository + ".git";
	}

	private static String escapeRegex(String format) {
		return format
				.replaceAll("\\[", "\\\\[")
				.replaceAll("\\(", "\\\\(")
				.replaceAll("\\)", "\\\\)")
				.replaceAll("\\*", "\\\\*")
				// line-endings are standardized in GitHub comments
				.replaceAll("\n", "(\\\\r\\\\n|\\\\n|\\\\r)");
	}

}
