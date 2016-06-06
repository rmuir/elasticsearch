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

package org.elasticsearch.painless;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

/** 
 * computes "everything you need" to call LambdaMetaFactory, given an expected interface,
 * and reference class + method name
 */
public class FunctionRef {
    // XXX: this is a mess, because of ASM versus MethodHandle types
    // clean all this up, move reflection out of here into definition, etc etc
    public final String invokedName;
    public final Type invokedType;
    public final Handle implMethod;
    public final Type samMethodType;
    public final Type interfaceType;

    public final MethodHandle implMethodHandle;
    
    public FunctionRef(Class<?> expected, String type, String call) {
        boolean isCtorReference = "new".equals(call);
        // check its really a functional interface
        // for e.g. Comparable
        java.lang.reflect.Method method = getFunctionalMethod(type, call, expected);
        // e.g. compareTo
        invokedName = method.getName();
        // e.g. (Object)Comparator
        invokedType = Type.getMethodType(Type.getType(expected));
        // e.g. (Object,Object)int
        interfaceType = Type.getMethodType(Type.getMethodDescriptor(method));
        // lookup requested method
        Definition.Struct struct = Definition.getType(type).struct;
        final Definition.Method impl;
        // ctor ref
        if (isCtorReference) {
            impl = struct.constructors.get(new Definition.MethodKey("<init>", method.getParameterCount()));
        } else {
            // look for a static impl first
            Definition.Method staticImpl = struct.staticMethods.get(new Definition.MethodKey(call, method.getParameterCount()));
            if (staticImpl == null) {
                // otherwise a virtual impl
                impl = struct.methods.get(new Definition.MethodKey(call, method.getParameterCount()-1));
            } else {
                impl = staticImpl;
            }
        }
        if (impl == null) {
            throw new IllegalArgumentException("Unknown reference [" + type + "::" + call + "] matching " +
                                               "[" + expected + "]");
        }
        
        final int tag;
        if (isCtorReference) {
            tag = Opcodes.H_NEWINVOKESPECIAL;
        } else if (Modifier.isStatic(impl.modifiers)) {
            tag = Opcodes.H_INVOKESTATIC;
        } else {
            tag = Opcodes.H_INVOKEVIRTUAL;
        }
        implMethod = new Handle(tag, struct.type.getInternalName(), impl.name, impl.method.getDescriptor());
        implMethodHandle = impl.handle;
        if (isCtorReference) {
            samMethodType = Type.getMethodType(interfaceType.getReturnType(), impl.method.getArgumentTypes());
        } else if (Modifier.isStatic(impl.modifiers)) {
            samMethodType = Type.getMethodType(impl.method.getReturnType(), impl.method.getArgumentTypes());
        } else {
            Type[] argTypes = impl.method.getArgumentTypes();
            Type[] params = new Type[argTypes.length + 1];
            System.arraycopy(argTypes, 0, params, 1, argTypes.length);
            params[0] = struct.type;
            samMethodType = Type.getMethodType(impl.method.getReturnType(), params);
        }
    }
    
    static final Set<Definition.MethodKey> OBJECT_METHODS = new HashSet<>();
    static {
        for (java.lang.reflect.Method m : Object.class.getMethods()) {
            OBJECT_METHODS.add(new Definition.MethodKey(m.getName(), m.getParameterCount()));
        }
    }

    // TODO: move all this crap out, to Definition to compute up front
    java.lang.reflect.Method getFunctionalMethod(String type, String call, Class<?> clazz) {
        if (!clazz.isInterface()) {
            throw new IllegalArgumentException("Cannot convert function reference [" 
                                               + type + "::" + call + "] to [" + clazz + "]");
        }
        for (java.lang.reflect.Method m : clazz.getMethods()) {
            if (m.isDefault()) {
                continue;
            }
            if (OBJECT_METHODS.contains(new Definition.MethodKey(m.getName(), m.getParameterCount()))) {
                continue;
            }
            return m;
        }
        throw new IllegalArgumentException("Cannot convert function reference [" 
                                           + type + "::" + call + "] to [" + clazz + "]");
    }
}
