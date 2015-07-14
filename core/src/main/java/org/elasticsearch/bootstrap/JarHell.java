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

import org.elasticsearch.Version;
import org.elasticsearch.common.SuppressForbidden;
import org.elasticsearch.common.io.PathUtils;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/** Simple check for duplicate class files across the classpath */
public class JarHell {

    /** Simple driver class, can be used eg. from builds. Returns non-zero on jar-hell */
    public static void main(String args[]) throws Exception {
        checkJarHell();
    }

    /**
     * Checks the current classloader for duplicate classes
     * @throws IllegalStateException if jar hell was found
     */
    public static void checkJarHell() throws Exception {
        ClassLoader loader = JarHell.class.getClassLoader();
        if (loader instanceof URLClassLoader == false) {
           return;
        }
        ESLogger logger = Loggers.getLogger(JarHell.class);
        if (logger.isDebugEnabled()) {
            logger.debug("java.class.path: {}", System.getProperty("java.class.path"));
            logger.debug("sun.boot.class.path: {}", System.getProperty("sun.boot.class.path"));
            logger.debug("classloader urls: {}", Arrays.toString(((URLClassLoader)loader).getURLs()));
        }
        checkJarHell(((URLClassLoader)loader).getURLs());
    }

    /**
     * Checks the set of URLs for duplicate classes
     * @throws IllegalStateException if jar hell was found
     */
    @SuppressForbidden(reason = "needs JarFile for speed, just reading entries")
    public static void checkJarHell(URL urls[]) throws Exception {
        ESLogger logger = Loggers.getLogger(JarHell.class);
        // we don't try to be sneaky and use deprecated/internal/not portable stuff
        // like sun.boot.class.path, and with jigsaw we don't yet have a way to get
        // a "list" at all. So just exclude any elements underneath the java home
        String javaHome = System.getProperty("java.home");
        logger.debug("java.home: {}", javaHome);
        final Map<String,Path> clazzes = new HashMap<>(32768);
        Set<Path> seenJars = new HashSet<>();
        for (final URL url : urls) {
            final Path path = PathUtils.get(url.toURI());
            // exclude system resources
            if (path.startsWith(javaHome)) {
                logger.debug("excluding system resource: {}", path);
                continue;
            }
            if (path.toString().endsWith(".jar")) {
                if (!seenJars.add(path)) {
                    logger.debug("excluding duplicate classpath element: {}", path);
                    continue; // we can't fail because of sheistiness with joda-time
                }
                logger.debug("examining jar: {}", path);
                try (JarFile file = new JarFile(path.toString())) {
                    Manifest manifest = file.getManifest();
                    if (manifest != null) {
                        checkManifest(manifest, path);
                    }
                    // inspect entries
                    Enumeration<JarEntry> elements = file.entries();
                    while (elements.hasMoreElements()) {
                        String entry = elements.nextElement().getName();
                        if (entry.endsWith(".class")) {
                            // for jar format, the separator is defined as /
                            entry = entry.replace('/', '.').substring(0, entry.length() - 6);
                            checkClass(clazzes, entry, path);
                        }
                    }
                }
            } else {
                logger.debug("examining directory: {}", path);
                // case for tests: where we have class files in the classpath
                final Path root = PathUtils.get(url.toURI());
                final String sep = root.getFileSystem().getSeparator();
                Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        String entry = root.relativize(file).toString();
                        if (entry.endsWith(".class")) {
                            // normalize with the os separator
                            entry = entry.replace(sep, ".").substring(0,  entry.length() - 6);
                            checkClass(clazzes, entry, path);
                        }
                        return super.visitFile(file, attrs);
                    }
                });
            }
        }
    }

    /** inspect manifest for sure incompatibilities */
    static void checkManifest(Manifest manifest, Path jar) {
        // give a nice error if jar requires a newer java version
        String systemVersion = System.getProperty("java.specification.version");
        String targetVersion = manifest.getMainAttributes().getValue("X-Compile-Target-JDK");
        if (targetVersion != null) {
            float current = Float.POSITIVE_INFINITY;
            float target = Float.NEGATIVE_INFINITY;
            try {
                current = Float.parseFloat(systemVersion);
                target = Float.parseFloat(targetVersion);
            } catch (NumberFormatException e) {
                // some spec changed, time for a more complex parser
            }
            if (current < target) {
                throw new IllegalStateException(jar + " requires Java " + targetVersion
                        + ", your system: " + systemVersion);
            }
        }

        // give a nice error if jar is compiled against different es version
        String systemESVersion = Version.CURRENT.toString();
        String targetESVersion = manifest.getMainAttributes().getValue("X-Compile-Elasticsearch-Version");
        if (targetESVersion != null && targetESVersion.equals(systemESVersion) == false) {
            throw new IllegalStateException(jar + " requires Elasticsearch " + targetESVersion
                    + ", your system: " + systemESVersion);
        }
    }

    static void checkClass(Map<String,Path> clazzes, String clazz, Path jarpath) {
        Path previous = clazzes.put(clazz, jarpath);
        if (previous != null) {
            if (previous.equals(jarpath)) {
                if (clazz.startsWith("org.apache.xmlbeans")) {
                    return; // https://issues.apache.org/jira/browse/XMLBEANS-499
                }
                // throw a better exception in this ridiculous case.
                // unfortunately the zip file format allows this buggy possibility
                // UweSays: It can, but should be considered as bug :-)
                throw new IllegalStateException("jar hell!" + System.lineSeparator() +
                        "class: " + clazz + System.lineSeparator() +
                        "exists multiple times in jar: " + jarpath + " !!!!!!!!!");
            } else {
                if (clazz.startsWith("org.apache.log4j")) {
                    return; // go figure, jar hell for what should be System.out.println...
                }
                if (clazz.equals("org.joda.time.base.BaseDateTime")) {
                    return; // apparently this is intentional... clean this up
                }
                throw new IllegalStateException("jar hell!" + System.lineSeparator() +
                        "class: " + clazz + System.lineSeparator() +
                        "jar1: " + previous + System.lineSeparator() +
                        "jar2: " + jarpath);
            }
        }
    }
}
