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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks user defined methods and variables across compilation phases.
 */
public class LocalsImpl extends Locals {

    public static final class ExecuteReserved implements Reserved {
        public static final String THIS   = "#this";
        public static final String PARAMS = "params";
        public static final String SCORER = "#scorer";
        public static final String DOC    = "doc";
        public static final String VALUE  = "_value";
        public static final String SCORE  = "_score";
        public static final String CTX    = "ctx";
        public static final String LOOP   = "#loop";

        private boolean score = false;
        private boolean ctx = false;
        private int maxLoopCounter = 0;

        @Override
        public void markReserved(String name) {
            if (SCORE.equals(name)) {
                score = true;
            } else if (CTX.equals(name)) {
                ctx = true;
            }
        }

        @Override
        public boolean isReserved(String name) {
            return name.equals(THIS) || name.equals(PARAMS) || name.equals(SCORER) || name.equals(DOC) ||
                name.equals(VALUE) || name.equals(SCORE) || name.equals(CTX) || name.equals(LOOP);
        }

        public boolean usesScore() {
            return score;
        }

        public boolean usesCtx() {
            return ctx;
        }

        @Override
        public void setMaxLoopCounter(int max) {
            maxLoopCounter = max;
        }

        @Override
        public int getMaxLoopCounter() {
            return maxLoopCounter;
        }
    }

    public static final class FunctionReserved implements Reserved {
        public static final String THIS = "#this";
        public static final String LOOP = "#loop";

        private int maxLoopCounter = 0;

        public void markReserved(String name) {
            // Do nothing.
        }

        public boolean isReserved(String name) {
            return name.equals(THIS) || name.equals(LOOP);
        }

        @Override
        public void setMaxLoopCounter(int max) {
            maxLoopCounter = max;
        }

        @Override
        public int getMaxLoopCounter() {
            return maxLoopCounter;
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

    private final Reserved reserved;
    private final Map<MethodKey, Method> methods;
    private final Type rtnType;

    // TODO: this datastructure runs in linear time for nearly all operations. use linkedhashset instead?
    private final Deque<Integer> scopes = new ArrayDeque<>();
    private final Deque<Variable> variables = new ArrayDeque<>();
    
    public LocalsImpl(ExecuteReserved reserved, Map<MethodKey, Method> methods) {
        super(null);
        this.reserved = reserved;
        this.methods = methods;
        this.rtnType = Definition.OBJECT_TYPE;

        scopes.push(0);

        // Method variables.

        // This reference.  Internal use only.
        defineVariable(null, Definition.getType("Object"), ExecuteReserved.THIS, true);

        // Input map of variables passed to the script.
        defineVariable(null, Definition.getType("Map"), ExecuteReserved.PARAMS, true);

        // Scorer parameter passed to the script.  Internal use only.
        defineVariable(null, Definition.DEF_TYPE, ExecuteReserved.SCORER, true);

        // Doc parameter passed to the script. TODO: Currently working as a Map, we can do better?
        defineVariable(null, Definition.getType("Map"), ExecuteReserved.DOC, true);

        // Aggregation _value parameter passed to the script.
        defineVariable(null, Definition.DEF_TYPE, ExecuteReserved.VALUE, true);

        // Shortcut variables.

        // Document's score as a read-only double.
        if (reserved.usesScore()) {
            defineVariable(null, Definition.DOUBLE_TYPE, ExecuteReserved.SCORE, true);
        }

        // The ctx map set by executable scripts as a read-only map.
        if (reserved.usesCtx()) {
            defineVariable(null, Definition.getType("Map"), ExecuteReserved.CTX, true);
        }

        // Loop counter to catch infinite loops.  Internal use only.
        if (reserved.getMaxLoopCounter() > 0) {
            defineVariable(null, Definition.INT_TYPE, ExecuteReserved.LOOP, true);
        }
    }

    public LocalsImpl(FunctionReserved reserved, Locals locals, Type rtnType, List<Parameter> parameters) {
        super(locals);
        this.reserved = reserved;
        this.methods = new HashMap<>();
        this.rtnType = rtnType;

        scopes.push(0);

        for (Parameter parameter : parameters) {
            defineVariable(parameter.location, parameter.type, parameter.name, false);
        }

        // Loop counter to catch infinite loops.  Internal use only.
        if (reserved.getMaxLoopCounter() > 0) {
            defineVariable(null, Definition.INT_TYPE, ExecuteReserved.LOOP, true);
        }
    }

    /** Adds a new method to this locals. Do not use */
    public void addMethod(Method method) {
        // we deduplicate nested lambdas here. it comes with the territory of using tree nodes...
        methods.putIfAbsent(new MethodKey(method.name, method.arguments.size()), method);
    }

    @Override
    public int getMaxLoopCounter() {
        return reserved.getMaxLoopCounter();
    }

    @Override
    public Method lookupMethod(MethodKey key) {
        return methods.get(key);
    }

    @Override
    public Type getReturnType() {
        return rtnType;
    }

    @Override
    public Variable lookupVariable(Location location, String name) {
        for (Variable variable : variables) {
            if (variable.name.equals(name)) {
                return variable;
            }
        }

        return null;
    }

    @Override
    public Variable defineVariable(Location location, Type type, String name, boolean readonly) {
        int slot = getNextSlot();

        Variable variable = new Locals.Variable(location, name, type, slot, readonly);
        variables.push(variable);

        int update = scopes.pop() + 1;
        scopes.push(update);

        return variable;
    }
    
    @Override
    public int getNextSlot() {
        Variable previous = variables.peekFirst();

        if (previous == null) {
            return 0;
        }
        
        return previous.slot + previous.type.type.getSize();
    }
    
    @Override
    public boolean isReserved(String name) {
        return reserved.isReserved(name);
    }
}

