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

public class LocalScope extends Locals {

    public LocalScope(Locals parent) {
        super(parent);
    }

    @Override
    protected Variable lookupVariable(Location location, String name) {
        return null;
    }

    @Override
    protected Method lookupMethod(MethodKey key) {
        return null;
    }

    @Override
    public Variable addVariable(Location location, Type type, String name, boolean readonly) {
        return null;
    }

    @Override
    public void addMethod(Method method) {
        
    }

    @Override
    public int getMaxLoopCounter() {
        return getParent().getMaxLoopCounter();
    }

    @Override
    public Type getReturnType() {
        return getParent().getReturnType();
    }

    @Override
    public void incrementScope() {}

    @Override
    public void decrementScope() {}

}
