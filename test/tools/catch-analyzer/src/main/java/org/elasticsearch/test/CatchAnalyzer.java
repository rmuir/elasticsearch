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

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
 
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;
 
/**
 * Analyzes methods for broken catch blocks. These are catch blocks that somehow
 * drop the original exception (via any possible codepath). It is ok to wrap the exception
 * with another one, e.g.:
 * <ul>
 *   <li>new OtherException(..., original, ...)
 *   <li>otherException.initCause(original)
 *   <li>otherException.addSuppressed(original)
 * </ul>
 */
public class CatchAnalyzer extends MethodVisitor {
  private final String owner;
  private final String methodName;
  private final AtomicLong violationCount;
 
  CatchAnalyzer(String owner, int access, String name, String desc, String signature, String[] exceptions, AtomicLong violationCount) {
    super(Opcodes.ASM5, new MethodNode(access, name, desc, signature, exceptions));
    this.owner = owner;
    this.methodName = name;
    this.violationCount = violationCount;
  }
 
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
          String brokenCatchBlock = newViolation("Broken catch block", insns, handler);
          System.out.println(brokenCatchBlock);
          System.out.println("  " + violation);
          violationCount.incrementAndGet();
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
        return newViolation("Throws a different exception, but loses the original", insns, insn);
      }
      if (node.edges.isEmpty()) {
        return newViolation("Escapes without throwing anything", insns, insn);
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
 
  /** Forms a string message for a new violation */
  private String newViolation(String message, AbstractInsnNode insns[], int insn) {
    // walk backwards in the line number table
    int line = -1;
    for (int i = insn; i >= 0; i--) {
      AbstractInsnNode previous = insns[i];
      if (previous instanceof LineNumberNode) {
        line = ((LineNumberNode)previous).line;
        break;
      }
    }
    String clazzName = owner.replace('/', '.');
    if (line >= 0) {
      return message + " at " + clazzName + "." + methodName + ":" + line;
    } else {
      return message + " at " + clazzName + "." + methodName + ":(Unknown Source)";
    }
  }
 
  /**
   * Tracks "Original throwable". You can assign it to variables and stuff, but otherwise
   * operations on it convert it into a regular REFERENCE_VALUE. However, new OtherException(ORIGINAL_THROWABLE)
   * is treated still as ORIGINAL_THROWABLE, as you preserved it.
   */
  static class ThrowableInterpreter extends BasicInterpreter {
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
 
  /** Exception thrown when some exceptions are swallowed */
  public static class SwallowedException extends RuntimeException {
    public SwallowedException(String message) {
      super(message);
    }
  }
 
  /** Main method, takes directories as parameter. These must also be on classpath!!!! */
  public static void main(String args[]) throws Exception {
    AtomicLong scannedCount = new AtomicLong();
    AtomicLong violationCount = new AtomicLong();
    long startTime = System.currentTimeMillis();
    for (String arg : args) {
      Path dir = Paths.get(arg);
      Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          if (file.toString().endsWith(".class")) {
            byte bytes[] = Files.readAllBytes(file);
            ClassReader reader = new ClassReader(bytes);
            // entirely synthetic class, e.g. enum switch table, which always masks NoSuchFieldError!!!!!
            if ((reader.getAccess() & Opcodes.ACC_SYNTHETIC) != 0) {
              return FileVisitResult.CONTINUE;
            }
            reader.accept(new ClassVisitor(Opcodes.ASM5, null) {
              @Override
              public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                // don't scan synthetic methods (have not found any issues with them, but it would be unfair)
                if ((access & Opcodes.ACC_SYNTHETIC) != 0) {
                  return super.visitMethod(access, name, desc, signature, exceptions);
                }
                // TODO: allow scanning ctors (we just have to handle the super call better)
                if ("<init>".equals(name)) {
                  return super.visitMethod(access, name, desc, signature, exceptions);
                }
                // TODO: fix bugs in ASM, that cause problems when doing dataflow analysis of the following methods:
                // indexwriter.shutdown
                if ("shutdown".equals(name) && "org/apache/lucene/index/IndexWriter".equals(reader.getClassName())) {
                  return super.visitMethod(access, name, desc, signature, exceptions);
                }
                // fst.arc readtargetarc
                if ("readLastTargetArc".equals(name) && "org/apache/lucene/util/fst/FST".equals(reader.getClassName())) {
                  return super.visitMethod(access, name, desc, signature, exceptions);
                }
                // fst.bytesreader 
                if ("seekToNextNode".equals(name) && "org/apache/lucene/util/fst/FST".equals(reader.getClassName())) {
                  return super.visitMethod(access, name, desc, signature, exceptions);
                }
                return new CatchAnalyzer(reader.getClassName(), access, name, desc, signature, exceptions, violationCount);
              }
            }, 0);
            scannedCount.incrementAndGet();
          }
          return FileVisitResult.CONTINUE;
        }
      });
    }
    long endTime = System.currentTimeMillis();
    System.out.println("Scanned " + scannedCount.get() + " classes in " + (endTime - startTime) + " ms");
    if (violationCount.get() > 0) {
      throw new SwallowedException(violationCount.get() + " violations were found, see log for more details");
    }
  }
}
