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
import org.elasticsearch.painless.Definition.Method;
import org.elasticsearch.painless.Definition.Sort;
import org.elasticsearch.painless.Variables;
import org.elasticsearch.painless.MethodWriter;

/**
 * Represents a map load/store shortcut. (Internal only.)
 */
final class LMapShortcut extends ALink {

    AExpression index;
    Method getter;
    Method setter;

    LMapShortcut(int line, String location, AExpression index) {
        super(line, location, 2);

        this.index = index;
    }

    @Override
    ALink analyze(Variables variables) {
        getter = before.struct.methods.get(new Definition.MethodKey("get", 1));
        setter = before.struct.methods.get(new Definition.MethodKey("put", 2));

        if (getter != null && (getter.rtn.sort == Sort.VOID || getter.numberOfArguments() != 1)) {
            throw new IllegalArgumentException(error("Illegal map get shortcut for type [" + before.name + "]."));
        }

        if (setter != null && setter.numberOfArguments() != 2) {
            throw new IllegalArgumentException(error("Illegal map set shortcut for type [" + before.name + "]."));
        }

        if (getter != null && setter != null &&
            (!getter.argumentAt(0).equals(setter.argumentAt(0)) || !getter.rtn.equals(setter.argumentAt(1)))) {
            throw new IllegalArgumentException(error("Shortcut argument types must match."));
        }

        if ((load || store) && (!load || getter != null) && (!store || setter != null)) {
            index.expected = setter != null ? setter.argumentAt(0) : getter.argumentAt(0);
            index.analyze(variables);
            index = index.cast(variables);

            after = setter != null ? setter.argumentAt(1) : getter.rtn;
        } else {
            throw new IllegalArgumentException(error("Illegal map shortcut for type [" + before.name + "]."));
        }

        return this;
    }

    @Override
    void write(MethodWriter adapter) {
        index.write(adapter);
    }

    @Override
    void load(MethodWriter adapter) {
        if (java.lang.reflect.Modifier.isInterface(getter.owner.clazz.getModifiers())) {
            adapter.invokeInterface(getter.owner.type, getter.toAsmMethod());
        } else {
            adapter.invokeVirtual(getter.owner.type, getter.toAsmMethod());
        }

        if (!getter.rtn.clazz.equals(getter.handle.type().returnType())) {
            adapter.checkCast(getter.rtn.type);
        }
    }

    @Override
    void store(MethodWriter adapter) {
        if (java.lang.reflect.Modifier.isInterface(setter.owner.clazz.getModifiers())) {
            adapter.invokeInterface(setter.owner.type, setter.toAsmMethod());
        } else {
            adapter.invokeVirtual(setter.owner.type, setter.toAsmMethod());
        }

        adapter.writePop(setter.rtn.sort.size);
    }
}
