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

import java.net.URL;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import org.elasticsearch.test.ESTestCase;

import static org.elasticsearch.test.ESTestUtil.makeJar;

/** These evil tests change core java system properties to test JarHell */
public class JarHellJavaChangerTests extends ESTestCase {
    
    public void testBootclasspathLeniency() throws Exception {
        Path dir = createTempDir();
        String previousJavaHome = System.getProperty("java.home");
        System.setProperty("java.home", dir.toString());
        URL[] jars = {makeJar(dir, "foo.jar", null, "DuplicateClass.class"), makeJar(dir, "bar.jar", null, "DuplicateClass.class")};
        try {
            JarHell.checkJarHell(jars);
        } finally {
            System.setProperty("java.home", previousJavaHome);
        }
    }
    
    public void testRequiredJDKVersionIsOK() throws Exception {
        Path dir = createTempDir();
        String previousJavaVersion = System.getProperty("java.specification.version");
        System.setProperty("java.specification.version", "1.7");
        
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0.0");
        attributes.put(new Attributes.Name("X-Compile-Target-JDK"), "1.7");
        URL[] jars = {makeJar(dir, "foo.jar", manifest, "Foo.class")};
        try {
            JarHell.checkJarHell(jars);
        } finally {
            System.setProperty("java.specification.version", previousJavaVersion);
        }
    }
    
    public void testBadJDKVersionProperty() throws Exception {
        Path dir = createTempDir();
        String previousJavaVersion = System.getProperty("java.specification.version");
        System.setProperty("java.specification.version", "bogus");
        
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0.0");
        attributes.put(new Attributes.Name("X-Compile-Target-JDK"), "1.7");
        URL[] jars = {makeJar(dir, "foo.jar", manifest, "Foo.class")};
        try {
            JarHell.checkJarHell(jars);
        } finally {
            System.setProperty("java.specification.version", previousJavaVersion);
        }
    }
}
