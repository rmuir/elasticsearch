package org.elasticsearch.plugins;

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

import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;

import org.elasticsearch.Build;
import org.elasticsearch.Version;
import org.elasticsearch.test.ESTestCase;
import org.junit.After;

/** PluginManager unit tests that change system properties */
public class PluginManagerJavaChangerTests extends ESTestCase {
    @After
    public void cleanSystemProperty() {
        System.clearProperty(PluginManager.PROPERTY_SUPPORT_STAGING_URLS);
    }
    
    public void testSimplifiedNaming() throws IOException {
        String pluginName = randomAsciiOfLength(10);
        PluginManager.PluginHandle handle = PluginManager.PluginHandle.parse(pluginName);

        boolean supportStagingUrls = randomBoolean();
        if (supportStagingUrls) {
            System.setProperty(PluginManager.PROPERTY_SUPPORT_STAGING_URLS, "true");
        }

        Iterator<URL> iterator = handle.urls().iterator();

        if (supportStagingUrls) {
            String expectedStagingURL = String.format(Locale.ROOT, "https://download.elastic.co/elasticsearch/staging/%s-%s/org/elasticsearch/plugin/%s/%s/%s-%s.zip",
                    Version.CURRENT.number(), Build.CURRENT.shortHash(), pluginName, Version.CURRENT.number(), pluginName, Version.CURRENT.number());
            assertThat(iterator.next().toExternalForm(), is(expectedStagingURL));
        }

        URL expected = new URL("https", "download.elastic.co", "/elasticsearch/release/org/elasticsearch/plugin/" + pluginName + "/" + Version.CURRENT.number() + "/" +
                pluginName + "-" + Version.CURRENT.number() + ".zip");
        assertThat(iterator.next().toExternalForm(), is(expected.toExternalForm()));

        assertThat(iterator.hasNext(), is(false));
    }

    public void testOfficialPluginName() throws IOException {
        String randomPluginName = randomFrom(new ArrayList<>(PluginManager.OFFICIAL_PLUGINS));
        PluginManager.PluginHandle handle = PluginManager.PluginHandle.parse(randomPluginName);
        assertThat(handle.name, is(randomPluginName));

        boolean supportStagingUrls = randomBoolean();
        if (supportStagingUrls) {
            System.setProperty(PluginManager.PROPERTY_SUPPORT_STAGING_URLS, "true");
        }

        Iterator<URL> iterator = handle.urls().iterator();

        if (supportStagingUrls) {
            String expectedStagingUrl = String.format(Locale.ROOT, "https://download.elastic.co/elasticsearch/staging/%s-%s/org/elasticsearch/plugin/%s/%s/%s-%s.zip",
                    Version.CURRENT.number(), Build.CURRENT.shortHash(), randomPluginName, Version.CURRENT.number(), randomPluginName, Version.CURRENT.number());
            assertThat(iterator.next().toExternalForm(), is(expectedStagingUrl));
        }

        String releaseUrl = String.format(Locale.ROOT, "https://download.elastic.co/elasticsearch/release/org/elasticsearch/plugin/%s/%s/%s-%s.zip",
                randomPluginName, Version.CURRENT.number(), randomPluginName, Version.CURRENT.number());
        assertThat(iterator.next().toExternalForm(), is(releaseUrl));

        assertThat(iterator.hasNext(), is(false));
    }
}
