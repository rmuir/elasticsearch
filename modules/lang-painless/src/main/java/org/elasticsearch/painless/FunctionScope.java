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

import java.util.List;

public class FunctionScope extends LocalScope {   
    private final Type returnType;

    public FunctionScope(Locals program, Type returnType, List<Parameter> parameters, int maxLoopCounter) {
        super(program);
        this.returnType = returnType;
        for (Parameter parameter : parameters) {
            defineVariable(parameter.location, parameter.type, parameter.name, false);
        }
        // Loop counter to catch infinite loops.  Internal use only.
        if (maxLoopCounter > 0) {
            defineVariable(null, Definition.INT_TYPE, LOOP, true);
        }
    }

    @Override
    public Type getReturnType() {
        return returnType;
    }
}
