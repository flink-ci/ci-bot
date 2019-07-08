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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Cache;
import okhttp3.OkHttpClient;
import okhttp3.OkUrlFactory;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.kohsuke.github.GHBranch;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.kohsuke.github.HttpException;
import org.kohsuke.github.extras.OkHttp3Connector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A bot that mirrors pull requests opened against one repository (so called "observed repository") to branches in
 * another repository (so called "ci repository"), and report back the Checker status once the checks have completed.
 */
public class CiBot implements Runnable, AutoCloseable {

	private static final Logger LOG = LoggerFactory.getLogger(CiBot.class);

	private static final Path LOCAL_BASE_PATH = Paths.get(System.getProperty("java.io.tmpdir"), "ci_bot");
	private static final File LOCAL_CACHE_PATH = LOCAL_BASE_PATH.resolve(Paths.get("cache_" + UUID.randomUUID())).toFile();
	private static final File LOCAL_REPO_PATH = LOCAL_BASE_PATH.resolve(Paths.get("repo_" + UUID.randomUUID(), ".git")).toFile();

	private static final String REMOTE_NAME_OBSERVED_REPOSITORY = "observed";
	private static final String REMOTE_NAME_CI_REPOSITORY = "ci";

	private static final String REGEX_GROUP_PULL_REQUEST_ID = "PullRequestID";
	private static final String REGEX_GROUP_COMMIT_HASH = "CommitHash";
	private static final Pattern REGEX_PATTERN_CI_BRANCH = Pattern.compile(
			"ci_(?<" + REGEX_GROUP_PULL_REQUEST_ID + ">[0-9]+)_(?<" + REGEX_GROUP_COMMIT_HASH + ">[0-9a-f]+)", Pattern.DOTALL);
	private static final Pattern REGEX_PATTERN_BOT_REPORT_MESSAGE = Pattern.compile(
			"CI report for commit (?<" + REGEX_GROUP_COMMIT_HASH + ">[0-9a-f]{5,40}).*", Pattern.DOTALL);

	private final String observedRepository;
	private final String ciRepository;
	private final String username;
	private final String token;
	private final int pollingIntervalInSeconds;
	private final int backlogHours;

	private final Cache cache;
	private final GitHub gitHub;
	private final Git git;
	private final OkHttpClient okHttpClient;

	public static void main(String[] args) throws Exception {
		final Arguments arguments = new Arguments();
		final JCommander jCommander = JCommander.newBuilder()
				.addObject(arguments)
				.programName("java -jar ci-bot.jar")
				.args(args)
				.build();

		if (arguments.help) {
			final StringBuilder helpOutput = new StringBuilder();
			jCommander.usage(helpOutput);
			LOG.info(helpOutput.toString());
			return;
		}

		try (final CiBot ciBot = new CiBot(
				arguments.observedRepository,
				arguments.ciRepository,
				arguments.username,
				arguments.token,
				arguments.pollingIntervalInSeconds,
				arguments.backlogHours)) {
			ciBot.run();
		}
	}

	public CiBot(String observedRepository, String ciRepository, String username, String token, int pollingIntervalInSeconds, int backlogHours) throws Exception {
		this.observedRepository = observedRepository;
		this.ciRepository = ciRepository;
		this.username = username;
		this.token = token;
		this.pollingIntervalInSeconds = pollingIntervalInSeconds;
		this.backlogHours = backlogHours;

		cache = new Cache(LOCAL_CACHE_PATH, 4 * 1024 * 1024);
		okHttpClient = setupOkHttpClient(cache);
		git = setupGit(observedRepository, ciRepository);
		gitHub = setupGitHub(token, okHttpClient);
	}

	private static Git setupGit(String observedRepository, String ciRepository) throws Exception {
		LOG.info("Setting up git repo at {}.", LOCAL_REPO_PATH);

		FileUtils.deleteDirectory(LOCAL_REPO_PATH.getParentFile().getParentFile());

		final Repository repo = new FileRepositoryBuilder()
				.setMustExist(false)
				.setGitDir(LOCAL_REPO_PATH)
				.build();
		repo.create();

		Git git = new Git(repo) {
			@Override
			public void close() {
				// this is a hack to couple the git and repo lifecycle
				repo.close();
				super.close();
			}
		};

		LOG.info("Setting up remote for observed repository ({}).", observedRepository);
		git.remoteAdd()
				.setName(REMOTE_NAME_OBSERVED_REPOSITORY)
				.setUri(new URIish().setPath(getGitHubURL(observedRepository)))
				.call();

		LOG.info("Setting up remote for CI repository ({}).", ciRepository);
		git.remoteAdd()
				.setName(REMOTE_NAME_CI_REPOSITORY)
				.setUri(new URIish(new URL(getGitHubURL(ciRepository))))
				.call();

		LOG.info("Fetching master of observed repository.");
		git.fetch()
				.setRemote(REMOTE_NAME_OBSERVED_REPOSITORY)
				// this should use a logger instead, but this would break the output being updated in-place
				.setProgressMonitor(new TextProgressMonitor())
				.setRefSpecs(new RefSpec("refs/heads/master:refs/heads/master"))
				.call();

		return git;
	}

