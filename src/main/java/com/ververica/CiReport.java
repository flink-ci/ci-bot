package com.ververica;

import com.ververica.github.GitHubCheckerStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

	private static final String TEMPLATE_CI_REPORT = "" +
			TEMPLATE_CI_REPORT_META_DATA_SECTION +
			"\n"
			+ TEMPLATE_CI_REPORT_USER_DATA_SECTION;

	private static final Pattern REGEX_PATTERN_LEGACY_CI_REPORT = Pattern.compile(
			String.format(escapeRegex(TEMPLATE_CI_REPORT_USER_DATA_SECTION),
					"(?<" + REGEX_GROUP_USER_DATA + ">.*)"),
			Pattern.DOTALL);

	private static final Pattern REGEX_PATTERN_CI_REPORT = Pattern.compile(
			String.format(escapeRegex(TEMPLATE_CI_REPORT),
					"(?<" + REGEX_GROUP_META_DATA + ">.*)",
					"(?<" + REGEX_GROUP_USER_DATA + ">.*)"),
			Pattern.DOTALL);

	private static final String TEMPLATE_META_DATA_LINE = "Hash:%s Status:%s URL:%s TriggerType:%s TriggerID:%s\n";

	private static final Pattern REGEX_PATTERN_META_DATA_LINES = Pattern.compile(String.format(escapeRegex(TEMPLATE_META_DATA_LINE),
			"(?<" + REGEX_GROUP_COMMIT_HASH + ">[0-9a-f]+)",
			"(?<" + REGEX_GROUP_BUILD_STATUS + ">[A-Z]+)",
			"(?<" + REGEX_GROUP_BUILD_URL + ">.+)",
			"(?<" + REGEX_GROUP_BUILD_TRIGGER_TYPE + ">.+)",
			"(?<" + REGEX_GROUP_BUILD_TRIGGER_ID + ">.+)"));

	private static final String TEMPLATE_USER_DATA_LINE_UNKNOWN = "* %s : %s\n";
	private static final String TEMPLATE_USER_DATA_LINE = "* %s : %s [Build](%s)\n";

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

				builds.put(commitHash + triggerType.name() + triggerID, new Build(
						pullRequestID,
						commitHash,
						Optional.of(new GitHubCheckerStatus(
								GitHubCheckerStatus.State.valueOf(status),
								url,
								"Travis CI")),
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
				final Optional<GitHubCheckerStatus> gitHubCheckerStatus;
				if (state == GitHubCheckerStatus.State.UNKNOWN) {
					gitHubCheckerStatus = Optional.of(new GitHubCheckerStatus(
							GitHubCheckerStatus.State.UNKNOWN,
							"TBD",
							"Travis CI"));
				} else {
					gitHubCheckerStatus = Optional.of(new GitHubCheckerStatus(
							state,
							url,
							"Travis CI"));
				}

				builds.put(commitHash + triggerType + triggerID, new Build(
						pullRequestID,
						commitHash,
						gitHubCheckerStatus,
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

		builds.put(
				build.commitHash + build.trigger.getType().name() + build.trigger.getId(),
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

		final Map<String, String> uniqueBuildPerHash = new LinkedHashMap<>();
		builds.values().forEach(build -> build.status.ifPresent(status -> {
			if (status.getState() == GitHubCheckerStatus.State.UNKNOWN) {
				uniqueBuildPerHash.put(
						build.commitHash,
						String.format(
								TEMPLATE_USER_DATA_LINE_UNKNOWN,
								build.commitHash,
								status.getState().name()));
			} else {
				uniqueBuildPerHash.put(
						build.commitHash,
						String.format(
								TEMPLATE_USER_DATA_LINE,
								build.commitHash,
								status.getState().name(),
								status.getDetailsUrl()));
			}
		}));
		final StringBuilder userDataSectionBuilder = new StringBuilder();
		uniqueBuildPerHash.values().forEach(userDataSectionBuilder::append);

		return String.format(TEMPLATE_CI_REPORT, metaDataSectionBuilder.toString(), userDataSectionBuilder.toString());
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
