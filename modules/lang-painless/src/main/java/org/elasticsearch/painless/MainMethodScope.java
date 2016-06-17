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

public class MainMethodScope extends Locals {   
    
    public MainMethodScope(Locals parent, boolean usesScore, boolean usesCtx, int maxLoopCounter) {
        super(parent);
        
        // This reference.  Internal use only.
        defineVariable(null, Definition.getType("Object"), THIS, true);

        // Input map of variables passed to the script.
        defineVariable(null, Definition.getType("Map"), PARAMS, true);

        // Scorer parameter passed to the script.  Internal use only.
        defineVariable(null, Definition.DEF_TYPE, SCORER, true);

        // Doc parameter passed to the script. TODO: Currently working as a Map, we can do better?
        defineVariable(null, Definition.getType("Map"), DOC, true);

        // Aggregation _value parameter passed to the script.
        defineVariable(null, Definition.DEF_TYPE, VALUE, true);

        // Shortcut variables.

        // Document's score as a read-only double.
        if (usesScore) {
            defineVariable(null, Definition.DOUBLE_TYPE, SCORE, true);
        }

        // The ctx map set by executable scripts as a read-only map.
        if (usesCtx) {
            defineVariable(null, Definition.getType("Map"), CTX, true);
        }

        // Loop counter to catch infinite loops.  Internal use only.
        if (maxLoopCounter > 0) {
            defineVariable(null, Definition.INT_TYPE, LOOP, true);
        }
    }

    @Override
    public Type getReturnType() {
        return Definition.OBJECT_TYPE;
    }
}
