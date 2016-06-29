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

import com.carrotsearch.randomizedtesting.JUnit4MethodProvider;
import com.carrotsearch.randomizedtesting.RandomizedRunner;
import com.carrotsearch.randomizedtesting.annotations.TestMethodProviders;

import org.junit.Assert;
import org.junit.runner.RunWith;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

@TestMethodProviders({
    LuceneJUnit3MethodProvider.class,
    JUnit4MethodProvider.class
})
@RunWith(RandomizedRunner.class)
public class BaseTestCase extends Assert {
    
    public void check(Method method, int failures, String expectedOutput) throws Exception {
        String methodDesc = Type.getMethodDescriptor(method);
        Class<?> parentClass = method.getDeclaringClass();
        ClassReader reader = new ClassReader(parentClass.getName());
        AtomicLong violationCount = new AtomicLong();
        AtomicLong analyzedMethods = new AtomicLong();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream stream = new PrintStream(output, false, "UTF-8");
        reader.accept(new ClassVisitor(Opcodes.ASM5, null) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                if (name.equals(method.getName()) && desc.equals(methodDesc)) {
                    analyzedMethods.incrementAndGet();
                    return new CatchAnalyzer(reader.getClassName(), access, name, desc, signature, exceptions, violationCount, stream);
                }
                return null;
            }
        }, 0);
        stream.flush();
        String messages = output.toString("UTF-8");
        assertEquals("unexpected number of matching methods", 1L, analyzedMethods.get());
        assertEquals("unexpected failure count, output: " + messages, (long)failures, violationCount.get());
        if (expectedOutput == null) {
            assertTrue("output was not empty:\n" + messages, messages.isEmpty());
        } else {
            assertTrue("output didn't contain expected:\n" + messages, messages.contains(expectedOutput));
        }
    }
}
