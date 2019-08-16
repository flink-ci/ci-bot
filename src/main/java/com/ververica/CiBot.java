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
import com.ververica.git.GitActionsImpl;
import com.ververica.github.GithubActionsImpl;
import com.ververica.travis.TravisActionsImpl;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.errors.TransportException;
import org.kohsuke.github.GHException;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.HttpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A bot that mirrors pull requests opened against one repository (so called "observed repository") to branches in
 * another repository (so called "ci repository"), and report back the Checker status once the checks have completed.
 */
public class CiBot implements Runnable, AutoCloseable {

	private static final Logger LOG = LoggerFactory.getLogger(CiBot.class);

	private static final Path LOCAL_BASE_PATH = Paths.get(System.getProperty("java.io.tmpdir"), "ci_bot");

	private final static int DELAY_MILLI_SECONDS = 5 * 1000;

	private final Core core;
	private final int pollingIntervalInSeconds;
	private final int backlogHours;

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
				new Core(
						arguments.observedRepository,
						arguments.ciRepository,
						arguments.username,
						arguments.githubToken,
						new GitActionsImpl(LOCAL_BASE_PATH),
						new GithubActionsImpl(LOCAL_BASE_PATH.resolve("github"), arguments.githubToken),
						new TravisActionsImpl(LOCAL_BASE_PATH.resolve("travis"), arguments.travisToken),
						DELAY_MILLI_SECONDS),
				arguments.pollingIntervalInSeconds,
				arguments.backlogHours)) {
			ciBot.run();
		}
	}

	public CiBot(Core core, int pollingIntervalInSeconds, int backlogHours) {
		this.core = core;
		this.pollingIntervalInSeconds = pollingIntervalInSeconds;
		this.backlogHours = backlogHours;
	}

	@Override
	public void run() {
		try {
			Date lastUpdateDate = Date.from(Instant.now().minus(Duration.ofHours(backlogHours)));
			while (true) {
				// include a grace-period to handle GitHub not returning the latest data
				final Date currentUpdateDate = Date.from(Instant.now().minus(Duration.ofMinutes(10)));
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
				} catch (GHException ge) {
					LOG.error("Generic github exception occurred.", ge);
				} catch (GHFileNotFoundException gfnfe) {
					LOG.error("GitHub server error.", gfnfe);
				}
				Thread.sleep(pollingIntervalInSeconds * 1000);
			}
		} catch (Exception e) {
			LOG.error("An exception occurred.", e);
		}
	}

	@Override
	public void close() {
		core.close();
		try {
			FileUtils.deleteDirectory(LOCAL_BASE_PATH.toFile());
		} catch (Exception e) {
			LOG.debug("Error while cleaning up directory.", e);
		}
		LOG.info("Shutting down.");
	}

	private void tick(Date lastUpdateTime) throws Exception {
		final CIState ciState = core.fetchCiState();

		Map<Integer, List<Build>> updatesPerPullRequest = Stream
				.concat(ciState.getPendingBuilds(), ciState.getFinishedBuilds())
				.collect(Collectors.groupingBy(build -> build.pullRequestID));
		for (Map.Entry<Integer, List<Build>> entry : updatesPerPullRequest.entrySet()) {
			core.updateCiReport(entry.getKey(), entry.getValue());
			Thread.sleep(DELAY_MILLI_SECONDS);
		}

		final ObservedState observedRepositoryState = core.fetchGithubState(lastUpdateTime);

		final List<Build> builds = resolveDanglingBuilds(ciState, observedRepositoryState);
		logDanglingBuilds(builds);

		final List<Build> requiredBuilds = resolveRequiredBuilds(ciState, observedRepositoryState);
		logRequiredBuilds(requiredBuilds);
		final Set<Integer> pullRequestsWithNewBuilds = new HashSet<>();
		for (Build build : requiredBuilds) {
			core.mirrorPullRequest(build.pullRequestID);
			pullRequestsWithNewBuilds.add(build.pullRequestID);
			Thread.sleep(DELAY_MILLI_SECONDS);
		}

		final Map<Integer, List<Build>> pendingBuildsPerPullRequestId = ciState.getPendingBuilds().collect(Collectors.groupingBy(build -> build.pullRequestID));
		for (Map.Entry<Integer, List<Build>> pendingBuilds : pendingBuildsPerPullRequestId.entrySet()) {
			final int pullRequestID = pendingBuilds.getKey();
			if (core.isPullRequestClosed(pullRequestID)) {
				LOG.info("Canceling pending builds for PullRequest {} since the PullRequest was closed.", pullRequestID);
				cancelBuilds(pendingBuilds.getValue());
			} else if (pullRequestsWithNewBuilds.contains(pullRequestID)) {
				LOG.info("Canceling pending builds for PullRequest {} since a new build was triggered.", pullRequestID);
				cancelBuilds(pendingBuilds.getValue());
			}
		}

		final Map<Integer, List<Build>> finishedBuildsPerPullRequestId = ciState.getFinishedBuilds().collect(Collectors.groupingBy(build -> build.pullRequestID));
		for (Map.Entry<Integer, List<Build>> finishedBuilds : finishedBuildsPerPullRequestId.entrySet()) {
			final int pullRequestID = finishedBuilds.getKey();
			if (core.isPullRequestClosed(pullRequestID)) {
				LOG.info("Deleting branches for PullRequest {} since PullRequest was closed.", pullRequestID);
				for (Build finishedBuild : finishedBuilds.getValue()) {
					core.deleteCiBranch(finishedBuild);
					Thread.sleep(DELAY_MILLI_SECONDS);
				}
			}
		}
	}

	private void cancelBuilds(Iterable<Build> builds) throws InterruptedException {
		for (Build build : builds) {
			core.cancelBuild(build);
			Thread.sleep(DELAY_MILLI_SECONDS);
		}
	}

	private static List<Build> resolveRequiredBuilds(CIState ciState, ObservedState observedState) {
		return observedState.getAwaitingBuilds()
				.filter(build -> ciState.getPendingBuilds().noneMatch(build::equals))
				.filter(build -> ciState.getFinishedBuilds().noneMatch(build::equals))
				.collect(Collectors.toList());
	}

	private static List<Build> resolveDanglingBuilds(CIState ciState, ObservedState observedState) {
		return observedState.getPendingBuilds()
				.filter(build -> ciState.getPendingBuilds().noneMatch(build::equals))
				.filter(build -> ciState.getFinishedBuilds().noneMatch(build::equals))
				.collect(Collectors.toList());
	}

	private static void logDanglingBuilds(List<Build> danglingBuilds) {
		if (!danglingBuilds.isEmpty()) {
			final StringWriter sw = new StringWriter();
			try (PrintWriter pw = new PrintWriter(sw)) {
				pw.println("Observed repository state:");

				pw.println(String.format("\tDangling builds (%s):", danglingBuilds.size()));
				danglingBuilds.forEach(build -> pw.println("\t\t" + build.pullRequestID + '@' + build.commitHash));
			}
			LOG.warn(sw.toString());
		}
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
}