	private static OkHttpClient setupOkHttpClient(Cache cache) {
		LOG.info("Setting up OkHttp client with cache at {}.", LOCAL_CACHE_PATH);

		OkHttpClient.Builder okHttpClient = new OkHttpClient.Builder();
		okHttpClient.cache(cache);
		return okHttpClient.build();
	}

	private static GitHub setupGitHub(String token, OkHttpClient client) throws IOException {
		LOG.info("Setting up GitHub client.");

		return GitHubBuilder.fromEnvironment().withOAuthToken(token)
				.withConnector(new OkHttp3Connector(new OkUrlFactory(client)))
				.build();
	}

	@Override
	public void run() {
		try {
			Date lastUpdateDate = Date.from(Instant.now().minus(Duration.ofHours(backlogHours)));
			while (true) {
				final Date currentUpdateDate = Date.from(Instant.now());
				try {
					tick(lastUpdateDate);
					lastUpdateDate = currentUpdateDate;
				} catch (SocketTimeoutException ste) {
					LOG.error("Timeout occurred.", ste);
				} catch (HttpException he) {
					// this may happen in case of a timeout for some reasons
					LOG.error("Generic HTTP exception occurred.", he);
				} catch (TransportException te) {
					// this may happen in case of a git timeout
					LOG.error("Generic transport exception occurred.", te);
				}
				Thread.sleep(pollingIntervalInSeconds * 1000);
			}
		} catch (Exception e) {
			LOG.error("An exception occurred.", e);
		}
	}

	@Override
	public void close() {
		git.close();
		try {
			cache.close();
		} catch (Exception e) {
			LOG.debug("Error while shutting down cache.", e);
		}
		try {
			FileUtils.deleteDirectory(LOCAL_BASE_PATH.toFile());
		} catch (Exception e) {
			LOG.debug("Error while cleaning up directory.", e);
		}
		LOG.info("Shutting down.");
	}

	private void tick(Date lastUpdateTime) throws Exception {
		final CIState ciState = fetchCiState();
		processFinishedCiBuilds(ciState.finishedBuilds);

		final ObservedState observedRepositoryState = fetchGithubState(lastUpdateTime);

		final List<Build> requiredBuilds = resolveStates(ciState, observedRepositoryState);

		logRequiredBuilds(requiredBuilds);

		if (!requiredBuilds.isEmpty()) {
			for (Build build : requiredBuilds) {
				mirrorPullRequest(build.pullRequestID, build.commitHash);
				ciState.pendingBuilds.add(build);
			}
			LOG.info("Mirroring complete.");
		}
	}

	private static List<Build> resolveStates(CIState ciState, ObservedState observedState) {
		return observedState.awaitingBuilds.stream()
				.filter(build -> !ciState.pendingBuilds.contains(build))
				.filter(build -> !ciState.finishedBuilds.contains(build))
				.collect(Collectors.toList());
	}

	private CIState fetchCiState() throws Exception {
		LOG.info(String.format("Retrieving CI repository state (%s).", ciRepository));

		final Map<String, GHBranch> branches = gitHub.getRepository(ciRepository).getBranches();

		final List<Build> pendingBuilds = new ArrayList<>();
		final List<Build> finishedBuilds = new ArrayList<>();
		for (Map.Entry<String, GHBranch> stringGHBranchEntry : branches.entrySet()) {
			Matcher matcher = REGEX_PATTERN_CI_BRANCH.matcher(stringGHBranchEntry.getKey());
			if (matcher.matches()) {
				String commitHash = matcher.group(REGEX_GROUP_COMMIT_HASH);
				int pullRequestID = Integer.valueOf(matcher.group(REGEX_GROUP_PULL_REQUEST_ID));

				Build.Status lastStatus = getCommitState(okHttpClient, ciRepository, commitHash, token);
				if (lastStatus == null) {
					LOG.warn("Discarding CI branch {} because no build was triggered.", getCiBranchName(pullRequestID, commitHash));
					deleteCiBranch(pullRequestID, commitHash);
				} else {
					Build build = new Build(pullRequestID, commitHash, Optional.of(lastStatus));
					if (lastStatus.state == Build.Status.State.PENDING) {
						pendingBuilds.add(build);
					} else {
						finishedBuilds.add(build);
					}
				}
			}
		}

		final CIState ciState = new CIState(pendingBuilds, finishedBuilds);
		LOG.info(ciState.toString());
		return ciState;
	}

