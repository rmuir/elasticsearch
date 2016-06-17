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

import org.elasticsearch.painless.Locals;
import org.elasticsearch.painless.Locals.FunctionReserved;
import org.elasticsearch.painless.Location;
import org.elasticsearch.painless.MethodWriter;
import org.elasticsearch.painless.Definition.MethodKey;
import org.objectweb.asm.Type;

import java.util.Collections;
import java.util.List;

public class ELambda extends AExpression implements ILambda {
    final String name;
    final FunctionReserved reserved;
    final List<String> paramTypeStrs;
    final List<String> paramNameStrs;
    final List<AStatement> statements;
    final List<AStatement> statements2;
    final List<SFunction> syntheticFunctions;
    ILambda impl;

    public ELambda(String name, List<SFunction> syntheticFunctions, FunctionReserved reserved, Location location,
                   List<String> paramTypes, List<String> paramNames, List<AStatement> statements, List<AStatement> statements2) {
        super(location);
        this.name = name;
        this.syntheticFunctions = syntheticFunctions;
        this.reserved = reserved;
        this.paramTypeStrs = Collections.unmodifiableList(paramTypes);
        this.paramNameStrs = Collections.unmodifiableList(paramNames);
        // this gets used just for an analysis pass to determine the capture variables (if any)
        this.statements = Collections.unmodifiableList(statements);
        // this is a clean copy of 'statements', its the one that gets used for the desugared method
        this.statements2 = Collections.unmodifiableList(statements2);
    }

    @Override
    void analyze(Locals locals) {
        if (statements == null || statements.isEmpty()) {
            throw createError(new IllegalArgumentException("Cannot generate an empty function [" + name + "]."));
        }
        
        SFunction throwAway = new SFunction(reserved, location, "def", name, 
                                            paramTypeStrs, paramNameStrs, statements, true, true);
        throwAway.generate();
        throwAway.analyze(locals);
        // this tells me the capture parameters and everything!

        // for nested lambdas, this may already exist!
        if (locals.getMethod(new MethodKey(name, paramTypeStrs.size())) == null) {
            // create a new synthetic method, analyze it, and add it to the queue to be written
            SFunction desugared = new SFunction(reserved, location, "def", name, 
                                                paramTypeStrs, paramNameStrs, statements2, true, false);
            desugared.generate();
            locals.addMethod(desugared.method);
            desugared.analyze(locals.getRoot());
            syntheticFunctions.add(desugared);
        }
        
        // setup reference
        EFunctionRef ref = new EFunctionRef(location, "this", name);
        ref.expected = expected;
        ref.analyze(locals);
        actual = ref.actual;
        impl = ref;
    }

    @Override
    void write(MethodWriter writer) {
        AExpression expr = (AExpression) impl;
        expr.write(writer);
    }

    @Override
    public String getPointer() {
        return impl.getPointer();
    }

    @Override
    public Type[] getCaptures() {
        return impl.getCaptures();
    }
}
