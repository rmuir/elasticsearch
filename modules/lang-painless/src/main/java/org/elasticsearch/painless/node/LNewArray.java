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
import org.elasticsearch.painless.Definition.Type;
import org.elasticsearch.painless.Variables;
import org.elasticsearch.painless.MethodWriter;

import java.util.List;

/**
 * Represents an array instantiation.
 */
public final class LNewArray extends ALink {

    final String type;
    final List<AExpression> arguments;

    public LNewArray(int line, int offset, String location, String type, List<AExpression> arguments) {
        super(line, offset, location, -1);

        this.type = type;
        this.arguments = arguments;
    }

    @Override
    ALink analyze(Variables variables) {
        if (before != null) {
            throw new IllegalArgumentException(error("Cannot create a new array with a target already defined."));
        } else if (store) {
            throw new IllegalArgumentException(error("Cannot assign a value to a new array."));
        } else if (!load) {
            throw new IllegalArgumentException(error("A newly created array must be read."));
        }

        final Type type;

        try {
            type = Definition.getType(this.type);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(error("Not a type [" + this.type + "]."));
        }

        for (int argument = 0; argument < arguments.size(); ++argument) {
            AExpression expression = arguments.get(argument);

            expression.expected = Definition.INT_TYPE;
            expression.analyze(variables);
            arguments.set(argument, expression.cast(variables));
        }

        after = Definition.getType(type.struct, arguments.size());

        return this;
    }

    @Override
    void write(MethodWriter writer) {
        // Do nothing.
    }

    @Override
    void load(MethodWriter writer) {
        writer.writeDebugInfo(offset);
        for (AExpression argument : arguments) {
            argument.write(writer);
        }

        if (arguments.size() > 1) {
            writer.visitMultiANewArrayInsn(after.type.getDescriptor(), after.type.getDimensions());
        } else {
            writer.newArray(Definition.getType(after.struct, 0).type);
        }
    }

    @Override
    void store(MethodWriter writer) {
        throw new IllegalStateException(error("Illegal tree structure."));
    }
}
