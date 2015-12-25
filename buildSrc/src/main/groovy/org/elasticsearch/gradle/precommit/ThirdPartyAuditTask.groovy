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
package org.elasticsearch.gradle.precommit;

import org.apache.tools.ant.BuildEvent;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.BuildListener;
import org.apache.tools.ant.BuildLogger;
import org.apache.tools.ant.DefaultLogger;
import org.apache.tools.ant.Project;
import org.elasticsearch.gradle.AntTask;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.FileCollection;

import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Basic static checking to keep tabs on third party JARs
 */
public class ThirdPartyAuditTask extends AntTask {
    
    // patterns for classes to exclude, because we understand their issues
    private String[] excludes = new String[0];
    
    ThirdPartyAuditTask() {
        // we depend on this because its the only reliable configuration
        // this probably makes the build slower: gradle you suck here when it comes to configurations, you pay the price.
        dependsOn(project.configurations.testCompile);
        description = "Checks third party JAR bytecode for missing classes, use of internal APIs, and other horrors'";
    }
    
    /**
     * classes that should be excluded from the scan,
     * e.g. because we know what sheisty stuff those particular classes are up to.
     */
    public void setExcludes(String[] classes) {
        for (String s : classes) {
            if (s.indexOf('*') != -1) {
                throw new IllegalArgumentException("illegal third party audit exclusion: '" + s + "', wildcards are not permitted!");
            }
        }
        excludes = classes;
    }
    
    /**
     * Returns current list of exclusions.
     */
    public String[] getExcludes() {
        return excludes;
    }

    // yes, we parse Uwe Schindler's errors to find missing classes. Just don't let him know!
    static final Pattern MISSING_CLASS_PATTERN =
        Pattern.compile(/WARNING: The referenced class '(.*)' cannot be loaded\. Please fix the classpath\!/);
        
    static final Pattern VIOLATION_PATTERN = 
        Pattern.compile(/\s\sin ([a-zA-Z0-9\$\.]+) \(.*\)/);

    // we log everything (except missing classes warnings). Those we handle ourselves.
    static class EvilLogger extends DefaultLogger {
        final Set<String> missingClasses = new TreeSet<>();
        final Map<String,List<String>> violations = new TreeMap<>();
        String previousLine = null;

        @Override
        public void messageLogged(BuildEvent event) {
            if (event.getTask().getClass() == de.thetaphi.forbiddenapis.ant.AntTask.class) {
                if (event.getPriority() == Project.MSG_WARN) {
                    Matcher m = MISSING_CLASS_PATTERN.matcher(event.getMessage());
                    if (m.matches()) {
                        missingClasses.add(m.group(1).replace('.', '/') + ".class");
                    }
                } else if (event.getPriority() == Project.MSG_ERR) {
                    Matcher m = VIOLATION_PATTERN.matcher(event.getMessage());
                    if (m.matches()) {
                        String violation = previousLine + '\n' + event.getMessage();
                        String clazz = m.group(1).replace('.', '/') + ".class";
                        List<String> current = violations.get(clazz);
                        if (current == null) {
                            current = new ArrayList<>();
                            violations.put(clazz, current);
                        }
                        current.add(violation);
                    }
                    previousLine = event.getMessage();
                }
            }
            super.messageLogged(event);
        }
    }

    @Override
    protected BuildLogger makeLogger(PrintStream stream, int outputLevel) {
        DefaultLogger log = new EvilLogger();
        log.errorPrintStream = stream;
        log.outputPrintStream = stream;
        log.messageOutputLevel = outputLevel;
        return log;
    }

