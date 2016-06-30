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

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.Method;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

class MethodAnalyzer extends MethodVisitor {
    private final String owner;
    private final String methodName;
    /** any violations we found for this method */
    final List<String> violations = new ArrayList<>();
    /** any lambda invocations we found for this method (we'll apply suppression to them, if needed) */
    final List<Method> lambdas = new ArrayList<>();
    /** true if we found a suppression annotation for this method */
    boolean suppressed;
    
    MethodAnalyzer(String owner, int access, String name, String desc, String signature, String[] exceptions) {
        super(Opcodes.ASM5, new MethodNode(access, name, desc, signature, exceptions));
        this.owner = owner;
        this.methodName = name;
    }
    
    @Override
    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
        if (desc.contains("SwallowsExceptions")) {
            suppressed = true;
        }
        return super.visitAnnotation(desc, visible);
    }
    
    @Override
    public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... bsmArgs) {
        super.visitInvokeDynamicInsn(name, desc, bsm, bsmArgs);
    }

    @Override
    public void visitEnd() {
        MethodNode node = (MethodNode)mv;
        AbstractInsnNode insns[] = node.instructions.toArray(); // all instructions for the method
        Set<Integer> handlers = new TreeSet<>(); // entry points of exception handlers found

        Analyzer<BasicValue> a = new Analyzer<BasicValue>(new ThrowableInterpreter()) {
            @Override
            protected Frame<BasicValue> newFrame(Frame<? extends BasicValue> src) {
                return new Node<BasicValue>(src);
            }
            
            @Override
            protected Frame<BasicValue> newFrame(int nLocals, int nStack) {
                return new Node<BasicValue>(nLocals, nStack);
            }
            
            @Override
            protected void newControlFlowEdge(int insn, int next) {
                Node<BasicValue> s = (Node<BasicValue>) getFrames()[insn];
                s.edges.add(next);
            }
            
            @Override
            protected boolean newControlFlowExceptionEdge(int insn, TryCatchBlockNode next) {
                int nextInsn = node.instructions.indexOf(next.handler);
                newControlFlowEdge(insn, nextInsn);
                // null type: e.g. finally block
                if (next.type != null) {
                    handlers.add(nextInsn);
                }
                return true;
            }
        };
        try {
            Frame<BasicValue> frames[] = a.analyze(owner, node);
            List<Node<BasicValue>> nodes = new ArrayList<>();
            for (Frame<BasicValue> frame : frames) {
                nodes.add((Node<BasicValue>)frame);
            }
            // check the destination of every exception edge
            for (int handler : handlers) {
                String violation = analyze(insns, nodes, handler, new BitSet());
                if (violation != null) {
                    String brokenCatchBlock = newViolation("Broken catch block", getLineNumberForwards(insns, handler));
                    violations.add(brokenCatchBlock + System.lineSeparator() + "\t" + violation);
                }
            }
        } catch (AnalyzerException e) {
            throw new RuntimeException(e);
        }
    }
    
    /**
     * Analyzes a basic block starting at insn, recursing for all paths in the CFG, until it finds an exit or
     * throw. Returns null if there were no problems, or a string error.
     */
    private String analyze(AbstractInsnNode insns[], List<Node<BasicValue>> nodes, int insn, BitSet visited) {
        Node<BasicValue> node = nodes.get(insn);
        while (true) {
            if (visited.get(insn)) {
                return null;
            }
            if (insns[insn] instanceof MethodInsnNode) {
                MethodInsnNode methodNode = (MethodInsnNode) insns[insn];
                if ((methodNode.name.equals("addSuppressed") || methodNode.name.equals("initCause")) &&
                        ThrowableInterpreter.isThrowable(Type.getObjectType(methodNode.owner), getClass().getClassLoader())) {
                    // its a suppressor, check our original is live on stack
                    for (int i = 0; i < node.getStackSize(); i++) {
                        if (node.getStack(i) == ThrowableInterpreter.ORIGINAL_THROWABLE) {
                            return null;
                        }
                    }
                }
                if ((methodNode.name.equals("addSuppressed") && methodNode.owner.equals("org/apache/lucene/util/IOUtils"))) {
                    // its a suppressor, check our original is live on stack
                    for (int i = 0; i < node.getStackSize(); i++) {
                        if (node.getStack(i) == ThrowableInterpreter.ORIGINAL_THROWABLE) {
                            return null;
                        }
                    }
                }
            }
            if (isThrowInsn(insns[insn])) {
                // its a throw, or equivalent. check that our original is live on stack
                for (int i = 0; i < node.getStackSize(); i++) {
                    if (node.getStack(i) == ThrowableInterpreter.ORIGINAL_THROWABLE) {
                        return null;
                    }
                }
                return newViolation("Throws a different exception, but loses the original", getLineNumberBackwards(insns, insn));
            }
            if (node.edges.isEmpty()) {
                return newViolation("Escapes without throwing anything", getLineNumberBackwards(insns, insn));
            }
            visited.set(insn);
            if (node.edges.size() == 1) {
                insn = node.edges.iterator().next();
                node = nodes.get(insn);
            } else {
                break;
            }
        }
        
        // recurse: multiple edges
        for (int edge : node.edges) {
            String violation = analyze(insns, nodes, edge, visited);
            if (violation != null) {
                return violation;
            }
        }
        
        // we checked all paths, no problems.
        return null;
    }
    
    /** true if this is a throw, or an equivalent (e.g. rethrow) */
    private static boolean isThrowInsn(AbstractInsnNode insn) {
        if (insn.getOpcode() == Opcodes.ATHROW) {
            return true;
        }
        if (insn instanceof MethodInsnNode) {
            MethodInsnNode methodNode = (MethodInsnNode) insn;
            if (methodNode.owner.equals("org/apache/lucene/codecs/CodecUtil") && methodNode.name.equals("checkFooter")) {
                return true;
            }
            if (methodNode.owner.equals("org/apache/lucene/util/IOUtils") && methodNode.name.equals("reThrow")) {
                return true;
            }
        }
        return false;
    }
    
    private static int getLineNumberForwards(AbstractInsnNode insns[], int insn) {
        // walk forwards in the line number table
        int line = -1;
        for (int i = insn; i < insns.length; i++) {
            AbstractInsnNode next = insns[i];
            if (next instanceof LineNumberNode) {
                line = ((LineNumberNode)next).line;
                break;
            }
        }
        return line;
    }
    
    private static int getLineNumberBackwards(AbstractInsnNode insns[], int insn) {
        // walk backwards in the line number table
        int line = -1;
        for (int i = insn; i >= 0; i--) {
            AbstractInsnNode previous = insns[i];
            if (previous instanceof LineNumberNode) {
                line = ((LineNumberNode)previous).line;
                break;
            }
        }
        return line;
    }
    
    /** Forms a string message for a new violation */
    private String newViolation(String message, int line) {
        String clazzName = owner.replace('/', '.');
        if (line >= 0) {
            return message + " at " + clazzName + "." + methodName + ":" + line;
        } else {
            return message + " at " + clazzName + "." + methodName + ":(Unknown Source)";
        }
    }
}
