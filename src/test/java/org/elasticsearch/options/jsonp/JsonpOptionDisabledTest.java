/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.options.jsonp;

import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.http.HttpServerTransport;
import org.elasticsearch.rest.helper.HttpClient;
import org.elasticsearch.rest.helper.HttpClientResponse;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.elasticsearch.test.ElasticsearchIntegrationTest.ClusterScope;
import org.elasticsearch.test.ElasticsearchIntegrationTest.Scope;
import org.junit.Test;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

// Test to make sure that our JSONp response is disabled
@ClusterScope(scope = Scope.TEST, numDataNodes = 1)
public class JsonpOptionDisabledTest extends ElasticsearchIntegrationTest {

    // Build our cluster settings
    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return ImmutableSettings.settingsBuilder()
                .put("http.jsonp.enable", false)
                .put(super.nodeSettings(nodeOrdinal))
                .build();
    }

    // Make sure our response has both the callback as well as our "JSONP is disabled" message. 
    @Test
    public void testThatJSONPisDisabled() throws Exception {
        // Make the HTTP request
        HttpServerTransport httpServerTransport = internalCluster().getDataNodeInstance(HttpServerTransport.class);
        HttpClient httpClient = new HttpClient(httpServerTransport.boundAddress().publishAddress());
        HttpClientResponse response = httpClient.request("/?callback=DisabledJSONPCallback");
        assertThat(response.getHeader("Content-Type"), is("application/javascript"));
        assertThat(response.response(), containsString("DisabledJSONPCallback("));
        assertThat(response.response(), containsString("JSONP is disabled"));
    }
}