    @Override
    protected void runAnt(AntBuilder ant) {
        Configuration configuration = project.configurations.findByName('runtime');
        if (configuration == null) {
            // some projects apparently do not have 'runtime'? what a nice inconsistency,
            // basically only serves to waste time in build logic!
            configuration = project.configurations.findByName('testCompile');
        }
        assert configuration != null;
        ant.project.addTaskDefinition('thirdPartyAudit', de.thetaphi.forbiddenapis.ant.AntTask);

        // we only want third party dependencies.
        FileCollection jars = configuration.fileCollection({ dependency ->
            dependency.group.startsWith("org.elasticsearch") == false
        });

        // we don't want provided dependencies, which we have already scanned. e.g. don't
        // scan ES core's dependencies for every single plugin
        Configuration provided = project.configurations.findByName('provided');
        if (provided != null) {
            jars -= provided;
        }

        // no dependencies matched, we are done
        if (jars.isEmpty()) {
            return;
        }

        // print which jars we are going to scan, always
        // this is not the time to try to be succinct! Forbidden will print plenty on its own!
        Set<String> names = new TreeSet<>();
        for (File jar : jars) {
            names.add(jar.getName());
        }
        logger.error("Scanning: " + names);

        // TODO: forbidden-apis + zipfileset gives O(n^2) behavior unless we dump to a tmpdir first,
        // and then remove our temp dir afterwards. don't complain: try it yourself.
        // we don't use gradle temp dir handling, just google it, or try it yourself.

        File tmpDir = new File(project.buildDir, 'tmp/thirdPartyAudit');

        // clean up any previous mess (if we failed), then unzip everything to one directory
        ant.delete(dir: tmpDir.getAbsolutePath());
        tmpDir.mkdirs();
        for (File jar : jars) {
            ant.unzip(src: jar.getAbsolutePath(), dest: tmpDir.getAbsolutePath());
        }

        // convert exclusion class names to binary file names
        String[] excludedFiles = new String[excludes.length];
        for (int i = 0; i < excludes.length; i++) {
            excludedFiles[i] = excludes[i].replace('.', '/') + ".class";
        }
        Set<String> excludedSet = new TreeSet<>(Arrays.asList(excludedFiles));

        // jarHellReprise
        Set<String> sheistySet = getSheistyClasses(tmpDir.toPath());

        try { 
            ant.thirdPartyAudit(internalRuntimeForbidden: true,
                            failOnUnsupportedJava: false,
                            failOnMissingClasses: false,
                            classpath: configuration.asPath) {
                fileset(dir: tmpDir)
            }
        } catch (BuildException ignore) {}

	EvilLogger evilLogger = null;
        for (BuildListener listener : ant.project.getBuildListeners()) {
            if (listener instanceof EvilLogger) {
                evilLogger = (EvilLogger) listener;
                break;
            }
        }
        assert evilLogger != null;

        // keep our whitelist up to date
        Set<String> bogusExclusions = new TreeSet<>(excludedSet);
        bogusExclusions.removeAll(sheistySet);
        bogusExclusions.removeAll(evilLogger.missingClasses);
        bogusExclusions.removeAll(evilLogger.violations.keySet());
        if (!bogusExclusions.isEmpty()) {
            throw new IllegalStateException("Invalid exclusions, nothing is wrong with these classes: " + bogusExclusions);
        }

        // don't duplicate classes with the JDK
        sheistySet.removeAll(excludedSet);
        if (!sheistySet.isEmpty()) {
            throw new IllegalStateException("JAR HELL WITH JDK! " + sheistySet);
        }

        // don't allow a broken classpath
        evilLogger.missingClasses.removeAll(excludedSet);
        if (!evilLogger.missingClasses.isEmpty()) {
            throw new IllegalStateException("CLASSES ARE MISSING! " + evilLogger.missingClasses);
        }

        // don't use internal classes
        evilLogger.violations.keySet().removeAll(excludedSet);
        if (!evilLogger.violations.isEmpty()) {
            throw new IllegalStateException("VIOLATIONS WERE FOUND! " + evilLogger.violations);
        }

        // clean up our mess (if we succeed)
        ant.delete(dir: tmpDir.getAbsolutePath());
    }

    /**
     * check for sheisty classes: if they also exist in the extensions classloader, its jar hell with the jdk!
     */
    private Set<String> getSheistyClasses(Path root) {
        // system.parent = extensions loader.
        // note: for jigsaw, this evilness will need modifications (e.g. use jrt filesystem!). 
        // but groovy/gradle needs to work at all first!
        ClassLoader ext = ClassLoader.getSystemClassLoader().getParent();
        assert ext != null;
        
        Set<String> sheistySet = new TreeSet<>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String entry = root.relativize(file).toString();
                if (entry.endsWith(".class")) {
                    if (ext.getResource(entry) != null) {
                        sheistySet.add(entry);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return sheistySet;
    }
}
