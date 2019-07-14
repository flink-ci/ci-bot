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

package com.ververica.github;

public class GitHubCheckerStatus {
	private final State state;
	private final String detailsUrl;
	private final String name;

	public GitHubCheckerStatus(State state, String detailsUrl, String name) {
		this.state = state;
		this.detailsUrl = detailsUrl;
		this.name = name;
	}

	public State getState() {
		return state;
	}

	public String getDetailsUrl() {
		return detailsUrl;
	}

	public String getName() {
		return name;
	}

	public enum State {
		PENDING,
		SUCCESS,
		CANCELED,
		FAILURE
	}
}
