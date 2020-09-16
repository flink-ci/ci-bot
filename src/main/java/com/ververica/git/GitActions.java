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

package com.ververica.git;

import org.eclipse.jgit.api.errors.GitAPIException;

public interface GitActions extends AutoCloseable {
	void addRemote(String repositoryUrl, String remoteName) throws GitException;

	void fetchBranch(String remoteBranchName, String remoteName, boolean fetchPrBranch) throws GitException;

	void pushBranch(String localBranchName, String remoteBranchName, String remoteName, boolean force, String authenticationToken) throws GitException;

	void deleteBranch(String localBranchName, boolean force) throws GitException;

	void deleteBranch(String remoteBranchName, String remoteName, boolean force, String authenticationToken) throws GitException;

	String getHeadCommitSHA(String localBranchName) throws GitException;

	void cleanup() throws GitAPIException;

	void close();
}
