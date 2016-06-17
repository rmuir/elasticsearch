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


import org.elasticsearch.painless.Definition.Type;
import org.elasticsearch.painless.LocalsImpl.Parameter;

import java.util.ArrayList;
import java.util.List;

public class LambdaScope extends LocalScope {
    private List<Variable> captures = new ArrayList<>();

    public LambdaScope(Locals parent, List<Parameter> parameters) {
        super(parent);
        for (Parameter parameter : parameters) {
            defineVariable(parameter.location, parameter.type, parameter.name, false);
        }
    }
    
    @Override
    public Variable getVariable(Location location, String name) {
        Variable variable = lookupVariable(location, name);
        if (variable != null) {
            return variable;
        }
        if (getParent() != null) {
            variable = getParent().getVariable(location, name);
            if (variable != null) {
                // make it read-only, and record that it was used.
                Variable readOnly = new Variable(variable.location, variable.name, variable.type, variable.slot, true);
                captures.add(readOnly);
                return readOnly;
            }
        }
        throw location.createError(new IllegalArgumentException("Variable [" + name + "] is not defined."));
    }

    @Override
    public Type getReturnType() {
        return Definition.DEF_TYPE;
    }
    
    public List<Variable> getCaptures() {
        return captures;
    }
}
