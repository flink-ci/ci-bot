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

package com.ververica.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.RemoteRefUpdate.Status;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.UUID;

public class GitActionsImpl implements GitActions {
	private static final Path LOCAL_REPO_PATH = Paths.get("repo_" + UUID.randomUUID(), ".git");
	private static final Logger LOG = LoggerFactory.getLogger(GitActionsImpl.class);

	private final Git git;
	private final int operationTimeoutInSeconds = (int) Duration.ofMinutes(10).getSeconds();

	public GitActionsImpl(final Path temporaryDirectory) throws IOException {
		final Path repoPath = temporaryDirectory.resolve(LOCAL_REPO_PATH);
		LOG.debug("Setting up git repo at {}.", repoPath);
		final Repository repo = new FileRepositoryBuilder()
				.setMustExist(false)
				.setGitDir(repoPath.toFile())
				.build();
		repo.getConfig().setBoolean(ConfigConstants.CONFIG_GC_SECTION, null, ConfigConstants.CONFIG_KEY_AUTODETACH, false);
		repo.create();

		git = new Git(repo) {
			@Override
			public void close() {
				// this is a hack to couple the git and repo lifecycle
				repo.close();
				super.close();
			}
		};
	}

	@Override
	public void close() {
		git.close();
	}

	@Override
	public void addRemote(String repositoryUrl, String remoteName) throws GitException {
		LOG.debug("Setting up remote {} for repository ({}).", remoteName, repositoryUrl);
		try {
			git.remoteAdd()
					.setName(remoteName)
					.setUri(new URIish().setPath(repositoryUrl))
					.call();
		} catch (GitAPIException e) {
			throw new GitException(e);
		}
	}

	@Override
	public void fetchBranch(String remoteBranchName, String remoteName, boolean fetchPrBranch) throws GitException {
		final RefSpec refSpec = new RefSpec(
				fetchPrBranch
						? String.format("refs/pull/%s/head:%s", remoteBranchName, remoteBranchName)
						: String.format("refs/heads/%s:%s", remoteBranchName, remoteBranchName));

		LOG.debug("Fetching branch {} from {}.", refSpec, remoteName);

		try {
			git.fetch()
					.setRemote(remoteName)
					// this should use a logger instead, but this would break the output being updated in-place
					.setProgressMonitor(new TextProgressMonitor())
					.setRefSpecs(refSpec)
					.setTimeout(operationTimeoutInSeconds)
					.call();
		} catch (GitAPIException e) {
			throw new GitException(e);
		}
	}

	@Override
	public void pushBranch(String localBranchName, String remoteBranchName, String remoteName, boolean force, String authenticationToken) throws GitException {
		LOG.debug("Pushing branch {} to {}/{}.", localBranchName, remoteName, remoteBranchName);
		internalPushGitBranch(localBranchName, remoteBranchName, remoteName, force, authenticationToken);
	}

	@Override
	public void deleteLocalBranch(String localBranchName, boolean force) throws GitException {
		LOG.debug("Deleting branch {}.", localBranchName);
		try {
			git.branchDelete()
					.setBranchNames(localBranchName)
					.setForce(true)
					.call();
		} catch (GitAPIException e) {
			throw new GitException(e);
		}
	}

	@Override
	public void deleteRemoteBranch(String remoteBranchName, String remoteName, boolean force, String authenticationToken) throws GitException {
		LOG.debug("Deleting branch {}/{}.", remoteName, remoteBranchName);

		internalPushGitBranch(
				"",
				remoteBranchName,
				remoteName,
				force,
				authenticationToken);
	}

	private void internalPushGitBranch(String localBranchName, String remoteBranchName, String remoteName, boolean force, String authenticationToken) throws GitException {
		try {
			Iterable<PushResult> pushResults = git.push()
					.setRefSpecs(new RefSpec(String.format("%s:refs/heads/%s", localBranchName, remoteBranchName)))
					.setRemote(remoteName)
					.setCredentialsProvider(new UsernamePasswordCredentialsProvider(authenticationToken, ""))
					.setForce(force)
					.setTimeout(operationTimeoutInSeconds)
					.call();
			for (PushResult pushResult : pushResults) {
				LOG.debug(pushResult.getRemoteUpdates().toString());
				for (final RemoteRefUpdate rru : pushResult.getRemoteUpdates()) {
					switch (rru.getStatus()) {
						case OK:
						case UP_TO_DATE: // indicates duplicate push, which can happen due to eventual consistency
						case NON_EXISTING: // indicates duplicate delete, which can happen due to eventual consistency
							continue;
						case REJECTED_NODELETE:
							// branch could not be deleted
						case REJECTED_NONFASTFORWARD:
							// remote has diverged from local branch
							// should never occur when pushing a new branch
						case REJECTED_REMOTE_CHANGED:
							// Remote ref update was rejected, because old object id on remote
							// should never occur when pushing a new branch
						case REJECTED_OTHER_REASON:
							throw new GitException(
									new RuntimeException(
											String.format(
													"Error while pushing branch %s to %s/%s: %s",
													localBranchName,
													remoteBranchName,
													remoteName,
													rru.getMessage())));
					}
				}
			}
		} catch (GitAPIException e) {
			throw new GitException(e);
		}
	}

	@Override
	public String getHeadCommitSHA(String localBranchName) throws GitException {
		Iterable<RevCommit> call;
		try {
			ObjectId resolve = git.getRepository().resolve(localBranchName);
			call = git.log()
					.add(resolve)
					.call();
		} catch (IOException | GitAPIException e) {
			throw new GitException(e);
		}

		for (RevCommit revCommit : call) {
			return revCommit.getName();
		}
		throw new IllegalStateException("No commits in branch " + localBranchName + '.');
	}

	@Override
	public void cleanup() throws GitAPIException {
		LOG.debug("Running gc.");
		git.gc()
				.setProgressMonitor(new TextProgressMonitor())
				.call();
	}
}