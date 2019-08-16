/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ververica.travis;

import okhttp3.Cache;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

public class TravisActionsImpl implements TravisActions {
	private static final Logger LOG = LoggerFactory.getLogger(TravisActionsImpl.class);

	private final Cache cache;
	private final OkHttpClient okHttpClient;
	private final String authorizationToken;

	public TravisActionsImpl(Path temporaryDirectory, String authorizationToken) throws IOException {
		cache = new Cache(temporaryDirectory.toFile(), 4 * 1024 * 1024);
		okHttpClient = setupOkHttpClient(cache);
		this.authorizationToken = authorizationToken;
	}

	@Override
	public void close() {
		try {
			cache.close();
		} catch (Exception e) {
			LOG.debug("Error while shutting down cache.", e);
		}
	}

	private static OkHttpClient setupOkHttpClient(Cache cache) {
		LOG.info("Setting up OkHttp client with cache at {}.", cache.directory());

		final OkHttpClient.Builder okHttpClient = new OkHttpClient.Builder();
		okHttpClient.cache(cache);
		return okHttpClient.build();
	}

	@Override
	public void cancelBuild(String detailsUrl) {
		final String buildId = detailsUrl.substring(detailsUrl.lastIndexOf('/') + 1);

		try {
			try (Response cancelResponse = okHttpClient.newCall(
					new Request.Builder()
							.url("https://api.travis-ci.com/v3/build/" + buildId + "/cancel")
							.header("Authorization", "token " + authorizationToken)
							.header("Travis-API-Version", "3")
							.post(RequestBody.create(null, ""))
							.build()
			).execute()) {
				if (cancelResponse.isSuccessful()) {
					LOG.debug("Canceled build {}.", buildId);
				} else {
					LOG.debug("Cancel response for build {}: {}; {}.", buildId, cancelResponse.toString(), cancelResponse.body().string());
				}
			}
		} catch (IOException e) {
			LOG.error("Failed to cancel build {}.", buildId, e);
		}
	}

	@Override
	public void restartBuild(String detailsUrl) {
		final String buildId = detailsUrl.substring(detailsUrl.lastIndexOf('/') + 1);

		try {
			try (Response cancelResponse = okHttpClient.newCall(
					new Request.Builder()
							.url("https://api.travis-ci.com/v3/build/" + buildId + "/restart")
							.header("Authorization", "token " + authorizationToken)
							.header("Travis-API-Version", "3")
							.post(RequestBody.create(null, ""))
							.build()
			).execute()) {
				if (cancelResponse.isSuccessful()) {
					LOG.debug("Restarted build {}.", buildId);
				} else {
					LOG.debug("Restart response for build {}: {}; {}.", buildId, cancelResponse.toString(), cancelResponse.body().string());
				}
			}
		} catch (IOException e) {
			LOG.error("Failed to restart build {}.", buildId, e);
		}
	}
}
