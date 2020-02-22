package com.ververica;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static com.ververica.github.GitHubCheckerStatus.State.PENDING;

class ObservedState {
    private final List<CiReport> ciReports;

    ObservedState(List<CiReport> ciReports) {
        this.ciReports = ciReports;
    }

    public Stream<Build> getPendingBuilds() {
        return ciReports.stream()
                .sorted(Comparator.comparingInt(CiReport::getPullRequestID))
                .flatMap(CiReport::getBuilds)
                .filter(report -> report.status.isPresent() && report.status.get().getState() == PENDING);
    }

    public Stream<Build> getFinishedBuilds() {
        return ciReports.stream()
                .sorted(Comparator.comparingInt(CiReport::getPullRequestID))
                .flatMap(CiReport::getBuilds)
                .filter(report -> report.status.isPresent() && report.status.get().getState() != PENDING);
    }

    public Stream<CiReport> getCiReports() {
        return ciReports.stream().sorted(Comparator.comparingInt(CiReport::getPullRequestID));
    }
}
