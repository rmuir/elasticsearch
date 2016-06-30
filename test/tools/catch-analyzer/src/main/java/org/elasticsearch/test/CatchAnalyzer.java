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

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;

/**
 * Analyzes methods for broken catch blocks. These are catch blocks that somehow
 * drop the original exception (via any possible codepath). It is ok to wrap the exception
 * with another one, e.g.:
 * <ul>
 *   <li>new OtherException(..., original, ...)
 *   <li>otherException.initCause(original)
 *   <li>otherException.addSuppressed(original)
 * </ul>
 */
public class CatchAnalyzer {
 
    /** Exception thrown when some exceptions are swallowed */
    public static class SwallowedException extends RuntimeException {
        public SwallowedException(String message) {
            super(message);
        }
    }
    
    /** Main method, takes directories as parameter. These must also be on classpath!!!! */
    public static void main(String args[]) throws Exception {
        long scannedCount = 0;
        long startTime = System.currentTimeMillis();
        List<Path> files = new ArrayList<>();
        // step 1: collect files
        for (String arg : args) {
            Path dir = Paths.get(arg);
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (file.toString().endsWith(".class")) {
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        // step 2: sort
        files.sort((x,y) -> x.toAbsolutePath().toString().compareTo(y.toAbsolutePath().toString()));
        // step 3: process
        for (Path file : files) {
            byte bytes[] = Files.readAllBytes(file);
            ClassReader reader = new ClassReader(bytes);
            // entirely synthetic class, e.g. enum switch table, which always masks NoSuchFieldError!!!!!
            if ((reader.getAccess() & Opcodes.ACC_SYNTHETIC) != 0) {
                continue;
            }
            ClassAnalyzer analyzer = new ClassAnalyzer(reader.getClassName(), System.out);
            reader.accept(analyzer, 0);
            scannedCount++;
        }
        long endTime = System.currentTimeMillis();
        // step 4: print
        long violationCount = 0;
        
        
        System.out.println("Scanned " + scannedCount + " classes in " + (endTime - startTime) + " ms");
        if (violationCount > 0) {
            throw new SwallowedException(violationCount + " violations were found, see log for more details");
        }
    }
}
