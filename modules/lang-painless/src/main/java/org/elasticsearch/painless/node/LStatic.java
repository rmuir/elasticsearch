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
import org.elasticsearch.painless.MethodWriter;
import org.elasticsearch.painless.Variables;

/**
 * Represents a static type target.
 */
public final class LStatic extends ALink {

    final String type;

    public LStatic(int offset, String type) {
        super(offset, 0);

        this.type = type;
    }

    @Override
    ALink analyze(Variables variables) {
        if (before != null) {
            throw error2(new IllegalArgumentException("Illegal static type [" + type + "] after target already defined."));
        }

        try {
            after = Definition.getType(type);
            statik = true;
        } catch (IllegalArgumentException exception) {
            throw error2(new IllegalArgumentException("Not a type [" + type + "]."));
        }

        return this;
    }

    @Override
    void write(MethodWriter writer) {
        throw error2(new IllegalStateException("Illegal tree structure."));
    }

    @Override
    void load(MethodWriter writer) {
        throw error2(new IllegalStateException("Illegal tree structure."));
    }

    @Override
    void store(MethodWriter writer) {
        throw error2(new IllegalStateException("Illegal tree structure."));
    }
}
