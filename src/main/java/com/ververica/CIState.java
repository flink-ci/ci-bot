package com.ververica;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class CIState {
	private final List<Build> pendingBuilds;
	private final List<Build> finishedBuilds;

	CIState(List<Build> pendingBuilds, List<Build> finishedBuilds) {
		this.pendingBuilds = pendingBuilds;
		this.finishedBuilds = finishedBuilds;
	}

	public Stream<Build> getPendingBuilds() {
		return pendingBuilds.stream();
	}

	public Stream<Build> getFinishedBuilds() {
		return finishedBuilds.stream();
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
