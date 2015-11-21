package org.elasticsearch.bootstrap;

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

import java.io.FilePermission;
import java.nio.file.Path;
import java.security.Permissions;

import org.elasticsearch.common.io.PathUtils;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.test.ESTestCase;

import static org.elasticsearch.test.ESTestUtil.assertExactPermissions;
import static org.elasticsearch.test.ESTestUtil.assertNoPermissions;

/** Permissions tests that override jvm sysprops */
public class SecurityJavaChangerTests extends ESTestCase {

    /** test generated permissions */
    public void testGeneratedPermissions() throws Exception {
        Path path = createTempDir();
        // make a fake ES home and ensure we only grant permissions to that.
        Path esHome = path.resolve("esHome");
        Settings.Builder settingsBuilder = Settings.builder();
        settingsBuilder.put("path.home", esHome.toString());
        Settings settings = settingsBuilder.build();
        
        Path fakeTmpDir = createTempDir();
        String realTmpDir = System.getProperty("java.io.tmpdir");
        Permissions permissions;
        try {
            System.setProperty("java.io.tmpdir", fakeTmpDir.toString());
            Environment environment = new Environment(settings);
            permissions = Security.createPermissions(environment);
        } finally {
            System.setProperty("java.io.tmpdir", realTmpDir);
        }
        
        // the fake es home
        assertNoPermissions(esHome, permissions);
        // its parent
        assertNoPermissions(esHome.getParent(), permissions);
        // some other sibling
        assertNoPermissions(esHome.getParent().resolve("other"), permissions);
        // double check we overwrote java.io.tmpdir correctly for the test
        assertNoPermissions(PathUtils.get(realTmpDir), permissions);
    }

    /** test generated permissions for all configured paths */
    public void testEnvironmentPaths() throws Exception {
        Path path = createTempDir();
        // make a fake ES home and ensure we only grant permissions to that.
        Path esHome = path.resolve("esHome");

        Settings.Builder settingsBuilder = Settings.builder();
        settingsBuilder.put("path.home", esHome.resolve("home").toString());
        settingsBuilder.put("path.conf", esHome.resolve("conf").toString());
        settingsBuilder.put("path.scripts", esHome.resolve("scripts").toString());
        settingsBuilder.put("path.plugins", esHome.resolve("plugins").toString());
        settingsBuilder.putArray("path.data", esHome.resolve("data1").toString(), esHome.resolve("data2").toString());
        settingsBuilder.put("path.shared_data", esHome.resolve("custom").toString());
        settingsBuilder.put("path.logs", esHome.resolve("logs").toString());
        settingsBuilder.put("pidfile", esHome.resolve("test.pid").toString());
        Settings settings = settingsBuilder.build();

        Path fakeTmpDir = createTempDir();
        String realTmpDir = System.getProperty("java.io.tmpdir");
        Permissions permissions;
        Environment environment;
        try {
            System.setProperty("java.io.tmpdir", fakeTmpDir.toString());
            environment = new Environment(settings);
            permissions = Security.createPermissions(environment);
        } finally {
            System.setProperty("java.io.tmpdir", realTmpDir);
        }

        // the fake es home
        assertNoPermissions(esHome, permissions);
        // its parent
        assertNoPermissions(esHome.getParent(), permissions);
        // some other sibling
        assertNoPermissions(esHome.getParent().resolve("other"), permissions);
        // double check we overwrote java.io.tmpdir correctly for the test
        assertNoPermissions(PathUtils.get(realTmpDir), permissions);
 
        // check that all directories got permissions:

        // bin file: ro
        assertExactPermissions(new FilePermission(environment.binFile().toString(), "read,readlink"), permissions);
        // lib file: ro
        assertExactPermissions(new FilePermission(environment.libFile().toString(), "read,readlink"), permissions);
        // config file: ro
        assertExactPermissions(new FilePermission(environment.configFile().toString(), "read,readlink"), permissions);
        // scripts file: ro
        assertExactPermissions(new FilePermission(environment.scriptsFile().toString(), "read,readlink"), permissions);
        // plugins: ro
        assertExactPermissions(new FilePermission(environment.pluginsFile().toString(), "read,readlink"), permissions);

        // data paths: r/w
        for (Path dataPath : environment.dataFiles()) {
            assertExactPermissions(new FilePermission(dataPath.toString(), "read,readlink,write,delete"), permissions);
        }
        for (Path dataPath : environment.dataWithClusterFiles()) {
            assertExactPermissions(new FilePermission(dataPath.toString(), "read,readlink,write,delete"), permissions);
        }
        assertExactPermissions(new FilePermission(environment.sharedDataFile().toString(), "read,readlink,write,delete"), permissions);
        // logs: r/w
        assertExactPermissions(new FilePermission(environment.logsFile().toString(), "read,readlink,write,delete"), permissions);
        // temp dir: r/w
        assertExactPermissions(new FilePermission(fakeTmpDir.toString(), "read,readlink,write,delete"), permissions);
        // PID file: delete only (for the shutdown hook)
        assertExactPermissions(new FilePermission(environment.pidFile().toString(), "delete"), permissions);
    }
}
