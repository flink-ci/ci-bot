package com.ververica;

import com.ververica.github.GitHubCheckerStatus;

import java.util.Objects;
import java.util.Optional;

class Build {
	public final int pullRequestID;
	public final String commitHash;
	public final Optional<GitHubCheckerStatus> status;
	public final Trigger trigger;

	Build(int pullRequestID, String commitHash, Optional<GitHubCheckerStatus> status, Trigger trigger) {
		this.pullRequestID = pullRequestID;
		this.commitHash = commitHash;
		this.status = status;
		this.trigger = trigger;
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
				Objects.equals(commitHash, build.commitHash) &&
				trigger == build.trigger;
	}

	@Override
	public int hashCode() {
		return Objects.hash(pullRequestID, commitHash);
	}
}
