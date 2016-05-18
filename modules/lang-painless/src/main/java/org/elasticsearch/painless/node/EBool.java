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

import org.elasticsearch.painless.CompilerSettings;
import org.elasticsearch.painless.Definition;
import org.elasticsearch.painless.Operation;
import org.elasticsearch.painless.Variables;
import org.objectweb.asm.Label;
import org.elasticsearch.painless.MethodWriter;

/**
 * Represents a boolean expression.
 */
public final class EBool extends AExpression {

    final Operation operation;
    AExpression left;
    AExpression right;

    public EBool(final int line, final String location, final Operation operation, final AExpression left, final AExpression right) {
        super(line, location);

        this.operation = operation;
        this.left = left;
        this.right = right;
    }

    @Override
    void analyze(final CompilerSettings settings, final Definition definition, final Variables variables) {
        left.expected = definition.getType("boolean");
        left.analyze(settings, definition, variables);
        left = left.cast(settings, definition, variables);

        right.expected = definition.getType("boolean");
        right.analyze(settings, definition, variables);
        right = right.cast(settings, definition, variables);

        if (left.constant != null && right.constant != null) {
            if (operation == Operation.AND) {
                constant = (boolean)left.constant && (boolean)right.constant;
            } else if (operation == Operation.OR) {
                constant = (boolean)left.constant || (boolean)right.constant;
            } else {
                throw new IllegalStateException(error("Illegal tree structure."));
            }
        }

        actual = definition.getType("boolean");
    }

    @Override
    void write(final CompilerSettings settings, final Definition definition, final MethodWriter adapter) {
        if (tru != null || fals != null) {
            if (operation == Operation.AND) {
                final Label localfals = fals == null ? new Label() : fals;

                left.fals = localfals;
                right.tru = tru;
                right.fals = fals;

                left.write(settings, definition, adapter);
                right.write(settings, definition, adapter);

                if (fals == null) {
                    adapter.mark(localfals);
                }
            } else if (operation == Operation.OR) {
                final Label localtru = tru == null ? new Label() : tru;

                left.tru = localtru;
                right.tru = tru;
                right.fals = fals;

                left.write(settings, definition, adapter);
                right.write(settings, definition, adapter);

                if (tru == null) {
                    adapter.mark(localtru);
                }
            } else {
                throw new IllegalStateException(error("Illegal tree structure."));
            }
        } else {
            if (operation == Operation.AND) {
                final Label localfals = new Label();
                final Label end = new Label();

                left.fals = localfals;
                right.fals = localfals;

                left.write(settings, definition, adapter);
                right.write(settings, definition, adapter);

                adapter.push(true);
                adapter.goTo(end);
                adapter.mark(localfals);
                adapter.push(false);
                adapter.mark(end);
            } else if (operation == Operation.OR) {
                final Label localtru = new Label();
                final Label localfals = new Label();
                final Label end = new Label();

                left.tru = localtru;
                right.fals = localfals;

                left.write(settings, definition, adapter);
                right.write(settings, definition, adapter);

                adapter.mark(localtru);
                adapter.push(true);
                adapter.goTo(end);
                adapter.mark(localfals);
                adapter.push(false);
                adapter.mark(end);
            } else {
                throw new IllegalStateException(error("Illegal tree structure."));
            }
        }
    }
}
