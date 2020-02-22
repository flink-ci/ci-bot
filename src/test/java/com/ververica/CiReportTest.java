package com.ververica;

import org.junit.Test;

import java.util.Optional;

public class CiReportTest {

	private static final String EMPTY_LEGACY_CI_REPORT = "" +
			"## CI report:\n" +
			"\n";

	private static final String LEGACY_CI_REPORT = "" +
			"## CI report:\n" +
			"\n" +
			"* 1e9a07e9ffa1d8ae02e8fa26e543d5be01eacfe3 : SUCCESS [Build](https://travis-ci.com/flink-ci/flink/builds/123348301)\n";

	private static final String EMPTY_CI_REPORT = "" +
			"<!--\n" +
			"Meta data\n" +
			"-->\n" +
			"## CI report:\n" +
			"\n";

	private static final String CI_REPORT = "" +
			"<!--\n" +
			"Meta data\n" +
			"Hash:1e9a07e9ffa1d8ae02e8fa26e543d5be01eacfe3 Status:SUCCESS URL:https://travis-ci.com/flink-ci/flink/builds/123348301 TriggerType:PUSH TriggerID:1e9a07e9ffa1d8ae02e8fa26e543d5be01eacfe3\n" +
			"-->\n" +
			"## CI report:\n" +
			"\n";

	private static final String CI_REPORT_WITH_MULTIPLE_BUILDS = "" +
			"<!--\n" +
			"Meta data\n" +
			"Hash:1e9a07e9ffa1d8ae02e8fa26e543d5be01eacfe3 Status:SUCCESS URL:https://travis-ci.com/flink-ci/flink/builds/123348301 TriggerType:PUSH TriggerID:1e9a07e9ffa1d8ae02e8fa26e543d5be01eacfe3\n" +
			"Hash:1e9a07e9ffa1d8ae02e8fa26e543d5be01eacfe3 Status:SUCCESS URL:https://travis-ci.com/flink-ci/flink/builds/123348302 TriggerType:MANUAL TriggerID:1\n" +
			"-->\n" +
			"## CI report:\n" +
			"\n";

	private static final String CI_REPORT_WITH_UNKNOWN_STATUS = "" +
			"<!--\n" +
			"Meta data\n" +
			"Hash:1e9a07e9ffa1d8ae02e8fa26e543d5be01eacfe3 Status:UNKNOWN URL:TBD TriggerType:PUSH TriggerID:1e9a07e9ffa1d8ae02e8fa26e543d5be01eacfe3\n" +
			"-->\n" +
			"## CI report:\n" +
			"\n";

	@Test
	public void testEmptyLegacyParsing() {
		CiReport ciReport = CiReport.fromComment(1, EMPTY_LEGACY_CI_REPORT, s -> Optional.empty());
		System.out.println(ciReport);
	}

	@Test
	public void testLegacyParsing() {
		CiReport ciReport = CiReport.fromComment(1, LEGACY_CI_REPORT, s -> Optional.empty());
		System.out.println(ciReport);
	}

	@Test
	public void testParsing() {
		CiReport ciReport = CiReport.fromComment(1, CI_REPORT, s -> Optional.empty());
		System.out.println(ciReport);
	}

	@Test
	public void testEmptyParsing() {
		CiReport ciReport = CiReport.fromComment(1, EMPTY_CI_REPORT, s -> Optional.empty());
		System.out.println(ciReport);
	}

	@Test
	public void testParsingWithMultipleBuilds() {
		CiReport ciReport = CiReport.fromComment(1, CI_REPORT_WITH_MULTIPLE_BUILDS, s -> Optional.empty());
		System.out.println(ciReport);
	}

	@Test
	public void testParsingWithUnknownStatus() {
		CiReport ciReport = CiReport.fromComment(1, CI_REPORT_WITH_UNKNOWN_STATUS, s -> Optional.empty());
		System.out.println(ciReport);
	}
}