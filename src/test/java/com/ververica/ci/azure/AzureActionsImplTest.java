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

package com.ververica.ci.azure;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Tests for {@link AzureActionsImplTest}.
 */
public class AzureActionsImplTest {

    @Test
    public void testNormalizeUrl() {
        String shortUrl = "https://dev.azure.com/bar/0f3463e8-185e-423b-aa88-6cc39182caea/_build/results?buildId=123";
        String longUrl = "https://dev.azure.com/baz/0f3463e8-185e-423b-aa88-6cc39182caea/_build/results?buildId=44&view=logs&jobId=c6e12662-7e76-5fac-6ded-4b654ce98c1b";
        String malformedUrl = "foo";

        assertEquals("https://dev.azure.com/bar/0f3463e8-185e-423b-aa88-6cc39182caea/_build/results?buildId=123", AzureActionsImpl.internalNormalizeUrl(shortUrl));
        assertEquals("https://dev.azure.com/baz/0f3463e8-185e-423b-aa88-6cc39182caea/_build/results?buildId=44", AzureActionsImpl.internalNormalizeUrl(longUrl));
        try {
            AzureActionsImpl.internalNormalizeUrl(malformedUrl);
            fail("Should have failed.");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void testExtractProjectUrl() {
        String shortUrl = "https://dev.azure.com/bar/0f3463e8-185e-423b-aa88-6cc39182caea/_build/results?buildId=123";
        String longUrl = "https://dev.azure.com/baz/0f3463e8-185e-423b-aa88-6cc39182caea/_build/results?buildId=44&view=logs&jobId=c6e12662-7e76-5fac-6ded-4b654ce98c1b";
        String malformedUrl = "foo";

        assertEquals("bar/0f3463e8-185e-423b-aa88-6cc39182caea", AzureActionsImpl.extractProjectSlug(shortUrl));
        assertEquals("baz/0f3463e8-185e-423b-aa88-6cc39182caea", AzureActionsImpl.extractProjectSlug(longUrl));
        try {
            AzureActionsImpl.extractProjectSlug(malformedUrl);
            fail("Should have failed.");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void testExtractBuildId() {
        String shortUrl = "https://dev.azure.com/chesnay/0f3463e8-185e-423b-aa88-6cc39182caea/_build/results?buildId=123";
        String longUrl = "https://dev.azure.com/chesnay/0f3463e8-185e-423b-aa88-6cc39182caea/_build/results?buildId=44&view=logs&jobId=c6e12662-7e76-5fac-6ded-4b654ce98c1b";
        String malformedUrl = "foo";

        assertEquals("123", AzureActionsImpl.extractBuildId(shortUrl));
        assertEquals("44", AzureActionsImpl.extractBuildId(longUrl));
        try {
            AzureActionsImpl.extractBuildId(malformedUrl);
            fail("Should have failed.");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

}