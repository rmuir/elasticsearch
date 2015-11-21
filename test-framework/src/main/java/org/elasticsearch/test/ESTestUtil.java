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

package org.elasticsearch.test;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertFileExists;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;

import java.io.FilePermission;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.PermissionCollection;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.elasticsearch.plugins.PluginInfo;
import org.junit.Assert;

/** Grab bag of static methods used here and there */
public class ESTestUtil {
    /** no instance */
    private ESTestUtil() {}
    
    /** Creates a jar file underneath {@code dir} with the specified files. Manifest can be null */
    public static URL makeJar(Path dir, String name, Manifest manifest, String... files) throws IOException {
        Path jarpath = dir.resolve(name);
        ZipOutputStream out;
        if (manifest == null) {
            out = new JarOutputStream(Files.newOutputStream(jarpath, StandardOpenOption.CREATE));
        } else {
            out = new JarOutputStream(Files.newOutputStream(jarpath, StandardOpenOption.CREATE), manifest);
        }
        for (String file : files) {
            out.putNextEntry(new ZipEntry(file));
        }
        out.close();
        return jarpath.toUri().toURL();
    }
    
    /** convenience method to write a plugin properties file */
    public static void writeProperties(Path pluginDir, String... stringProps) throws IOException {
        assert stringProps.length % 2 == 0;
        Files.createDirectories(pluginDir);
        Path propertiesFile = pluginDir.resolve(PluginInfo.ES_PLUGIN_PROPERTIES);
        Properties properties =  new Properties();
        for (int i = 0; i < stringProps.length; i += 2) {
            properties.put(stringProps[i], stringProps[i + 1]);
        }
        try (OutputStream out = Files.newOutputStream(propertiesFile)) {
            properties.store(out, "");
        }
    }
    
    /**
     * Check that a file contains a given String
     * @param dir root dir for file
     * @param filename relative path from root dir to file
     * @param expected expected content (if null, we don't expect any file)
     */
    public static void assertFileContent(Path dir, String filename, String expected) throws IOException {
        Assert.assertThat(Files.exists(dir), is(true));
        Path file = dir.resolve(filename);
        if (expected == null) {
            Assert.assertThat("file [" + file + "] should not exist.", Files.exists(file), is(false));
        } else {
            assertFileExists(file);
            String fileContent = new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
            // trim the string content to prevent different handling on windows vs. unix and CR chars...
            Assert.assertThat(fileContent.trim(), equalTo(expected.trim()));
        }
    }
    
    /** 
     * checks exact file permissions, meaning those and only those for that path.
     */
    public static void assertExactPermissions(FilePermission expected, PermissionCollection actual) {
        String target = expected.getName(); // see javadocs
        Set<String> permissionSet = ESTestCase.asSet(expected.getActions().split(","));
        boolean read = permissionSet.remove("read");
        boolean readlink = permissionSet.remove("readlink");
        boolean write = permissionSet.remove("write");
        boolean delete = permissionSet.remove("delete");
        boolean execute = permissionSet.remove("execute");
        ESTestCase.assertTrue("unrecognized permission: " + permissionSet, permissionSet.isEmpty());
        ESTestCase.assertEquals(read, actual.implies(new FilePermission(target, "read")));
        ESTestCase.assertEquals(readlink, actual.implies(new FilePermission(target, "readlink")));
        ESTestCase.assertEquals(write, actual.implies(new FilePermission(target, "write")));
        ESTestCase.assertEquals(delete, actual.implies(new FilePermission(target, "delete")));
        ESTestCase.assertEquals(execute, actual.implies(new FilePermission(target, "execute")));
    }

    /**
     * checks that this path has no permissions
     */
    public static void assertNoPermissions(Path path, PermissionCollection actual) {
        String target = path.toString();
        ESTestCase.assertFalse(actual.implies(new FilePermission(target, "read")));
        ESTestCase.assertFalse(actual.implies(new FilePermission(target, "readlink")));
        ESTestCase.assertFalse(actual.implies(new FilePermission(target, "write")));
        ESTestCase.assertFalse(actual.implies(new FilePermission(target, "delete")));
        ESTestCase.assertFalse(actual.implies(new FilePermission(target, "execute")));
    }
}
