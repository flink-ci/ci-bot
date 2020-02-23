package com.ververica;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

class ObservedState {
    private final List<CiReport> ciReports;

    ObservedState(List<CiReport> ciReports) {
        this.ciReports = ciReports;
    }

	public Stream<CiReport> getCiReports() {
        return ciReports.stream().sorted(Comparator.comparingInt(CiReport::getPullRequestID));
    }
}
