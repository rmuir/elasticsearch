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

package org.elasticsearch.bootstrap;

import org.apache.lucene.util.Constants;
import org.elasticsearch.test.ESTestCase;

import java.io.FilePermission;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Permissions;

import static org.elasticsearch.test.ESTestUtil.assertExactPermissions;

public class SecurityTests extends ESTestCase {
    
    public void testEnsureExists() throws IOException {
        Path p = createTempDir();

        // directory exists
        Path exists = p.resolve("exists");
        Files.createDirectory(exists);
        Security.ensureDirectoryExists(exists);
        Files.createTempFile(exists, null, null);
    }
    
    public void testEnsureNotExists() throws IOException { 
        Path p = createTempDir();

        // directory does not exist: create it
        Path notExists = p.resolve("notexists");
        Security.ensureDirectoryExists(notExists);
        Files.createTempFile(notExists, null, null);
    }
    
    public void testEnsureRegularFile() throws IOException {
        Path p = createTempDir();

        // regular file
        Path regularFile = p.resolve("regular");
        Files.createFile(regularFile);
        try {
            Security.ensureDirectoryExists(regularFile);
            fail("didn't get expected exception");
        } catch (IOException expected) {}
    }
    
    public void testEnsureSymlink() throws IOException {
        Path p = createTempDir();
        
        Path exists = p.resolve("exists");
        Files.createDirectory(exists);

        // symlink
        Path linkExists = p.resolve("linkExists");
        try {
            Files.createSymbolicLink(linkExists, exists);
        } catch (UnsupportedOperationException | IOException e) {
            assumeNoException("test requires filesystem that supports symbolic links", e);
        } catch (SecurityException e) {
            assumeNoException("test cannot create symbolic links with security manager enabled", e);
        }
        Security.ensureDirectoryExists(linkExists);
        Files.createTempFile(linkExists, null, null);
    }
    
    public void testEnsureBrokenSymlink() throws IOException {
        Path p = createTempDir();

        // broken symlink
        Path brokenLink = p.resolve("brokenLink");
        try {
            Files.createSymbolicLink(brokenLink, p.resolve("nonexistent"));
        } catch (UnsupportedOperationException | IOException e) {
            assumeNoException("test requires filesystem that supports symbolic links", e);
        } catch (SecurityException e) {
            assumeNoException("test cannot create symbolic links with security manager enabled", e);
        }
        try {
            Security.ensureDirectoryExists(brokenLink);
            fail("didn't get expected exception");
        } catch (IOException expected) {}
    }

    /** can't execute processes */
    public void testProcessExecution() throws Exception {
        assumeTrue("test requires security manager", System.getSecurityManager() != null);
        try {
            Runtime.getRuntime().exec("ls");
            fail("didn't get expected exception");
        } catch (SecurityException expected) {}
    }

    /** When a configured dir is a symlink, test that permissions work on link target */
    public void testSymlinkPermissions() throws IOException {
        // see https://github.com/elastic/elasticsearch/issues/12170
        assumeFalse("windows does not automatically grant permission to the target of symlinks", Constants.WINDOWS);
        Path dir = createTempDir();

        Path target = dir.resolve("target");
        Files.createDirectory(target);

        // symlink
        Path link = dir.resolve("link");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            assumeNoException("test requires filesystem that supports symbolic links", e);
        } catch (SecurityException e) {
            assumeNoException("test cannot create symbolic links with security manager enabled", e);
        }
        Permissions permissions = new Permissions();
        Security.addPath(permissions, "testing", link, "read");
        assertExactPermissions(new FilePermission(link.toString(), "read"), permissions);
        assertExactPermissions(new FilePermission(link.resolve("foo").toString(), "read"), permissions);
        assertExactPermissions(new FilePermission(target.toString(), "read"), permissions);
        assertExactPermissions(new FilePermission(target.resolve("foo").toString(), "read"), permissions);
    }
}
