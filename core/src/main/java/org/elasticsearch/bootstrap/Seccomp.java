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

package org.elasticsearch.bootstrap;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import org.apache.lucene.util.Constants;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 
 * Installs a limited form of Linux secure computing mode (filter mode).
 * This filters system calls to block process execution.
 * <p>
 * This is only supported on the amd64 and aarch64 architectures, on Linux kernels 3.5 or above, and requires
 * {@code CONFIG_SECCOMP} and {@code CONFIG_SECCOMP_FILTER} compiled into the kernel.
 * <p>
 * Filters are installed using either {@code seccomp(2)} (3.17+) or {@code prctl(2)} (3.5+). {@code seccomp(2)}
 * is preferred, as it allows filters to be applied to any existing threads in the process, and one motivation
 * here is to protect against bugs in the JVM. Otherwise, code will fall back to the {@code prctl(2)} method 
 * which will at least protect elasticsearch application threads.
 * <p>
 * The filters will return {@code EACCES} (Access Denied) for the following system calls:
 * <ul>
 *   <li>{@code execve}</li>
 *   <li>{@code fork}</li>
 *   <li>{@code vfork}</li>
 * </ul>
 * <p>
 * This is not intended as a sandbox. It is another level of security, mostly intended to annoy
 * security researchers and make their lives more difficult in achieving "remote execution" exploits.
 * @see <a href="http://www.kernel.org/doc/Documentation/prctl/seccomp_filter.txt">
 *      http://www.kernel.org/doc/Documentation/prctl/seccomp_filter.txt</a>
 */
// not an example of how to write code!!!
final class Seccomp {
    private static final ESLogger logger = Loggers.getLogger(Seccomp.class);

    /** we use an explicit interface for native methods, for varargs support */
    static interface LinuxLibrary extends Library {
        /** 
         * maps to prctl(2) 
         */
        int prctl(int option, long arg2, long arg3, long arg4, long arg5);
        /** 
         * used to call seccomp(2), its too new... 
         * this is the only way, DONT use it on some other architecture unless you know wtf you are doing 
         */
        long syscall(long number, Object... args);
    };

    // null if something goes wrong.
    static final LinuxLibrary libc;

    static {
        LinuxLibrary lib = null;
        try {
            lib = (LinuxLibrary) Native.loadLibrary("c", LinuxLibrary.class);
        } catch (UnsatisfiedLinkError e) {
            logger.warn("unable to link C library. native methods (seccomp) will be disabled.", e);
        }
        libc = lib;
    }
    
    /** the preferred method is seccomp(2), since we can apply to all threads of the process */
    static final int SECCOMP_SET_MODE_FILTER   =   1;   // since Linux 3.17
    static final int SECCOMP_FILTER_FLAG_TSYNC =   1;   // since Linux 3.17

    /** otherwise, we can use prctl(2), which will at least protect ES application threads */
    static final int PR_GET_NO_NEW_PRIVS       =  39;   // since Linux 3.5
    static final int PR_SET_NO_NEW_PRIVS       =  38;   // since Linux 3.5
    static final int PR_GET_SECCOMP            =  21;   // since Linux 2.6.23
    static final int PR_SET_SECCOMP            =  22;   // since Linux 2.6.23
    static final int SECCOMP_MODE_FILTER       =   2;   // since Linux Linux 3.5
    
    /** corresponds to struct sock_filter */
    static final class SockFilter {
        short code; // insn
        byte jt;    // number of insn to jump (skip) if true
        byte jf;    // number of insn to jump (skip) if false
        int k;      // additional data

        SockFilter(short code, byte jt, byte jf, int k) {
            this.code = code;
            this.jt = jt;
            this.jf = jf;
            this.k = k;
        }
    }
    
    /** corresponds to struct sock_fprog */
    public static final class SockFProg extends Structure implements Structure.ByReference {
        public short   len;           // number of filters
        public Pointer filter;        // filters
        
