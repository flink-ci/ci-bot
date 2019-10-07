package com.ververica.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Properties;

/**
 * Revision information encapsulates information about the source code revision of the CiBot
 * code.
 */
public class RevisionInformation {

	private static final Logger LOG = LoggerFactory.getLogger(RevisionInformation.class);
	private static final String UNKNOWN = "Unknown";

	private final String commitHash;
	private final String commitDate;

	private RevisionInformation(String commitHash, String commitDate) {
		this.commitHash = commitHash;
		this.commitDate = commitDate;
	}

	public String getCommitHash() {
		return commitHash;
	}

	public String getCommitDate() {
		return commitDate;
	}

	public static RevisionInformation getRevisionInformation() {
		String revision = UNKNOWN;
		String commitDate = UNKNOWN;
		try (InputStream propFile = RevisionInformation.class.getClassLoader().getResourceAsStream("git.properties")) {
			if (propFile != null) {
				final Properties properties = new Properties();
				properties.load(propFile);
				revision = properties.getProperty("git.commit.id.abbrev", UNKNOWN);
				commitDate = properties.getProperty("git.commit.time", UNKNOWN);
			}
		} catch (Throwable t) {
			LOG.debug("Error while accessing revision information.", t);
		}

		return new RevisionInformation(revision, commitDate);
	}
}
