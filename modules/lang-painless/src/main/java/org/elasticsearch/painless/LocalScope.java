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

import java.util.HashMap;
import java.util.Map;

public class LocalScope extends Locals {
    int nextSlotNumber;
    Map<String,Variable> variables;
    Map<MethodKey,Method> methods;

    public LocalScope(Locals parent) {
        super(parent);
        if (parent == null) {
            this.nextSlotNumber = 0;
        } else {
            this.nextSlotNumber = parent.getNextSlot();
        }
    }

    @Override
    protected Variable lookupVariable(Location location, String name) {
        if (variables == null) {
            return null;
        }
        return variables.get(name);
    }

    @Override
    protected Method lookupMethod(MethodKey key) {
        if (methods == null) {
            return null;
        }
        return methods.get(key);
    }

    @Override
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

    @Override
    public void addMethod(Method method) {
        if (methods == null) {
            methods = new HashMap<>();
        }
        methods.put(new MethodKey(method.name, method.arguments.size()), method);
        // TODO: check result
    }

    @Override
    public Type getReturnType() {
        return getParent().getReturnType();
    }
    
    @Override
    public int getNextSlot() {
        return nextSlotNumber;
    }
}
