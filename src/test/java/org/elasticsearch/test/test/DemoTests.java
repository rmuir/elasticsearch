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

package org.elasticsearch.test.test;

import org.elasticsearch.action.count.CountResponse;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.test.ElasticsearchIntegrationTest;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCount;

/** Very simple test, useful for debugging test framework, recovering 
 *  from total aftermath of the codebase, etc */
public class DemoTests extends ElasticsearchIntegrationTest {
    public void testDemo() throws Exception {
        createIndex("test");
        ensureGreen();

        client().prepareIndex("test", "type1", "mydoc").setSource("field1", "value1").get();
        refresh();

        CountResponse countResponse = client().prepareCount().setQuery(QueryBuilders.idsQuery("type1").ids("mydoc")).get();
        assertHitCount(countResponse, 1L);
    }
}
