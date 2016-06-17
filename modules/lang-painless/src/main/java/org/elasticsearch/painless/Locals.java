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

import org.elasticsearch.painless.Definition.Method;
import org.elasticsearch.painless.Definition.MethodKey;
import org.elasticsearch.painless.Definition.Type;
import org.elasticsearch.painless.Locals.Parameter;
import org.elasticsearch.painless.Locals.Variable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tracks user defined methods and variables across compilation phases.
 */
public class Locals {
    public static final String THIS   = "#this";
    public static final String PARAMS = "params";
    public static final String SCORER = "#scorer";
    public static final String DOC    = "doc";
    public static final String VALUE  = "_value";
    public static final String SCORE  = "_score";
    public static final String CTX    = "ctx";
    public static final String LOOP   = "#loop";
    
    public static final Set<String> KEYWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            THIS,PARAMS,SCORER,DOC,VALUE,SCORE,CTX,LOOP
    )));
    
    private final Locals parent;
    private final Type returnType;
    int nextSlotNumber;
    Map<String,Variable> variables;
    Map<MethodKey,Method> methods;

    Locals(Locals parent) {
        this(parent, parent.getReturnType());
    }
    
    Locals(Locals parent, Type returnType) {
        this.parent = parent;
        this.returnType = returnType;
        if (parent == null) {
            this.nextSlotNumber = 0;
        } else {
            this.nextSlotNumber = parent.getNextSlot();
        }
    }

    public Locals getParent() {
        return parent;
    }
    
    /** Returns top-level locals. */
    public Locals getRoot() {
        Locals locals = this;
        while (locals.getParent() != null) {
            locals = locals.getParent();
        }
        return locals;
    }
    
    public Variable getVariable(Location location, String name) {
        Variable variable = lookupVariable(location, name);
        if (variable != null) {
            return variable;
        }
        if (parent != null) {
            return parent.getVariable(location, name);
        }
        throw location.createError(new IllegalArgumentException("Variable [" + name + "] is not defined."));
    }
    
    public final boolean hasVariable(String name) {
        Variable variable = lookupVariable(null, name);
        if (variable != null) {
            return true;
        }
        if (parent != null) {
            return parent.hasVariable(name);
        }
        return false;
    }
    
    protected Variable lookupVariable(Location location, String name) {
        if (variables == null) {
            return null;
        }
        return variables.get(name);
    }
    
    public final Method getMethod(MethodKey key) {
        Method method = lookupMethod(key);
        if (method != null) {
            return method;
        }
        if (parent != null) {
            return parent.getMethod(key);
        }
        return null;
    }

    protected Method lookupMethod(MethodKey key) {
        if (methods == null) {
            return null;
        }
        return methods.get(key);
    }
    
    public final Variable addVariable(Location location, Type type, String name, boolean readonly) {
        if (hasVariable(name)) {
            throw location.createError(new IllegalArgumentException("Variable [" + name + "] is already defined."));
        }
        if (KEYWORDS.contains(name)) {
            throw location.createError(new IllegalArgumentException("Variable [" + name + "] is reserved."));
        }
        return defineVariable(location, type, name, readonly);
    }
    
    public Variable defineVariable(Location location, Type type, String name, boolean readonly) {
        if (variables == null) {
            variables = new HashMap<>();
        }
        Variable variable = new Variable(location, name, type, readonly);
        variable.slot = getNextSlot();
        variables.put(name, variable); // TODO: check result
        nextSlotNumber += type.type.getSize();
        return variable;
    }
    
    public void addMethod(Method method) {
        if (methods == null) {
            methods = new HashMap<>();
        }
        methods.put(new MethodKey(method.name, method.arguments.size()), method);
        // TODO: check result
    }
    
    public Type getReturnType() {
        return returnType;
    }

    public int getNextSlot() {
        return nextSlotNumber;
    }

    public static final class Variable {
        public final Location location;
        public final String name;
        public final Type type;
        int slot = -1;
        public final boolean readonly;
        
        public Variable(Location location, String name, Type type, boolean readonly) {
            this.location = location;
            this.name = name;
            this.type = type;
            this.readonly = readonly;
        }
        
        public int getSlot() {
            return slot;
        }
    }
    
    public static final class Parameter {
        public final Location location;
        public final String name;
        public final Type type;

        public Parameter(Location location, String name, Type type) {
            this.location = location;
            this.name = name;
            this.type = type;
        }
    }
    
    public static Locals newScope(Locals currentScope) {
        return new Locals(currentScope);
    }
    
    public static Locals newFunctionScope(Locals programScope, Type returnType, List<Parameter> parameters, int maxLoopCounter) {
        Locals locals = new Locals(programScope, returnType);
        for (Parameter parameter : parameters) {
            locals.defineVariable(parameter.location, parameter.type, parameter.name, false);
        }
        // Loop counter to catch infinite loops.  Internal use only.
        if (maxLoopCounter > 0) {
            locals.defineVariable(null, Definition.INT_TYPE, LOOP, true);
        }
        return locals;
    }
}