	private void processFinishedCiBuilds(List<Build> finishedBuilds) throws Exception {
		LOG.info("Processing finished CI builds.");
		for (Build finishedBuild : finishedBuilds) {
			LOG.info("Processing finished CI build for {}@{}.", finishedBuild.pullRequestID, finishedBuild.commitHash);
			submitCiReport(finishedBuild);
			deleteCiBranch(finishedBuild.pullRequestID, finishedBuild.commitHash);
		}
	}

	private void submitCiReport(Build build) throws IOException {
		if (!build.status.isPresent()) {
			throw new IllegalStateException("Invalid state. Finished build without attached commit status.");
		}

		GHRepository repository = gitHub.getRepository(observedRepository);
		GHPullRequest pullRequest = repository.getPullRequest(build.pullRequestID);
		Build.Status ghCommitStatus = build.status.get();
		pullRequest.comment(
				String.format("CI report for commit %s: %s [Build](%s)",
						build.commitHash,
						ghCommitStatus.state.name(),
						ghCommitStatus.externalUrl));
	}

	private void deleteCiBranch(int pullRequestID, String commitHash) throws GitAPIException {
		LOG.info(String.format("Deleting CI branch for %s@%s.", pullRequestID, commitHash));
		git.push()
				.setRefSpecs(new RefSpec(":refs/heads/" + getCiBranchName(pullRequestID, commitHash)))
				.setRemote(REMOTE_NAME_CI_REPOSITORY)
				.setCredentialsProvider(new UsernamePasswordCredentialsProvider(token, ""))
				.setForce(true)
				.call()
				.forEach(pushResult -> LOG.debug(pushResult.getRemoteUpdates().toString()));
	}

	private ObservedState fetchGithubState(Date lastUpdatedAtCutoff) throws IOException {
		LOG.info("Retrieving observed repository state ({}).", observedRepository);
		final GHRepository observedGitHubRepository = gitHub.getRepository(this.observedRepository);
		final List<GHPullRequest> pullRequests = observedGitHubRepository.getPullRequests(GHIssueState.OPEN);

		final List<Build> pullRequestsRequiringBuild = new ArrayList<>();
		for (GHPullRequest pullRequest : pullRequests) {
			if (pullRequest.getUpdatedAt().after(lastUpdatedAtCutoff)) {
				final String headCommitHash = pullRequest.getHead().getSha();
				final Collection<String> verifiedCommitHashes = new ArrayList<>();
				for (GHIssueComment listReviewComment : pullRequest.getComments()) {
					if (listReviewComment.getUser().getLogin().equals(username)) {
						Matcher messageMatcher = REGEX_PATTERN_BOT_REPORT_MESSAGE.matcher(listReviewComment.getBody());
						if (messageMatcher.matches()) {
							String commitHash = messageMatcher.group(REGEX_GROUP_COMMIT_HASH);
							verifiedCommitHashes.add(commitHash);
						}
					}
				}
				if (!verifiedCommitHashes.contains(headCommitHash)) {
					pullRequestsRequiringBuild.add(new Build(pullRequest.getNumber(), headCommitHash, Optional.empty()));
				}
			}
		}

		return new ObservedState(pullRequestsRequiringBuild);
	}

	private void mirrorPullRequest(long pullRequestID, String commitHash) throws Exception {
		LOG.info("Mirroring PullRequest {}@{}.", pullRequestID, commitHash);
		LOG.info("Fetching PullRequest {}.", pullRequestID);
		git.fetch()
				.setRemote(REMOTE_NAME_OBSERVED_REPOSITORY)
				.setCheckFetchedObjects(true)
				.setRefSpecs(new RefSpec("refs/pull/" + pullRequestID + "/head:" + pullRequestID))
				.call();

		LOG.info("Pushing PullRequest {}.", pullRequestID);
		git.push()
				.setRemote(REMOTE_NAME_CI_REPOSITORY)
				.setRefSpecs(new RefSpec(pullRequestID + ":refs/heads/" + getCiBranchName(pullRequestID, commitHash)))
				.setCredentialsProvider(new UsernamePasswordCredentialsProvider(token, ""))
				.call()
				.forEach(pushResult -> LOG.debug(pushResult.getRemoteUpdates().toString()));
	}

