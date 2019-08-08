package com.ververica;

import java.util.List;
import java.util.stream.Stream;

class ObservedState {
    private final List<Build> pendingBuilds;
    private final List<Build> finishedBuilds;
    private final List<Build> awaitingBuilds;

    ObservedState(List<Build> awaitingBuilds, List<Build> pendingBuilds, List<Build> finishedBuilds) {
        this.awaitingBuilds = awaitingBuilds;
        this.pendingBuilds = pendingBuilds;
        this.finishedBuilds = finishedBuilds;
    }

    public Stream<Build> getPendingBuilds() {
        return pendingBuilds.stream();
    }

    public Stream<Build> getFinishedBuilds() {
        return finishedBuilds.stream();
    }

    public Stream<Build> getAwaitingBuilds() {
        return awaitingBuilds.stream();
    }
}
