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

package org.elasticsearch.painless;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MethodHandles.Lookup;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DefUnary {
    
    @SuppressWarnings("unused")
    private static int bwnot(int v) {
        return ~v;
    }
    
    @SuppressWarnings("unused")
    private static long bwnot(long v) {
        return ~v;
    }
    
    @SuppressWarnings("unused")
    private static float bwnot(float v) {
        throw new ClassCastException("Cannot apply not [~] to type [float]");
    }
    
    @SuppressWarnings("unused")
    private static double bwnot(double v) {
        throw new ClassCastException("Cannot apply not [~] to type [double]");
    }
    
    @SuppressWarnings("unused")
    private static int neg(int v) {
        return -v;
    }
    
    @SuppressWarnings("unused")
    private static long neg(long v) {
        return -v;
    }
    
    @SuppressWarnings("unused")
    private static float neg(float v) {
        return -v;
    }
    
    @SuppressWarnings("unused")
    private static double neg(double v) {
        return -v;
    }
    
    private static Class<?> unbox(Class<?> clazz) {
        if (clazz == Byte.class) { 
            return byte.class; 
        } else if (clazz == Short.class) { 
            return short.class; 
        } else if (clazz == Character.class) {
            return char.class;
        } else if (clazz == Integer.class) {
            return int.class;
        } else if (clazz == Long.class) {
            return long.class;
        } else if (clazz == Float.class) {
            return float.class;
        } else if (clazz == Double.class) {
            return double.class;
        } else {
            return clazz;
        }
    }
    
    private static Class<?> widen(Class<?> clazz) {
        if (clazz == byte.class || clazz == short.class || clazz == char.class || clazz == int.class) { 
            return int.class; 
        } else { 
            return clazz; 
        } 
    }
    
    private static final Lookup PRIV_LOOKUP = MethodHandles.lookup();

    private static final Map<Class<?>,Map<String,MethodHandle>> TYPE_MH_MAPPING = Collections.unmodifiableMap(
        Stream.of(int.class, long.class, float.class, double.class)
            .collect(Collectors.toMap(Function.identity(), type -> {
                try {
                    Map<String,MethodHandle> map = new HashMap<>();
                    map.put("bwnot", PRIV_LOOKUP.findStatic(PRIV_LOOKUP.lookupClass(), "bwnot", MethodType.methodType(type, type)));
                    map.put("neg", PRIV_LOOKUP.findStatic(PRIV_LOOKUP.lookupClass(), "neg", MethodType.methodType(type, type)));
                    return map;
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            }))
    );
    
    public static MethodHandle lookupOperator(Class<?> receiverClass, String name) {
        return TYPE_MH_MAPPING.get(widen(unbox(receiverClass))).get(name);
    }
}
