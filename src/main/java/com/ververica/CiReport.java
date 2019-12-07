package com.ververica;

import com.ververica.ci.CiProvider;
import com.ververica.github.GitHubCheckerStatus;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CiReport {

	private static final String REGEX_GROUP_COMMIT_HASH = "CommitHash";
	private static final String REGEX_GROUP_BUILD_STATUS = "BuildStatus";
	private static final String REGEX_GROUP_BUILD_URL = "URL";
	private static final String REGEX_GROUP_BUILD_TRIGGER_TYPE = "TriggerType";
	private static final String REGEX_GROUP_BUILD_TRIGGER_ID = "TriggerID";
	private static final String REGEX_GROUP_META_DATA = "MetaData";
	private static final String REGEX_GROUP_USER_DATA = "UserData";
	private static final String TEMPLATE_CI_REPORT_META_DATA_SECTION = "" +
			"<!--\n" +
			"Meta data\n" +
			"%s" +
			"-->";

	private static final String TEMPLATE_CI_REPORT_USER_DATA_SECTION = "" +
			"## CI report:\n" +
			"\n" +
			"%s";

	private static final String TEMPLATE_CI_REPORT_COMMAND_HELP_SECTION = "" +
			"<details>\n" +
			"<summary>Bot commands</summary>\n" +
			"  The @flinkbot bot supports the following commands:\n" +
			"\n" +
			" - `@flinkbot run travis` re-run the last Travis build\n" +
			" - `@flinkbot run azure` re-run the last Azure build\n" +
			"</details>";

	private static final String TEMPLATE_CI_REPORT_DATA_SECTION = "" +
			TEMPLATE_CI_REPORT_META_DATA_SECTION +
			"\n" +
			TEMPLATE_CI_REPORT_USER_DATA_SECTION;

	private static final String TEMPLATE_CI_REPORT = "" +
			TEMPLATE_CI_REPORT_DATA_SECTION +
			"\n" +
			TEMPLATE_CI_REPORT_COMMAND_HELP_SECTION;

	private static final Pattern REGEX_PATTERN_LEGACY_CI_REPORT = Pattern.compile(
			String.format(escapeRegex(TEMPLATE_CI_REPORT_USER_DATA_SECTION),
					"(?<" + REGEX_GROUP_USER_DATA + ">.*)"),
			Pattern.DOTALL);

	private static final Pattern REGEX_PATTERN_CI_REPORT = Pattern.compile(
			String.format(escapeRegex(TEMPLATE_CI_REPORT_DATA_SECTION),
					"(?<" + REGEX_GROUP_META_DATA + ">.*)",
					"(?<" + REGEX_GROUP_USER_DATA + ">.*)")
					+ "(\n" + TEMPLATE_CI_REPORT_COMMAND_HELP_SECTION + ")?",
			Pattern.DOTALL);

	private static final String TEMPLATE_META_DATA_LINE = "Hash:%s Status:%s URL:%s TriggerType:%s TriggerID:%s\n";

	private static final Pattern REGEX_PATTERN_META_DATA_LINES = Pattern.compile(String.format(escapeRegex(TEMPLATE_META_DATA_LINE),
			"(?<" + REGEX_GROUP_COMMIT_HASH + ">[0-9a-f]+)",
			"(?<" + REGEX_GROUP_BUILD_STATUS + ">[A-Z]+)",
			"(?<" + REGEX_GROUP_BUILD_URL + ">.+)",
			"(?<" + REGEX_GROUP_BUILD_TRIGGER_TYPE + ">.+)",
			"(?<" + REGEX_GROUP_BUILD_TRIGGER_ID + ">.+)"));

	// bf9a31483c0d55c968f65b8ca4a11557f52de456 [Travis CI](https://travis-ci.com/flink-ci/flink/builds/138727067) FAILURE
	private static final String TEMPLATE_USER_DATA_LINE = "* %s %s\n";
	// Travis CI [FAILURE](https://travis-ci.com/flink-ci/flink/builds/138727067)
	private static final String TEMPLATE_USER_DATA_BUILD_ITEM = "%s: [%s](%s) ";

	private static final Pattern REGEX_PATTERN_USER_DATA_LINES = Pattern.compile(String.format(escapeRegex(TEMPLATE_USER_DATA_LINE),
			"(?<" + REGEX_GROUP_COMMIT_HASH + ">[0-9a-f]+)",
			"(?<" + REGEX_GROUP_BUILD_STATUS + ">[A-Z]+)",
			"(?<" + REGEX_GROUP_BUILD_URL + ">.+)"));

	private static final String UNKNOWN_URL = "TBD";

	private final int pullRequestID;
	private final Map<String, Build> builds;

	private CiReport(int pullRequestID, Map<String, Build> builds) {
		this.pullRequestID = pullRequestID;
		this.builds = builds;
	}

	public static CiReport empty(int pullRequestID) {
		return new CiReport(pullRequestID, new LinkedHashMap<>());
	}

	public static CiReport fromComment(int pullRequestID, String comment) {
		final Map<String, Build> builds = new LinkedHashMap<>();

		final Matcher reportMatcher = REGEX_PATTERN_CI_REPORT.matcher(comment);
		if (!reportMatcher.matches()) {
			final Matcher legacyReportMatcher = REGEX_PATTERN_LEGACY_CI_REPORT.matcher(comment);
			if (!legacyReportMatcher.matches()) {
				throw new IllegalArgumentException();
			}

			// compatibility routine; extract data from user data instead
			final String userData = legacyReportMatcher.group(REGEX_GROUP_USER_DATA);
			final Matcher userDataMatcher = REGEX_PATTERN_USER_DATA_LINES.matcher(userData);
			while (userDataMatcher.find()) {
				final String commitHash = userDataMatcher.group(REGEX_GROUP_COMMIT_HASH);
				final String status = userDataMatcher.group(REGEX_GROUP_BUILD_STATUS);
				final String url = userDataMatcher.group(REGEX_GROUP_BUILD_URL);
				final Trigger.Type triggerType = Trigger.Type.PUSH;
				final String triggerID = commitHash;

				builds.put(commitHash + triggerType.name() + triggerID + url.hashCode(), new Build(
						pullRequestID,
						commitHash,
						Optional.of(new GitHubCheckerStatus(
								GitHubCheckerStatus.State.valueOf(status),
								url,
								CiProvider.fromUrl(url))),
						new Trigger(triggerType, triggerID)));
			}
		} else {
			final String metaData = reportMatcher.group(REGEX_GROUP_META_DATA);
			final Matcher metaDataMatcher = REGEX_PATTERN_META_DATA_LINES.matcher(metaData);
			metaDataMatcher.reset();
			while (metaDataMatcher.find()) {
				final String commitHash = metaDataMatcher.group(REGEX_GROUP_COMMIT_HASH);
				final String status = metaDataMatcher.group(REGEX_GROUP_BUILD_STATUS);
				final String url = metaDataMatcher.group(REGEX_GROUP_BUILD_URL);
				final String triggerType = metaDataMatcher.group(REGEX_GROUP_BUILD_TRIGGER_TYPE);
				final String triggerID = metaDataMatcher.group(REGEX_GROUP_BUILD_TRIGGER_ID);

				GitHubCheckerStatus.State state = GitHubCheckerStatus.State.valueOf(status);
				final GitHubCheckerStatus gitHubCheckerStatus;
				if (state == GitHubCheckerStatus.State.UNKNOWN) {
					gitHubCheckerStatus = new GitHubCheckerStatus(
							GitHubCheckerStatus.State.UNKNOWN,
							"TBD",
							CiProvider.Unknown);
				} else {
					gitHubCheckerStatus = new GitHubCheckerStatus(
							state,
							url,
							CiProvider.fromUrl(url));
				}

				builds.put(commitHash + triggerType + triggerID + gitHubCheckerStatus.getDetailsUrl().hashCode(), new Build(
						pullRequestID,
						commitHash,
						Optional.of(gitHubCheckerStatus),
						new Trigger(Trigger.Type.valueOf(triggerType), triggerID)));
			}
		}
		return new CiReport(pullRequestID, builds);
	}

	public static boolean isCiReportComment(String comment) {
		return REGEX_PATTERN_CI_REPORT.matcher(comment).matches() || REGEX_PATTERN_LEGACY_CI_REPORT.matcher(comment).matches();
	}

	public void add(Build build) {
		if (pullRequestID != build.pullRequestID) {
			throw new IllegalArgumentException();
		}

		final String baseHash = build.commitHash + build.trigger.getType().name() + build.trigger.getId();

		builds.remove(baseHash);
		builds.remove(baseHash + UNKNOWN_URL.hashCode());
		builds.put(
				baseHash + build.status.map(gitHubCheckerStatus -> String.valueOf(gitHubCheckerStatus.getDetailsUrl().hashCode())).orElse(""),
				build
		);
	}

	public Stream<Build> getBuilds() {
		return builds.values().stream();
	}

	public int getPullRequestID() {
		return pullRequestID;
	}

	@Override
	public String toString() {
		return String.format(TEMPLATE_CI_REPORT, createMetaDataSection(), createUserDataSection());
	}

	private String createMetaDataSection() {
		final StringBuilder metaDataSectionBuilder = new StringBuilder();
		builds.values().forEach(build -> {
			final GitHubCheckerStatus.State status;
			final String url;
			if (build.status.isPresent()) {
				status = build.status.get().getState();
				url = build.status.get().getDetailsUrl();
			} else {
				status = GitHubCheckerStatus.State.UNKNOWN;
				url = UNKNOWN_URL;
			}

			metaDataSectionBuilder.append(String.format(
					TEMPLATE_META_DATA_LINE,
					build.commitHash,
					status.name(),
					url,
					build.trigger.getType(),
					build.trigger.getId()));
		});

		return metaDataSectionBuilder.toString();
	}

	private String createUserDataSection() {
		final Map<String, List<Build>> buildsPerHash = builds.values().stream()
				.filter(build -> build.status.isPresent())
				.collect(Collectors.groupingBy(
						build -> build.commitHash,
						LinkedHashMap::new,
						Collectors.toList()));

		final Map<String, String> reportEntryPerHash = new LinkedHashMap<>();
		buildsPerHash.forEach((hash, builds) -> {
			// reverse list so that distinct() retains the LAST matching build
			Collections.reverse(builds);
			builds = builds.stream().map(BuildDeduplicator::new).distinct().map(BuildDeduplicator::getOriginalBuild).collect(Collectors.toList());
			Collections.reverse(builds);
			builds.sort(Comparator.comparing(build -> build.status.map(GitHubCheckerStatus::getCiProvider).orElse(CiProvider.Unknown)));

			final StringBuilder reportEntryBuilder = new StringBuilder();
			for (Build build : builds) {
				build.status.ifPresent(status -> {
					if (status.getState() != GitHubCheckerStatus.State.UNKNOWN) {
						reportEntryBuilder.append(
								String.format(
										TEMPLATE_USER_DATA_BUILD_ITEM,
										status.getCiProvider().getName(),
										status.getState().name(),
										status.getDetailsUrl()));
					}
				});
			}

			if (reportEntryBuilder.length() == 0) {
				reportEntryBuilder.append(GitHubCheckerStatus.State.UNKNOWN.name());
			}

			reportEntryPerHash.put(
					hash,
					String.format(
							TEMPLATE_USER_DATA_LINE,
							hash,
							reportEntryBuilder.toString()));
		});

		final StringBuilder userDataSectionBuilder = new StringBuilder();
		reportEntryPerHash.values().forEach(userDataSectionBuilder::append);

		return userDataSectionBuilder.toString();
	}

	private static class BuildDeduplicator {

		private final Build originalBuild;

		private BuildDeduplicator(Build originalBuild) {
			this.originalBuild = originalBuild;
		}

		public Build getOriginalBuild() {
			return originalBuild;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (o == null || getClass() != o.getClass()) {
				return false;
			}
			BuildDeduplicator that = (BuildDeduplicator) o;
			// equal impl
			return Objects.equals(originalBuild.commitHash, that.originalBuild.commitHash) &&
					Objects.equals(
							originalBuild.status.map(GitHubCheckerStatus::getDetailsUrl).orElse(null),
							that.originalBuild.status.map(GitHubCheckerStatus::getDetailsUrl).orElse(null));
		}

		@Override
		public int hashCode() {
			return Objects.hash(originalBuild);
		}
	}

	private static String escapeRegex(String format) {
		return format
				.replaceAll("\\[", "\\\\[")
				.replaceAll("\\(", "\\\\(")
				.replaceAll("\\)", "\\\\)")
				.replaceAll("\\*", "\\\\*")
				// line-endings are standardized in GitHub comments
				.replaceAll("\n", "(\\\\r\\\\n|\\\\n|\\\\r)");
	}
}
