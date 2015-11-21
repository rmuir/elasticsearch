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

package org.elasticsearch.plugins;

import org.elasticsearch.common.http.client.HttpDownloadHelper;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Path;

import static org.elasticsearch.common.settings.Settings.settingsBuilder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

/**
 *
 */
public class PluginManagerUnitTests extends ESTestCase {

    public void testThatConfigDirectoryCanBeOutsideOfElasticsearchHomeDirectory() throws IOException {
        String pluginName = randomAsciiOfLength(10);
        Path homeFolder = createTempDir();
        Path genericConfigFolder = createTempDir();

        Settings settings = settingsBuilder()
                .put("path.conf", genericConfigFolder)
                .put("path.home", homeFolder)
                .build();
        Environment environment = new Environment(settings);

        PluginManager.PluginHandle pluginHandle = new PluginManager.PluginHandle(pluginName, "version", "user");
        Path configDirPath = pluginHandle.configDir(environment).normalize();
        Path expectedDirPath = genericConfigFolder.resolve(pluginName).normalize();
        assertEquals(configDirPath, expectedDirPath);
    }

    public void testGithubPluginName() throws IOException {
        String user = randomAsciiOfLength(6);
        String pluginName = randomAsciiOfLength(10);
        PluginManager.PluginHandle handle = PluginManager.PluginHandle.parse(user + "/" + pluginName);
        assertThat(handle.name, is(pluginName));
        assertThat(handle.urls(), hasSize(1));
        assertThat(handle.urls().get(0).toExternalForm(), is(new URL("https", "github.com", "/" + user + "/" + pluginName + "/" + "archive/master.zip").toExternalForm()));
    }

    public void testDownloadHelperChecksums() throws Exception {
        // Sanity check to make sure the checksum functions never change how they checksum things
        assertEquals("0beec7b5ea3f0fdbc95d0dd47f3c5bc275da8a33",
                HttpDownloadHelper.SHA1_CHECKSUM.checksum("foo".getBytes(Charset.forName("UTF-8"))));
        assertEquals("acbd18db4cc2f85cedef654fccc4a4d8",
                HttpDownloadHelper.MD5_CHECKSUM.checksum("foo".getBytes(Charset.forName("UTF-8"))));
    }
}