	private static void logRequiredBuilds(List<Build> requiredBuilds) {
		final StringWriter sw = new StringWriter();
		try (PrintWriter pw = new PrintWriter(sw)) {
			pw.println("Observed repository state:");

			pw.println(String.format("\tRequired builds (%s):", requiredBuilds.size()));
			requiredBuilds.forEach(build -> pw.println("\t\t" + build.pullRequestID + '@' + build.commitHash));
		}
		LOG.info(sw.toString());
	}

	/**
	 * Retrieves the CI status for the given commit.
	 *
	 * <p>This internally retrieves the status via the Checks API, since the Commit Status API is not supported on
	 * {@code travis-ci.com}.
	 * Internally this uses a plain REST client, since the {@code github-api} does not support the
	 * Checks API. (see https://github.com/kohsuke/github-api/issues/520)
	 */
	private static Build.Status getCommitState(OkHttpClient client, String observedRepository, String commitHash, String token) throws IOException {
		try (Response response = client.newCall(new Request.Builder()
				.url("https://api.github.com/repos/" + observedRepository + "/commits/" + commitHash + "/check-runs")
				.addHeader("Accept", "application/vnd.github.antiope-preview+json")
				.addHeader("Authorization", "token " + token)
				.build()).execute()) {
			String rawJson = response.body().string();

			ObjectMapper objectMapper = new ObjectMapper();
			LOG.debug(rawJson);
			JsonNode jsonNode = objectMapper.readTree(rawJson);
			Iterator<JsonNode> checkJson = jsonNode.get("check_runs").iterator();

			if (checkJson.hasNext()) {
				JsonNode next = checkJson.next();

				final Build.Status.State state;
				final GHStatus ghStatus = GHStatus.valueOf(next.get("status").asText().toUpperCase());
				switch (ghStatus) {
					case COMPLETED:
						final GHConclusion ghConclusion = GHConclusion.valueOf(next.get("conclusion").asText().toUpperCase());
						switch (ghConclusion) {
							case SUCCESS:
								state = Build.Status.State.SUCCESS;
								break;
							case CANCELLED:
								state = Build.Status.State.CANCELED;
								break;
							default:
								state = Build.Status.State.FAILURE;
								break;
						}
						break;
					default:
						state = Build.Status.State.PENDING;
						break;
				}
				String externalUrl = next.get("details_url").asText();
				return new Build.Status(state, externalUrl);
			} else {
				return null;
			}
		} catch (Exception e) {
			// super janky but don't bother handling this in a better way
			// there are just too many failure points here
			LOG.warn("Could not retrieve commit state.", e);
			return null;
		}
	}

	private static String getCiBranchName(long pullRequestID, String commitHash) {
		return "ci_" + pullRequestID + "_" + commitHash;
	}

	private static String getGitHubURL(String repository) {
		return "https://github.com/" + repository + ".git";
	}

	private static class ObservedState {
		public final List<Build> awaitingBuilds;

		private ObservedState(List<Build> awaitingBuilds) {
			this.awaitingBuilds = awaitingBuilds;
		}
	}

	private static class CIState {
		public final List<Build> pendingBuilds;
		public final List<Build> finishedBuilds;

		private CIState(List<Build> pendingBuilds, List<Build> finishedBuilds) {
			this.pendingBuilds = pendingBuilds;
			this.finishedBuilds = finishedBuilds;
		}

		public String toString() {
			final StringWriter sw = new StringWriter();
			try (PrintWriter pw = new PrintWriter(sw)) {
				pw.println("CI repository state:");
				pw.println(String.format("\tPending builds (%s):", pendingBuilds.size()));
				pendingBuilds.forEach(build -> pw.println("\t\t" + build.pullRequestID + '@' + build.commitHash));
				pw.println(String.format("\tFinished builds (%s):", finishedBuilds.size()));
				finishedBuilds.forEach(build -> pw.println("\t\t" + build.pullRequestID + '@' + build.commitHash));
			}
			return sw.toString();
		}
	}

	private static class Build {
		public final int pullRequestID;
		public final String commitHash;
		public final Optional<Status> status;

		private Build(int pullRequestID, String commitHash, Optional<Status> status) {
			this.pullRequestID = pullRequestID;
			this.commitHash = commitHash;
			this.status = status;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (o == null || getClass() != o.getClass()) {
				return false;
			}
			Build build = (Build) o;
			return pullRequestID == build.pullRequestID &&
					Objects.equals(commitHash, build.commitHash);
		}

		@Override
		public int hashCode() {
			return Objects.hash(pullRequestID, commitHash);
		}

		public static class Status {
			public final State state;
			public final String externalUrl;

			public Status(State state, String externalUrl) {
				this.state = state;
				this.externalUrl = externalUrl;
			}

			public enum State {
				PENDING,
				SUCCESS,
				CANCELED,
				FAILURE
			}
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
