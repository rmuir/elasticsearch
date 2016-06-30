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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Node in the flow graph. contains set of outgoing edges for the next instructions possible */
class Node<V extends BasicValue> extends Frame<V> {
    Set<Integer> edges = new HashSet<>();
    
    public Node(int nLocals, int nStack) {
        super(nLocals, nStack);
    }
    
    public Node(Frame<? extends V> src) {
        super(src);
    }
    
    @Override
    public void execute(AbstractInsnNode insn, Interpreter<V> interpreter) throws AnalyzerException {
        // special handling for throwable constructor. if you do new Exception(originalException),
        // the stack value is replaced with originalException, so it survives.
        if (insn.getOpcode() == Opcodes.INVOKESPECIAL) {
            MethodInsnNode node = (MethodInsnNode) insn;
            Type ownerType = Type.getObjectType(node.owner);
            if ("<init>".equals(node.name) && ThrowableInterpreter.isThrowable(ownerType, getClass().getClassLoader())) {
                List<V> values = new ArrayList<V>();
                String desc = ((MethodInsnNode) insn).desc;
                for (int i = Type.getArgumentTypes(desc).length; i > 0; --i) {
                    values.add(0, pop());
                }
                values.add(0, pop());
                // replace stack value with the result (which will be original exc, if it was one of the parameters)
                pop();
                push(interpreter.naryOperation(insn, values));
                return;
            }
        }
        super.execute(insn, interpreter);
    }
    
    // just for debugging
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("edges=" + edges + "\n");
        sb.append("locals=\n");
        for (int i = 0; i < getLocals(); i++) {
            sb.append("\t");
            sb.append(i + "=" + getLocal(i).getType() + "\n");
        }
        sb.append("stack=\n");
        for (int i = 0; i < getStackSize(); i++) {
            sb.append("\t");
            sb.append(i + "=" + getStack(i).getType() + "\n");
        }
        return sb.toString();
    }
}
