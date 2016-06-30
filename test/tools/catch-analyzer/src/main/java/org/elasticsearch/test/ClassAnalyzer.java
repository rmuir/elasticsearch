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

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicLong;

/** class-level analyzer */
class ClassAnalyzer extends ClassVisitor {
    final String className;
    final PrintStream out;
    final AtomicLong violationCount = new AtomicLong();
    boolean suppressed;

    ClassAnalyzer(String className, PrintStream out) {
        super(Opcodes.ASM5);
        this.className = className;
        this.out = out;
    }
    
    @Override
    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
        if (desc.contains("SwallowsExceptions")) {
            suppressed = true;
        }
        return null;
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        // don't scan synthetic methods (have not found any issues with them, but it would be unfair)
        if ((access & Opcodes.ACC_SYNTHETIC) != 0) {
            return null;
        }
        // TODO: allow scanning ctors (we just have to handle the super call better)
        if ("<init>".equals(name)) {
            return null;
        }
        // TODO: fix bugs in ASM, that cause problems when doing dataflow analysis of the following methods:
        // indexwriter.shutdown
        if ("shutdown".equals(name) && "org/apache/lucene/index/IndexWriter".equals(className)) {
            return null;
        }
        // fst.arc readtargetarc
        if ("readLastTargetArc".equals(name) && "org/apache/lucene/util/fst/FST".equals(className)) {
            return null;
        }
        // fst.bytesreader 
        if ("seekToNextNode".equals(name) && "org/apache/lucene/util/fst/FST".equals(className)) {
            return null;
        }
        // suppressed whole class by user
        // TODO: like methods, we could do trickery to ensure _at least one method_ fails.
        // but better is, don't apply to whole classes!
        if (suppressed) {
            return null;
        }
        return new MethodAnalyzer(className, access, name, desc, 
                                 signature, exceptions, violationCount, out);
    }
}
