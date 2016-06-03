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

package org.elasticsearch.painless.node;

import org.elasticsearch.painless.Definition;
import org.elasticsearch.painless.Location;
import org.elasticsearch.painless.MethodWriter;
import org.elasticsearch.painless.Variables;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import static org.elasticsearch.painless.WriterConstants.LAMBDA_BOOTSTRAP_HANDLE;

import java.lang.invoke.LambdaMetafactory;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

/**
 * Represents a function reference.
 */
public class EFunctionRef extends AExpression {
    public final String type;
    public final String call;
    
    private String invokedName;
    private Type invokedType;
    private Handle implMethod;
    private Type samMethodType;
    private Type interfaceType;

    public EFunctionRef(Location location, String type, String call) {
        super(location);

        this.type = type;
        this.call = call;
    }

    @Override
    void analyze(Variables variables) {
        if (expected == null) {
            throw new UnsupportedOperationException("dynamic case not implemented yet");
        }
        // check its really a functional interface
        // for e.g. Comparable
        java.lang.reflect.Method method = getFunctionalMethod(expected.clazz);
        // e.g. compareTo
        invokedName = method.getName();
        // e.g. (Object)Comparator
        invokedType = Type.getMethodType(Type.getType(expected.clazz));
        // e.g. (Object,Object)int
        interfaceType = Type.getMethodType(Type.getMethodDescriptor(method));
        // lookup requested method
        Definition.Struct struct = Definition.getType(type).struct;
        // look for a static impl first
        Definition.Method impl = struct.staticMethods.get(new Definition.MethodKey(call, method.getParameterCount()));
        // otherwise a virtual impl
        if (impl == null) {
            impl = struct.methods.get(new Definition.MethodKey(call, method.getParameterCount()-1));
        }
        // TODO: constructor
        // otherwise not found:
        if (impl == null) {
            throw createError(new IllegalArgumentException("Unknown reference [" + type + "::" + call + "] matching " +
                                                           "[" + expected.clazz + "]"));
        }
        
        final int tag;
        if (Modifier.isStatic(impl.modifiers)) {
            tag = Opcodes.H_INVOKESTATIC;
        } else {
            tag = Opcodes.H_INVOKEVIRTUAL;
        }
        implMethod = new Handle(tag, struct.type.getInternalName(), impl.name, impl.method.getDescriptor());
        // e.g. (Object,Object)int
        if (Modifier.isStatic(impl.modifiers)) {
            samMethodType = Type.getMethodType(impl.method.getReturnType(), impl.method.getArgumentTypes());
        } else {
            Type[] argTypes = impl.method.getArgumentTypes();
            Type[] params = new Type[argTypes.length + 1];
            System.arraycopy(argTypes, 0, params, 1, argTypes.length);
            params[0] = struct.type;
            samMethodType = Type.getMethodType(impl.method.getReturnType(), params);
        }
        // ok we're good
        actual = expected;
    }

    @Override
    void write(MethodWriter writer) {
        if (expected == null) {
            throw createError(new IllegalStateException("Illegal tree structure."));
        }
        writer.writeDebugInfo(location);
        // currently if the interface differs, we ask for a bridge, but maybe we should do smarter checking?
        // either way, stuff will fail if its wrong :)
        if (interfaceType.equals(samMethodType)) {
            writer.invokeDynamic(invokedName, invokedType.getDescriptor(), LAMBDA_BOOTSTRAP_HANDLE, 
                                 samMethodType, implMethod, samMethodType);
        } else {
            writer.invokeDynamic(invokedName, invokedType.getDescriptor(), LAMBDA_BOOTSTRAP_HANDLE, 
                                 samMethodType, implMethod, samMethodType, LambdaMetafactory.FLAG_BRIDGES, 1, interfaceType);
        }
    }
    
    static final Set<Definition.MethodKey> OBJECT_METHODS = new HashSet<>();
    static {
        for (java.lang.reflect.Method m : Object.class.getMethods()) {
            OBJECT_METHODS.add(new Definition.MethodKey(m.getName(), m.getParameterCount()));
        }
    }

    // TODO: move all this crap out, to Definition to compute up front
    java.lang.reflect.Method getFunctionalMethod(Class<?> clazz) {
        if (!clazz.isInterface()) {
            throw createError(new IllegalArgumentException("Cannot convert function reference [" 
                                                          + type + "::" + call + "] to [" + expected.name + "]"));
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
        throw createError(new IllegalArgumentException("Cannot convert function reference [" 
                                                     + type + "::" + call + "] to [" + expected.name + "]"));
    }
}
