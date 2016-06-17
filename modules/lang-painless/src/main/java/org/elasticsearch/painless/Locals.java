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

/**
 * Tracks user defined methods and variables across compilation phases.
 */
public abstract class Locals {
    private final Locals parent;

    public Locals(Locals parent) {
        this.parent = parent;
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
    
    public abstract Variable getVariable(Location location, String name);
    public abstract Variable addVariable(Location location, Type type, String name, boolean readonly, boolean reserved);
    public abstract Method getMethod(MethodKey key);
    public abstract void addMethod(Method method);
    public abstract int getMaxLoopCounter();
    public abstract Type getReturnType();
    public abstract void incrementScope();
    public abstract void decrementScope();

    /**
     * Tracks reserved variables.  Must be given to any source of input
     * prior to beginning the analysis phase so that reserved variables
     * are known ahead of time to assign appropriate slots without
     * being wasteful.
     */
    public interface Reserved {
        void markReserved(String name);
        boolean isReserved(String name);

        void setMaxLoopCounter(int max);
        int getMaxLoopCounter();
    }

    public static final class Variable {
        public final Location location;
        public final String name;
        public final Type type;
        public final int slot;
        public final boolean readonly;

        public boolean read = false;

        protected Variable(Location location, String name, Type type, int slot, boolean readonly) {
            this.location = location;
            this.name = name;
            this.type = type;
            this.slot = slot;
            this.readonly = readonly;
        }
    }
}