package com.ververica;

import com.ververica.github.GitHubCheckerStatus;

import java.util.Objects;
import java.util.Optional;

class Build {
	public final int pullRequestID;
	public final String commitHash;
	public final Optional<GitHubCheckerStatus> status;

	Build(int pullRequestID, String commitHash, Optional<GitHubCheckerStatus> status) {
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
}
