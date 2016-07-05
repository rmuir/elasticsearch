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

package org.elasticsearch.test;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;

import java.util.List;

/**
 * Tracks "Original throwable". You can assign it to variables and stuff, but otherwise
 * operations on it convert it into a regular REFERENCE_VALUE. However, new OtherException(ORIGINAL_THROWABLE)
 * is treated still as ORIGINAL_THROWABLE, as you preserved it.
 */
class ThrowableInterpreter extends BasicInterpreter {
    static final BasicValue ORIGINAL_THROWABLE = new BasicValue(Type.getType(Throwable.class));
    
    ThrowableInterpreter() {
        super(Opcodes.ASM5);
    }
    
    @Override
    public BasicValue newValue(Type type) {
        if (isThrowable(type, getClass().getClassLoader())) {
            return ORIGINAL_THROWABLE;
        }
        return super.newValue(type);
    }
    
    @Override
    public BasicValue newOperation(AbstractInsnNode arg0) throws AnalyzerException {
        BasicValue v = super.newOperation(arg0);
        if (v == ORIGINAL_THROWABLE) {
            return BasicValue.REFERENCE_VALUE;
        }
        return v;
    }
    
    @Override
    public BasicValue naryOperation(AbstractInsnNode insn, List<? extends BasicValue> values) throws AnalyzerException {
        BasicValue v = super.naryOperation(insn, values);
        if (insn.getOpcode() == Opcodes.INVOKESPECIAL) {
            for (BasicValue arg : values) {
                if (arg == ORIGINAL_THROWABLE) {
                    return ORIGINAL_THROWABLE;
                }
            }
            return BasicValue.REFERENCE_VALUE;
        }
        return v;
    }
    
    /** check if the type is a throwable */
    static boolean isThrowable(Type type, ClassLoader loader) {
        if (type != null && type.getSort() == Type.OBJECT && "null".equals(type.getClassName()) == false) {
            try {
                Class<?> clazz = Class.forName(type.getClassName(), false, loader);
                return Throwable.class.isAssignableFrom(clazz);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
        return false;
    }
}
