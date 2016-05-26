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
import org.elasticsearch.painless.Variables;
import org.objectweb.asm.Label;
import org.elasticsearch.painless.MethodWriter;

/**
 * Represents a while loop.
 */
public final class SWhile extends AStatement {

    final int maxLoopCounter;
    AExpression condition;
    final SBlock block;

    public SWhile(int line, int offset, String location, int maxLoopCounter, AExpression condition, SBlock block) {
        super(line, offset, location);

        this.maxLoopCounter = maxLoopCounter;
        this.condition = condition;
        this.block = block;
    }

    @Override
    void analyze(Variables variables) {
        variables.incrementScope();

        condition.expected = Definition.BOOLEAN_TYPE;
        condition.analyze(variables);
        condition = condition.cast(variables);

        boolean continuous = false;

        if (condition.constant != null) {
            continuous = (boolean)condition.constant;

            if (!continuous) {
                throw new IllegalArgumentException(error("Extraneous while loop."));
            }

            if (block == null) {
                throw new IllegalArgumentException(error("While loop has no escape."));
            }
        }

        if (block != null) {
            block.beginLoop = true;
            block.inLoop = true;

            block.analyze(variables);

            if (block.loopEscape && !block.anyContinue) {
                throw new IllegalArgumentException(error("Extraneous while loop."));
            }

            if (continuous && !block.anyBreak) {
                methodEscape = true;
                allEscape = true;
            }

            block.statementCount = Math.max(1, block.statementCount);
        }

        statementCount = 1;

        if (maxLoopCounter > 0) {
            loopCounterSlot = variables.getVariable(location, "#loop").slot;
        }

        variables.decrementScope();
    }

    @Override
    void write(MethodWriter writer) {
        writer.writeStatementOffset(offset);
        Label begin = new Label();
        Label end = new Label();

        writer.mark(begin);

        condition.fals = end;
        condition.write(writer);

        if (block != null) {
            writer.writeLoopCounter(loopCounterSlot, Math.max(1, block.statementCount), offset);

            block.continu = begin;
            block.brake = end;
            block.write(writer);
        } else {
            writer.writeLoopCounter(loopCounterSlot, 1, offset);
        }

        if (block == null || !block.allEscape) {
            writer.goTo(begin);
        }

        writer.mark(end);
    }
}