        public SockFProg(SockFilter filters[]) {
            len = (short) filters.length;
            // serialize struct sock_filter * explicitly, its less confusing than the JNA magic we would need
            Memory filter = new Memory(len * 8);
            ByteBuffer bbuf = filter.getByteBuffer(0, len * 8);
            bbuf.order(ByteOrder.nativeOrder()); // native endian order
            for (SockFilter f : filters) {
                bbuf.putShort(f.code);
                bbuf.put(f.jt);
                bbuf.put(f.jf);
                bbuf.putInt(f.k);
            }
            this.filter = filter;
        }
        
        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList(new String[] { "len", "filter" });
        }
    }
    
    // BPF "macros" and constants
    static final int BPF_LD  = 0x00;
    static final int BPF_W   = 0x00;
    static final int BPF_ABS = 0x20;
    static final int BPF_JMP = 0x05;
    static final int BPF_JEQ = 0x10;
    static final int BPF_RET = 0x06;
    static final int BPF_K   = 0x00;
    
    static SockFilter BPF_STMT(int code, int k) {
        return new SockFilter((short) code, (byte) 0, (byte) 0, k);
    }
    
    static SockFilter BPF_JUMP(int code, int k, int jt, int jf) {
        return new SockFilter((short) code, (byte) jt, (byte) jf, k);
    }

    // seccomp constants used by filter
    static final int SECCOMP_RET_ERRNO = 0x00050000;
    static final int SECCOMP_RET_DATA  = 0x0000FFFF;
    static final int SECCOMP_RET_ALLOW = 0x7FFF0000;

    // some errno constants for error checking/handling
    static final int EACCES = 0x0D;
    static final int EFAULT = 0x0E;
    static final int EINVAL = 0x16;
    static final int ENOSYS = 0x26;
    
    /** architecture-dependent data */
    static class Arch {
        final int SCMP_arch;     // SCMP_ARCH_xxx value
        final ByteOrder endian;  // expected endianness
        final int offsetof_nr;   // offsetof(struct seccomp_data, nr)
        final int offsetof_arch; // offsetof(struct seccomp_data, arch)
        final int SYS_fork;      // fork(2) syscall nr
        final int SYS_vfork;     // vfork(2) syscall nr
        final int SYS_execve;    // execve(2) syscall nr
        final int SYS_execveat;  // execveat(2) syscall nr
        final int SYS_seccomp;   // seccomp(2) syscall nr

        Arch(int SCMP_arch, ByteOrder endian, int offsetof_nr, int offsetof_arch, int SYS_fork, int SYS_vfork, int SYS_execve, int SYS_execveat, int SYS_seccomp) {
            this.SCMP_arch = SCMP_arch;
            this.endian = endian;
            this.offsetof_nr = offsetof_nr;
            this.offsetof_arch = offsetof_arch;
            this.SYS_fork = SYS_fork;
            this.SYS_vfork = SYS_vfork;
            this.SYS_execve = SYS_execve;
            this.SYS_execveat = SYS_execveat;
            this.SYS_seccomp = SYS_seccomp;
        }
    }
    
    /** supported architectures map, populate this (and run all tests) to add a new one */
    static final Map<String,Arch> SUPPORTED_ARCHITECTURES;
    static {
        Map<String,Arch> m = new HashMap<>();
        m.put("amd64",   new Arch(0xC000003E, ByteOrder.LITTLE_ENDIAN, 0, 4,   57,   58,  59, 322, 317));
        m.put("aarch64", new Arch(0xC00000B7, ByteOrder.LITTLE_ENDIAN, 0, 4, 1079, 1071, 221, 281, 277));
        SUPPORTED_ARCHITECTURES = Collections.unmodifiableMap(m);
    }

    /** try to install our filters */
    static void installFilter() {
        // first be defensive: we can give nice errors this way, at the very least.
        // also, some of these security features get backported to old versions, checking kernel version here is a big no-no! 
        boolean supported = Constants.LINUX;
        if (supported == false) {
            throw new IllegalStateException("bug: should not be trying to initialize seccomp for an unsupported OS");
        }

        final Arch arch = SUPPORTED_ARCHITECTURES.get(Constants.OS_ARCH);
        if (arch == null) {
            throw new UnsupportedOperationException("seccomp unavailable: architecture '" + Constants.OS_ARCH + "' is not supported");
        }

        // safety check: jdk does not indicate this in os.arch (https://bugs.openjdk.java.net/browse/JDK-8073139)
        // if we really need to support big/little variants of an arch before they fix this bug, we can add to a composite key.
        if (arch.endian != ByteOrder.nativeOrder()) {
            throw new UnsupportedOperationException("seccomp unavailable: architecture endianness does not match expected '" + arch.endian + "'");
        }

        // we couldn't link methods, could be some really ancient kernel (e.g. < 2.1.57) or some bug
        if (libc == null) {
            throw new UnsupportedOperationException("seccomp unavailable: could not link methods. requires kernel 3.5+ with CONFIG_SECCOMP and CONFIG_SECCOMP_FILTER compiled in");
        }

        // check for kernel version
        if (libc.prctl(PR_GET_NO_NEW_PRIVS, 0, 0, 0, 0) < 0) {
            int errno = Native.getLastError();
            switch (errno) {
                case ENOSYS: throw new UnsupportedOperationException("seccomp unavailable: requires kernel 3.5+ with CONFIG_SECCOMP and CONFIG_SECCOMP_FILTER compiled in");
                default: throw new UnsupportedOperationException("prctl(PR_GET_NO_NEW_PRIVS): " + JNACLibrary.strerror(errno));
            }
        }
        // check for SECCOMP
        if (libc.prctl(PR_GET_SECCOMP, 0, 0, 0, 0) < 0) {
            int errno = Native.getLastError();
            switch (errno) {
                case EINVAL: throw new UnsupportedOperationException("seccomp unavailable: CONFIG_SECCOMP not compiled into kernel, CONFIG_SECCOMP and CONFIG_SECCOMP_FILTER are needed");
                default: throw new UnsupportedOperationException("prctl(PR_GET_SECCOMP): " + JNACLibrary.strerror(errno));
            }
        }
        // check for SECCOMP_MODE_FILTER
        if (libc.prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, 0, 0, 0) < 0) {
            int errno = Native.getLastError();
            switch (errno) {
                case EFAULT: break; // available
                case EINVAL: throw new UnsupportedOperationException("seccomp unavailable: CONFIG_SECCOMP_FILTER not compiled into kernel, CONFIG_SECCOMP and CONFIG_SECCOMP_FILTER are needed");
                default: throw new UnsupportedOperationException("prctl(PR_SET_SECCOMP): " + JNACLibrary.strerror(errno));
            }
        }

        // ok, now set PR_SET_NO_NEW_PRIVS, needed to be able to set a seccomp filter as ordinary user
        if (libc.prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) < 0) {
            throw new UnsupportedOperationException("prctl(PR_SET_NO_NEW_PRIVS): " + JNACLibrary.strerror(Native.getLastError()));
        }
        
        // BPF installed to check arch, then syscall. See https://www.kernel.org/doc/Documentation/prctl/seccomp_filter.txt for details.
        SockFilter insns[] = {
          /* 1 */ BPF_STMT(BPF_LD  + BPF_W   + BPF_ABS, arch.offsetof_arch),                     //
          /* 2 */ BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K,   arch.SCMP_arch,   0, 6),                 // if (arch != expected) goto fail;
          /* 3 */ BPF_STMT(BPF_LD  + BPF_W   + BPF_ABS, arch.offsetof_nr),                       //
          /* 4 */ BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K,   arch.SYS_fork,     4, 0),                // if (syscall == fork) goto fail;
          /* 5 */ BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K,   arch.SYS_vfork,    3, 0),                // if (syscall == vfork) goto fail;
          /* 6 */ BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K,   arch.SYS_execve,   2, 0),                // if (syscall == execve) goto fail;
          /* 7 */ BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K,   arch.SYS_execveat, 1, 0),                // if (syscall == execveat) goto pass;
          /* 8 */ BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_ALLOW),                                  // pass: return OK;
          /* 9 */ BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_ERRNO | (EACCES & SECCOMP_RET_DATA)),    // fail: return EACCES;
        };
        
        // seccomp takes a long, so we pass it one explicitly to keep the JNA simple
        SockFProg prog = new SockFProg(insns);
        prog.write();
        long pointer = Pointer.nativeValue(prog.getPointer());

        // install filter, if this works, after this there is no going back!
        // first try it with seccomp(SECCOMP_SET_MODE_FILTER), falling back to prctl()
        if (libc.syscall(arch.SYS_seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_TSYNC, pointer) != 0) {
            int errno1 = Native.getLastError();
            if (logger.isDebugEnabled()) {
                logger.debug("seccomp(SECCOMP_SET_MODE_FILTER): " + JNACLibrary.strerror(errno1) + ", falling back to prctl(PR_SET_SECCOMP)...");
            }
            if (libc.prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, pointer, 0, 0) < 0) {
                int errno2 = Native.getLastError();
                throw new UnsupportedOperationException("seccomp(SECCOMP_SET_MODE_FILTER): " + JNACLibrary.strerror(errno1) + 
                                                        ", prctl(PR_SET_SECCOMP): " + JNACLibrary.strerror(errno2));
            }
        }
        
        // now check that the filter was really installed, we should be in filter mode.
        if (libc.prctl(PR_GET_SECCOMP, 0, 0, 0, 0) != 2) {
            throw new UnsupportedOperationException("seccomp filter installation did not really succeed. seccomp(PR_GET_SECCOMP): " + JNACLibrary.strerror(Native.getLastError()));
        }

        logger.debug("seccomp filter installation successful");
    }
}
