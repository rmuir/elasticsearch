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

import org.elasticsearch.painless.Definition.Method;
import org.elasticsearch.painless.Definition.RuntimeClass;

import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaConversionException;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Support for dynamic type (def).
 * <p>
 * Dynamic types can invoke methods, load/store fields, and be passed as parameters to operators without
 * compile-time type information.
 * <p>
 * Dynamic methods, loads, stores, and array/list/map load/stores involve locating the appropriate field
 * or method depending on the receiver's class. For these, we emit an {@code invokedynamic} instruction that,
 * for each new type encountered will query a corresponding {@code lookupXXX} method to retrieve the appropriate
 * method. In most cases, the {@code lookupXXX} methods here will only be called once for a given call site, because
 * caching ({@link DefBootstrap}) generally works: usually all objects at any call site will be consistently
 * the same type (or just a few types).  In extreme cases, if there is type explosion, they may be called every
 * single time, but simplicity is still more valuable than performance in this code.
 */
public final class Def {

    // TODO: Once Java has a factory for those in java.lang.invoke.MethodHandles, use it:

    /** Helper class for isolating MethodHandles and methods to get the length of arrays
     * (to emulate a "arraystore" bytecode using MethodHandles).
     * This should really be a method in {@link MethodHandles} class!
     */
    @SuppressWarnings("unused") // getArrayLength() methods are are actually used, javac just does not know :)
    private static final class ArrayLengthHelper {
        private static final Lookup PRIV_LOOKUP = MethodHandles.lookup();

        private static final Map<Class<?>,MethodHandle> ARRAY_TYPE_MH_MAPPING = Collections.unmodifiableMap(
            Stream.of(boolean[].class, byte[].class, short[].class, int[].class, long[].class,
                char[].class, float[].class, double[].class, Object[].class)
                .collect(Collectors.toMap(Function.identity(), type -> {
                    try {
                        return PRIV_LOOKUP.findStatic(PRIV_LOOKUP.lookupClass(), "getArrayLength", MethodType.methodType(int.class, type));
                    } catch (ReflectiveOperationException e) {
                        throw new AssertionError(e);
                    }
                }))
        );

        private static final MethodHandle OBJECT_ARRAY_MH = ARRAY_TYPE_MH_MAPPING.get(Object[].class);

        static int getArrayLength(final boolean[] array) { return array.length; }
        static int getArrayLength(final byte[] array)    { return array.length; }
        static int getArrayLength(final short[] array)   { return array.length; }
        static int getArrayLength(final int[] array)     { return array.length; }
        static int getArrayLength(final long[] array)    { return array.length; }
        static int getArrayLength(final char[] array)    { return array.length; }
        static int getArrayLength(final float[] array)   { return array.length; }
        static int getArrayLength(final double[] array)  { return array.length; }
        static int getArrayLength(final Object[] array)  { return array.length; }

        static MethodHandle arrayLengthGetter(Class<?> arrayType) {
            if (!arrayType.isArray()) {
                throw new IllegalArgumentException("type must be an array");
            }
            return (ARRAY_TYPE_MH_MAPPING.containsKey(arrayType)) ?
                ARRAY_TYPE_MH_MAPPING.get(arrayType) :
                OBJECT_ARRAY_MH.asType(OBJECT_ARRAY_MH.type().changeParameterType(0, arrayType));
        }

        private ArrayLengthHelper() {}
    }

    /** pointer to Map.get(Object) */
    private static final MethodHandle MAP_GET;
    /** pointer to Map.put(Object,Object) */
    private static final MethodHandle MAP_PUT;
    /** pointer to List.get(int) */
    private static final MethodHandle LIST_GET;
    /** pointer to List.set(int,Object) */
    private static final MethodHandle LIST_SET;

    static {
        final Lookup lookup = MethodHandles.publicLookup();

        try {
            MAP_GET  = lookup.findVirtual(Map.class , "get", MethodType.methodType(Object.class, Object.class));
            MAP_PUT  = lookup.findVirtual(Map.class , "put", MethodType.methodType(Object.class, Object.class, Object.class));
            LIST_GET = lookup.findVirtual(List.class, "get", MethodType.methodType(Object.class, int.class));
            LIST_SET = lookup.findVirtual(List.class, "set", MethodType.methodType(Object.class, int.class, Object.class));
        } catch (final ReflectiveOperationException roe) {
            throw new AssertionError(roe);
        }
    }

    /** Returns an array length getter MethodHandle for the given array type */
    static MethodHandle arrayLengthGetter(Class<?> arrayType) {
        return ArrayLengthHelper.arrayLengthGetter(arrayType);
    }

    /**
     * Looks up method entry for a dynamic method call.
     * <p>
     * A dynamic method call for variable {@code x} of type {@code def} looks like:
     * {@code x.method(args...)}
     * <p>
     * This method traverses {@code recieverClass}'s class hierarchy (including interfaces)
     * until it finds a matching whitelisted method. If one is not found, it throws an exception.
     * Otherwise it returns the matching method.
     * <p>
     * @param receiverClass Class of the object to invoke the method on.
     * @param name Name of the method.
     * @param arity arity of method
     * @return matching method to invoke. never returns null.
     * @throws IllegalArgumentException if no matching whitelisted method was found.
     */
    static Method lookupMethodInternal(Class<?> receiverClass, String name, int arity) {
        Definition.MethodKey key = new Definition.MethodKey(name, arity);
        // check whitelist for matching method
        for (Class<?> clazz = receiverClass; clazz != null; clazz = clazz.getSuperclass()) {
            RuntimeClass struct = Definition.getRuntimeClass(clazz);

            if (struct != null) {
                Method method = struct.methods.get(key);
                if (method != null) {
                    return method;
                }
            }

            for (Class<?> iface : clazz.getInterfaces()) {
                struct = Definition.getRuntimeClass(iface);

                if (struct != null) {
                    Method method = struct.methods.get(key);
                    if (method != null) {
                        return method;
                    }
                }
            }
        }
        
        throw new IllegalArgumentException("Unable to find dynamic method [" + name + "] with [" + arity + "] arguments " +
                                           "for class [" + receiverClass.getCanonicalName() + "].");
    }

    /**
     * Looks up handle for a dynamic method call, with lambda replacement
     * <p>
     * A dynamic method call for variable {@code x} of type {@code def} looks like:
     * {@code x.method(args...)}
     * <p>
     * This method traverses {@code recieverClass}'s class hierarchy (including interfaces)
     * until it finds a matching whitelisted method. If one is not found, it throws an exception.
     * Otherwise it returns a handle to the matching method.
     * <p>
     * @param receiverClass Class of the object to invoke the method on.
     * @param name Name of the method.
     * @param args args passed to callsite
     * @param recipe bitset marking functional parameters
     * @return pointer to matching method to invoke. never returns null.
     * @throws IllegalArgumentException if no matching whitelisted method was found.
     */
     static MethodHandle lookupMethod(Class<?> receiverClass, String name, Object args[], long recipe) {
         Method method = lookupMethodInternal(receiverClass, name, args.length - 1);
         MethodHandle handle = method.handle;
         MethodHandle filters[] = new MethodHandle[args.length];

         if (recipe != 0) {
             for (int i = 0; i < args.length; i++) {
                 if ((recipe & (1L << (i - 1))) != 0) {
                     filters[i] = lookupReference(method.arguments.get(i - 1).clazz, (String) args[i]);
                 }
             }
         }
         handle = MethodHandles.filterArguments(handle, 0, filters);
         
         return handle;
     }
     
     private static MethodHandle lookupReference(Class<?> clazz, String signature) {
         int separator = signature.indexOf('.');
         FunctionRef ref = new FunctionRef(clazz, signature.substring(0, separator), signature.substring(separator+1));
         MethodHandles.Lookup lookup = MethodHandles.lookup(); // XXX: no lookuping needed
         final CallSite callSite;
         // XXX: clean all this up to use handles, deal with ASM in EFunctionRef
         MethodType invokedType = MethodType.fromMethodDescriptorString(ref.invokedType.getDescriptor(), Def.class.getClassLoader());
         MethodType samMethodType = MethodType.fromMethodDescriptorString(ref.samMethodType.getDescriptor(), Def.class.getClassLoader());
         MethodType interfaceType = MethodType.fromMethodDescriptorString(ref.interfaceType.getDescriptor(), Def.class.getClassLoader());
         try {
             if (ref.interfaceType.equals(ref.samMethodType)) {
                 callSite = LambdaMetafactory.altMetafactory(lookup, 
                         ref.invokedName, 
                         invokedType,
                         samMethodType,
                         ref.implMethodHandle,
                         samMethodType,
                         0);
             } else {
                 callSite = LambdaMetafactory.altMetafactory(lookup, 
                         ref.invokedName, 
                         invokedType,
                         samMethodType,
                         ref.implMethodHandle,
                         samMethodType,
                         LambdaMetafactory.FLAG_BRIDGES,
                         1,
                         interfaceType);
             }
         } catch (LambdaConversionException e) {
             throw new RuntimeException(e);
         }
         try {
             // create an implementation of the interface (instance)
             Object instance = callSite.dynamicInvoker().asType(MethodType.methodType(clazz)).invoke();
             // bind this instance as a constant replacement for the parameter
             return MethodHandles.dropArguments(MethodHandles.constant(clazz, instance), 0, Object.class);
         } catch (Throwable e) {
             throw new RuntimeException(e);
         }
     }
     

    /**
     * Looks up handle for a dynamic field getter (field load)
     * <p>
     * A dynamic field load for variable {@code x} of type {@code def} looks like:
     * {@code y = x.field}
     * <p>
     * The following field loads are allowed:
     * <ul>
     *   <li>Whitelisted {@code field} from receiver's class or any superclasses.
     *   <li>Whitelisted method named {@code getField()} from receiver's class/superclasses/interfaces.
     *   <li>Whitelisted method named {@code isField()} from receiver's class/superclasses/interfaces.
     *   <li>The {@code length} field of an array.
     *   <li>The value corresponding to a map key named {@code field} when the receiver is a Map.
     *   <li>The value in a list at element {@code field} (integer) when the receiver is a List.
     * </ul>
     * <p>
     * This method traverses {@code recieverClass}'s class hierarchy (including interfaces)
     * until it finds a matching whitelisted getter. If one is not found, it throws an exception.
     * Otherwise it returns a handle to the matching getter.
     * <p>
     * @param receiverClass Class of the object to retrieve the field from.
     * @param name Name of the field.
     * @return pointer to matching field. never returns null.
     * @throws IllegalArgumentException if no matching whitelisted field was found.
     */
    static MethodHandle lookupGetter(Class<?> receiverClass, String name) {
        // first try whitelist
        for (Class<?> clazz = receiverClass; clazz != null; clazz = clazz.getSuperclass()) {
            RuntimeClass struct = Definition.getRuntimeClass(clazz);

            if (struct != null) {
                MethodHandle handle = struct.getters.get(name);
                if (handle != null) {
                    return handle;
                }
            }

            for (final Class<?> iface : clazz.getInterfaces()) {
                struct = Definition.getRuntimeClass(iface);

                if (struct != null) {
                    MethodHandle handle = struct.getters.get(name);
                    if (handle != null) {
                        return handle;
                    }
                }
            }
        }
        // special case: arrays, maps, and lists
        if (receiverClass.isArray() && "length".equals(name)) {
            // arrays expose .length as a read-only getter
            return arrayLengthGetter(receiverClass);
        } else if (Map.class.isAssignableFrom(receiverClass)) {
            // maps allow access like mymap.key
            // wire 'key' as a parameter, its a constant in painless
            return MethodHandles.insertArguments(MAP_GET, 1, name);
        } else if (List.class.isAssignableFrom(receiverClass)) {
            // lists allow access like mylist.0
            // wire '0' (index) as a parameter, its a constant. this also avoids
            // parsing the same integer millions of times!
            try {
                int index = Integer.parseInt(name);
                return MethodHandles.insertArguments(LIST_GET, 1, index);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException( "Illegal list shortcut value [" + name + "].");
            }
        }

        throw new IllegalArgumentException("Unable to find dynamic field [" + name + "] " +
                                           "for class [" + receiverClass.getCanonicalName() + "].");
    }

    /**
     * Looks up handle for a dynamic field setter (field store)
     * <p>
     * A dynamic field store for variable {@code x} of type {@code def} looks like:
     * {@code x.field = y}
     * <p>
     * The following field stores are allowed:
     * <ul>
     *   <li>Whitelisted {@code field} from receiver's class or any superclasses.
     *   <li>Whitelisted method named {@code setField()} from receiver's class/superclasses/interfaces.
     *   <li>The value corresponding to a map key named {@code field} when the receiver is a Map.
     *   <li>The value in a list at element {@code field} (integer) when the receiver is a List.
     * </ul>
     * <p>
     * This method traverses {@code recieverClass}'s class hierarchy (including interfaces)
     * until it finds a matching whitelisted setter. If one is not found, it throws an exception.
     * Otherwise it returns a handle to the matching setter.
     * <p>
     * @param receiverClass Class of the object to retrieve the field from.
     * @param name Name of the field.
     * @return pointer to matching field. never returns null.
     * @throws IllegalArgumentException if no matching whitelisted field was found.
     */
    static MethodHandle lookupSetter(Class<?> receiverClass, String name) {
        // first try whitelist
        for (Class<?> clazz = receiverClass; clazz != null; clazz = clazz.getSuperclass()) {
            RuntimeClass struct = Definition.getRuntimeClass(clazz);

            if (struct != null) {
                MethodHandle handle = struct.setters.get(name);
                if (handle != null) {
                    return handle;
                }
            }

            for (final Class<?> iface : clazz.getInterfaces()) {
                struct = Definition.getRuntimeClass(iface);

                if (struct != null) {
                    MethodHandle handle = struct.setters.get(name);
                    if (handle != null) {
                        return handle;
                    }
                }
            }
        }
        // special case: maps, and lists
        if (Map.class.isAssignableFrom(receiverClass)) {
            // maps allow access like mymap.key
            // wire 'key' as a parameter, its a constant in painless
            return MethodHandles.insertArguments(MAP_PUT, 1, name);
        } else if (List.class.isAssignableFrom(receiverClass)) {
            // lists allow access like mylist.0
            // wire '0' (index) as a parameter, its a constant. this also avoids
            // parsing the same integer millions of times!
            try {
                int index = Integer.parseInt(name);
                return MethodHandles.insertArguments(LIST_SET, 1, index);
            } catch (final NumberFormatException exception) {
                throw new IllegalArgumentException( "Illegal list shortcut value [" + name + "].");
            }
        }

        throw new IllegalArgumentException("Unable to find dynamic field [" + name + "] " +
                                           "for class [" + receiverClass.getCanonicalName() + "].");
    }

    /**
     * Returns a method handle to do an array store.
     * @param receiverClass Class of the array to store the value in
     * @return a MethodHandle that accepts the receiver as first argument, the index as second argument,
     *   and the value to set as 3rd argument. Return value is undefined and should be ignored.
     */
    static MethodHandle lookupArrayStore(Class<?> receiverClass) {
        if (receiverClass.isArray()) {
            return MethodHandles.arrayElementSetter(receiverClass);
        } else if (Map.class.isAssignableFrom(receiverClass)) {
            // maps allow access like mymap[key]
            return MAP_PUT;
        } else if (List.class.isAssignableFrom(receiverClass)) {
            return LIST_SET;
        }
        throw new IllegalArgumentException("Attempting to address a non-array type " +
                                           "[" + receiverClass.getCanonicalName() + "] as an array.");
    }

    /**
     * Returns a method handle to do an array load.
     * @param receiverClass Class of the array to load the value from
     * @return a MethodHandle that accepts the receiver as first argument, the index as second argument.
     *   It returns the loaded value.
     */
    static MethodHandle lookupArrayLoad(Class<?> receiverClass) {
        if (receiverClass.isArray()) {
            return MethodHandles.arrayElementGetter(receiverClass);
        } else if (Map.class.isAssignableFrom(receiverClass)) {
            // maps allow access like mymap[key]
            return MAP_GET;
        } else if (List.class.isAssignableFrom(receiverClass)) {
            return LIST_GET;
        }
        throw new IllegalArgumentException("Attempting to address a non-array type " +
                                           "[" + receiverClass.getCanonicalName() + "] as an array.");
    }

    // NOTE: Below methods are not cached, instead invoked directly because they are performant.
    //       We also check for Long values first when possible since the type is more
    //       likely to be a Long than a Float.

    public static Object not(final Object unary) {
        if (unary instanceof Double || unary instanceof Long || unary instanceof Float) {
            return ~((Number)unary).longValue();
        } else if (unary instanceof Number) {
            return ~((Number)unary).intValue();
        } else if (unary instanceof Character) {
            return ~(int)(char)unary;
        }

        throw new ClassCastException("Cannot apply [~] operation to type " +
                "[" + unary.getClass().getCanonicalName() + "].");
    }

    public static Object neg(final Object unary) {
        if (unary instanceof Double) {
            return -(double)unary;
        } else if (unary instanceof Float) {
            return -(float)unary;
        } else if (unary instanceof Long) {
            return -(long)unary;
        } else if (unary instanceof Number) {
            return -((Number)unary).intValue();
        } else if (unary instanceof Character) {
            return -(char)unary;
        }

        throw new ClassCastException("Cannot apply [-] operation to type " +
                "[" + unary.getClass().getCanonicalName() + "].");
    }

    public static Object mul(final Object left, final Object right) {
        if (left instanceof Number) {
            if (right instanceof Number) {
                if (left instanceof Double || right instanceof Double) {
                    return ((Number)left).doubleValue() * ((Number)right).doubleValue();
                } else if (left instanceof Float || right instanceof Float) {
                    return ((Number)left).floatValue() * ((Number)right).floatValue();
                } else if (left instanceof Long || right instanceof Long) {
                    return ((Number)left).longValue() * ((Number)right).longValue();
                } else {
                    return ((Number)left).intValue() * ((Number)right).intValue();
                }
            } else if (right instanceof Character) {
                if (left instanceof Double) {
                    return ((Number)left).doubleValue() * (char)right;
                } else if (left instanceof Long) {
                    return ((Number)left).longValue() * (char)right;
                } else if (left instanceof Float) {
                    return ((Number)left).floatValue() * (char)right;
                } else {
                    return ((Number)left).intValue() * (char)right;
                }
            }
        } else if (left instanceof Character) {
            if (right instanceof Number) {
                if (right instanceof Double) {
                    return (char)left * ((Number)right).doubleValue();
                } else if (right instanceof Long) {
                    return (char)left * ((Number)right).longValue();
                } else if (right instanceof Float) {
                    return (char)left * ((Number)right).floatValue();
                } else {
                    return (char)left * ((Number)right).intValue();
                }
            } else if (right instanceof Character) {
                return (char)left * (char)right;
            }
        }

        throw new ClassCastException("Cannot apply [*] operation to types " +
                "[" + left.getClass().getCanonicalName() + "] and [" + right.getClass().getCanonicalName() + "].");
    }

    public static Object div(final Object left, final Object right) {
        if (left instanceof Number) {
            if (right instanceof Number) {
                if (left instanceof Double || right instanceof Double) {
                    return ((Number)left).doubleValue() / ((Number)right).doubleValue();
                } else if (left instanceof Float || right instanceof Float) {
                    return ((Number)left).floatValue() / ((Number)right).floatValue();
                } else if (left instanceof Long || right instanceof Long) {
                    return ((Number)left).longValue() / ((Number)right).longValue();
                } else {
                    return ((Number)left).intValue() / ((Number)right).intValue();
                }
            } else if (right instanceof Character) {
                if (left instanceof Double) {
                    return ((Number)left).doubleValue() / (char)right;
                } else if (left instanceof Long) {
                    return ((Number)left).longValue() / (char)right;
                } else if (left instanceof Float) {
                    return ((Number)left).floatValue() / (char)right;
                } else {
                    return ((Number)left).intValue() / (char)right;
                }
            }
        } else if (left instanceof Character) {
            if (right instanceof Number) {
                if (right instanceof Double) {
                    return (char)left / ((Number)right).doubleValue();
                } else if (right instanceof Long) {
                    return (char)left / ((Number)right).longValue();
                } else if (right instanceof Float) {
                    return (char)left / ((Number)right).floatValue();
                } else {
                    return (char)left / ((Number)right).intValue();
                }
            } else if (right instanceof Character) {
                return (char)left / (char)right;
            }
        }

        throw new ClassCastException("Cannot apply [/] operation to types " +
                "[" + left.getClass().getCanonicalName() + "] and [" + right.getClass().getCanonicalName() + "].");
    }

    public static Object rem(final Object left, final Object right) {
        if (left instanceof Number) {
            if (right instanceof Number) {
                if (left instanceof Double || right instanceof Double) {
                    return ((Number)left).doubleValue() % ((Number)right).doubleValue();
                } else if (left instanceof Float || right instanceof Float) {
                    return ((Number)left).floatValue() % ((Number)right).floatValue();
                } else if (left instanceof Long || right instanceof Long) {
                    return ((Number)left).longValue() % ((Number)right).longValue();
                } else {
                    return ((Number)left).intValue() % ((Number)right).intValue();
                }
            } else if (right instanceof Character) {
                if (left instanceof Double) {
                    return ((Number)left).doubleValue() % (char)right;
                } else if (left instanceof Long) {
                    return ((Number)left).longValue() % (char)right;
                } else if (left instanceof Float) {
                    return ((Number)left).floatValue() % (char)right;
                } else {
                    return ((Number)left).intValue() % (char)right;
                }
            }
        } else if (left instanceof Character) {
            if (right instanceof Number) {
                if (right instanceof Double) {
                    return (char)left % ((Number)right).doubleValue();
                } else if (right instanceof Long) {
                    return (char)left % ((Number)right).longValue();
                } else if (right instanceof Float) {
                    return (char)left % ((Number)right).floatValue();
                } else {
                    return (char)left % ((Number)right).intValue();
                }
            } else if (right instanceof Character) {
                return (char)left % (char)right;
            }
        }

        throw new ClassCastException("Cannot apply [%] operation to types " +
                "[" + left.getClass().getCanonicalName() + "] and [" + right.getClass().getCanonicalName() + "].");
    }

    public static Object add(final Object left, final Object right) {
        if (left instanceof String || right instanceof String) {
            return "" + left + right;
        } else if (left instanceof Number) {
            if (right instanceof Number) {
                if (left instanceof Double || right instanceof Double) {
                    return ((Number)left).doubleValue() + ((Number)right).doubleValue();
                } else if (left instanceof Float || right instanceof Float) {
                    return ((Number)left).floatValue() + ((Number)right).floatValue();
                } else if (left instanceof Long || right instanceof Long) {
                    return ((Number)left).longValue() + ((Number)right).longValue();
                } else {
                    return ((Number)left).intValue() + ((Number)right).intValue();
                }
            } else if (right instanceof Character) {
                if (left instanceof Double) {
                    return ((Number)left).doubleValue() + (char)right;
                } else if (left instanceof Long) {
                    return ((Number)left).longValue() + (char)right;
                } else if (left instanceof Float) {
                    return ((Number)left).floatValue() + (char)right;
                } else {
                    return ((Number)left).intValue() + (char)right;
                }
            }
        } else if (left instanceof Character) {
            if (right instanceof Number) {
                if (right instanceof Double) {
                    return (char)left + ((Number)right).doubleValue();
                } else if (right instanceof Long) {
                    return (char)left + ((Number)right).longValue();
                } else if (right instanceof Float) {
                    return (char)left + ((Number)right).floatValue();
                } else {
                    return (char)left + ((Number)right).intValue();
                }
            } else if (right instanceof Character) {
                return (char)left + (char)right;
            }
        }

        throw new ClassCastException("Cannot apply [+] operation to types " +
                "[" + left.getClass().getCanonicalName() + "] and [" + right.getClass().getCanonicalName() + "].");
    }

    public static Object sub(final Object left, final Object right) {
        if (left instanceof Number) {
            if (right instanceof Number) {
                if (left instanceof Double || right instanceof Double) {
                    return ((Number)left).doubleValue() - ((Number)right).doubleValue();
                } else if (left instanceof Float || right instanceof Float) {
                    return ((Number)left).floatValue() - ((Number)right).floatValue();
                } else if (left instanceof Long || right instanceof Long) {
                    return ((Number)left).longValue() - ((Number)right).longValue();
                } else {
                    return ((Number)left).intValue() - ((Number)right).intValue();
                }
            } else if (right instanceof Character) {
                if (left instanceof Double) {
                    return ((Number)left).doubleValue() - (char)right;
                } else if (left instanceof Long) {
                    return ((Number)left).longValue() - (char)right;
                } else if (left instanceof Float) {
                    return ((Number)left).floatValue() - (char)right;
                } else {
                    return ((Number)left).intValue() - (char)right;
                }
            }
        } else if (left instanceof Character) {
            if (right instanceof Number) {
                if (right instanceof Double) {
                    return (char)left - ((Number)right).doubleValue();
                } else if (right instanceof Long) {
                    return (char)left - ((Number)right).longValue();
                } else if (right instanceof Float) {
                    return (char)left - ((Number)right).floatValue();
                } else {
                    return (char)left - ((Number)right).intValue();
                }
            } else if (right instanceof Character) {
                return (char)left - (char)right;
            }
        }

        throw new ClassCastException("Cannot apply [-] operation to types " +
                "[" + left.getClass().getCanonicalName() + "] and [" + right.getClass().getCanonicalName() + "].");
    }

    public static Object lsh(final Object left, final int right) {
        if (left instanceof Double || left instanceof Long || left instanceof Float) {
            return ((Number)left).longValue() << right;
        } else if (left instanceof Number) {
            return ((Number)left).intValue() << right;
        } else if (left instanceof Character) {
            return (char)left << right;
        }

        throw new ClassCastException("Cannot apply [<<] operation to types [" + left.getClass().getCanonicalName() + "] and [int].");
    }

    public static Object rsh(final Object left, final int right) {
        if (left instanceof Double || left instanceof Long || left instanceof Float) {
            return ((Number)left).longValue() >> right;
        } else if (left instanceof Number) {
            return ((Number)left).intValue() >> right;
        } else if (left instanceof Character) {
            return (char)left >> right;
        }

        throw new ClassCastException("Cannot apply [>>] operation to types [" + left.getClass().getCanonicalName() + "] and [int].");
    }

    public static Object ush(final Object left, final int right) {
        if (left instanceof Double || left instanceof Long || left instanceof Float) {
            return ((Number)left).longValue() >>> right;
        } else if (left instanceof Number) {
            return ((Number)left).intValue() >>> right;
        } else if (left instanceof Character) {
            return (char)left >>> right;
        }

        throw new ClassCastException("Cannot apply [>>>] operation to types [" + left.getClass().getCanonicalName() + "] and [int].");
    }

    public static Object and(final Object left, final Object right) {
        if (left instanceof Boolean && right instanceof Boolean) {
            return (boolean)left && (boolean)right;
        } else if (left instanceof Number) {
            if (right instanceof Number) {
                if (left instanceof Double || right instanceof Double ||
                    left instanceof Long || right instanceof Long ||
                    left instanceof Float || right instanceof Float) {
                    return ((Number)left).longValue() & ((Number)right).longValue();
                } else {
                    return ((Number)left).intValue() & ((Number)right).intValue();
                }
            } else if (right instanceof Character) {
                if (left instanceof Double || left instanceof Long || left instanceof Float) {
                    return ((Number)left).longValue() & (char)right;
                } else {
                    return ((Number)left).intValue() & (char)right;
                }
            }
        } else if (left instanceof Character) {
            if (right instanceof Number) {
                if (right instanceof Double || right instanceof Long || right instanceof Float) {
                    return (char)left & ((Number)right).longValue();
                } else {
                    return (char)left & ((Number)right).intValue();
                }
            } else if (right instanceof Character) {
                return (char)left & (char)right;
            }
        }

        throw new ClassCastException("Cannot apply [&] operation to types " +
                "[" + left.getClass().getCanonicalName() + "] and [" + right.getClass().getCanonicalName() + "].");
    }

    public static Object xor(final Object left, final Object right) {
        if (left instanceof Boolean && right instanceof Boolean) {
            return (boolean)left ^ (boolean)right;
        } else if (left instanceof Number) {
            if (right instanceof Number) {
                if (left instanceof Double || right instanceof Double ||
                    left instanceof Long || right instanceof Long ||
                    left instanceof Float || right instanceof Float) {
                    return ((Number)left).longValue() ^ ((Number)right).longValue();
                } else {
                    return ((Number)left).intValue() ^ ((Number)right).intValue();
                }
            } else if (right instanceof Character) {
                if (left instanceof Double || left instanceof Long || left instanceof Float) {
                    return ((Number)left).longValue() ^ (char)right;
                } else {
                    return ((Number)left).intValue() ^ (char)right;
                }
            }
        } else if (left instanceof Character) {
            if (right instanceof Number) {
                if (right instanceof Double || right instanceof Long || right instanceof Float) {
                    return (char)left ^ ((Number)right).longValue();
                } else {
                    return (char)left ^ ((Number)right).intValue();
                }
            } else if (right instanceof Character) {
                return (char)left ^ (char)right;
            }
        }

        throw new ClassCastException("Cannot apply [^] operation to types " +
                "[" + left.getClass().getCanonicalName() + "] and [" + right.getClass().getCanonicalName() + "].");
    }

    public static Object or(final Object left, final Object right) {
        if (left instanceof Boolean && right instanceof Boolean) {
            return (boolean)left || (boolean)right;
        } else if (left instanceof Number) {
            if (right instanceof Number) {
                if (left instanceof Double || right instanceof Double ||
                    left instanceof Long || right instanceof Long ||
                    left instanceof Float || right instanceof Float) {
                    return ((Number)left).longValue() | ((Number)right).longValue();
                } else {
                    return ((Number)left).intValue() | ((Number)right).intValue();
                }
            } else if (right instanceof Character) {
                if (left instanceof Double || left instanceof Long || left instanceof Float) {
                    return ((Number)left).longValue() | (char)right;
                } else {
                    return ((Number)left).intValue() | (char)right;
                }
            }
        } else if (left instanceof Character) {
            if (right instanceof Number) {
                if (right instanceof Double || right instanceof Long || right instanceof Float) {
                    return (char)left | ((Number)right).longValue();
                } else {
                    return (char)left | ((Number)right).intValue();
                }
            } else if (right instanceof Character) {
                return (char)left | (char)right;
            }
        }

        throw new ClassCastException("Cannot apply [|] operation to types " +
                "[" + left.getClass().getCanonicalName() + "] and [" + right.getClass().getCanonicalName() + "].");
    }

    public static boolean eq(final Object left, final Object right) {
        if (left != null && right != null) {
            if (left instanceof Double) {
                if (right instanceof Number) {
                    return (double)left == ((Number)right).doubleValue();
                } else if (right instanceof Character) {
                    return (double)left == (char)right;
                }
            } else if (right instanceof Double) {
                if (left instanceof Number) {
                    return ((Number)left).doubleValue() == (double)right;
                } else if (left instanceof Character) {
                    return (char)left == ((Number)right).doubleValue();
                }
            } else if (left instanceof Float) {
                if (right instanceof Number) {
                    return (float)left == ((Number)right).floatValue();
                } else if (right instanceof Character) {
                    return (float)left == (char)right;
                }
            } else if (right instanceof Float) {
                if (left instanceof Number) {
                    return ((Number)left).floatValue() == (float)right;
                } else if (left instanceof Character) {
                    return (char)left == ((Number)right).floatValue();
                }
            } else if (left instanceof Long) {
                if (right instanceof Number) {
                    return (long)left == ((Number)right).longValue();
                } else if (right instanceof Character) {
                    return (long)left == (char)right;
                }
            } else if (right instanceof Long) {
                if (left instanceof Number) {
                    return ((Number)left).longValue() == (long)right;
                } else if (left instanceof Character) {
                    return (char)left == ((Number)right).longValue();
                }
            } else if (left instanceof Number) {
                if (right instanceof Number) {
                    return ((Number)left).intValue() == ((Number)right).intValue();
                } else if (right instanceof Character) {
                    return ((Number)left).intValue() == (char)right;
                }
            } else if (right instanceof Number && left instanceof Character) {
                return (char)left == ((Number)right).intValue();
            } else if (left instanceof Character && right instanceof Character) {
                return (char)left == (char)right;
            }

            return left.equals(right);
        }

        return left == null && right == null;
    }

    public static boolean lt(final Object left, final Object right) {
        if (left instanceof Number) {
            if (right instanceof Number) {
                if (left instanceof Double || right instanceof Double) {
                    return ((Number)left).doubleValue() < ((Number)right).doubleValue();
                } else if (left instanceof Float || right instanceof Float) {
                    return ((Number)left).floatValue() < ((Number)right).floatValue();
                } else if (left instanceof Long || right instanceof Long) {
                    return ((Number)left).longValue() < ((Number)right).longValue();
                } else {
                    return ((Number)left).intValue() < ((Number)right).intValue();
                }
            } else if (right instanceof Character) {
                if (left instanceof Double) {
                    return ((Number)left).doubleValue() < (char)right;
                } else if (left instanceof Long) {
                    return ((Number)left).longValue() < (char)right;
                } else if (left instanceof Float) {
                    return ((Number)left).floatValue() < (char)right;
                } else {
                    return ((Number)left).intValue() < (char)right;
                }
            }
        } else if (left instanceof Character) {
            if (right instanceof Number) {
                if (right instanceof Double) {
                    return (char)left < ((Number)right).doubleValue();
                } else if (right instanceof Long) {
                    return (char)left < ((Number)right).longValue();
                } else if (right instanceof Float) {
                    return (char)left < ((Number)right).floatValue();
                } else {
                    return (char)left < ((Number)right).intValue();
                }
            } else if (right instanceof Character) {
                return (char)left < (char)right;
            }
        }

        throw new ClassCastException("Cannot apply [<] operation to types " +
                "[" + left.getClass().getCanonicalName() + "] and [" + right.getClass().getCanonicalName() + "].");
    }

    public static boolean lte(final Object left, final Object right) {
        if (left instanceof Number) {
            if (right instanceof Number) {
                if (left instanceof Double || right instanceof Double) {
                    return ((Number)left).doubleValue() <= ((Number)right).doubleValue();
                } else if (left instanceof Float || right instanceof Float) {
                    return ((Number)left).floatValue() <= ((Number)right).floatValue();
                } else if (left instanceof Long || right instanceof Long) {
                    return ((Number)left).longValue() <= ((Number)right).longValue();
                } else {
                    return ((Number)left).intValue() <= ((Number)right).intValue();
                }
            } else if (right instanceof Character) {
                if (left instanceof Double) {
                    return ((Number)left).doubleValue() <= (char)right;
                } else if (left instanceof Long) {
                    return ((Number)left).longValue() <= (char)right;
                } else if (left instanceof Float) {
                    return ((Number)left).floatValue() <= (char)right;
                } else {
                    return ((Number)left).intValue() <= (char)right;
                }
            }
        } else if (left instanceof Character) {
            if (right instanceof Number) {
                if (right instanceof Double) {
                    return (char)left <= ((Number)right).doubleValue();
                } else if (right instanceof Long) {
                    return (char)left <= ((Number)right).longValue();
                } else if (right instanceof Float) {
                    return (char)left <= ((Number)right).floatValue();
                } else {
                    return (char)left <= ((Number)right).intValue();
                }
            } else if (right instanceof Character) {
                return (char)left <= (char)right;
            }
        }

        throw new ClassCastException("Cannot apply [<=] operation to types " +
                "[" + left.getClass().getCanonicalName() + "] and [" + right.getClass().getCanonicalName() + "].");
    }

    public static boolean gt(final Object left, final Object right) {
        if (left instanceof Number) {
            if (right instanceof Number) {
                if (left instanceof Double || right instanceof Double) {
                    return ((Number)left).doubleValue() > ((Number)right).doubleValue();
                } else if (left instanceof Float || right instanceof Float) {
                    return ((Number)left).floatValue() > ((Number)right).floatValue();
                } else if (left instanceof Long || right instanceof Long) {
                    return ((Number)left).longValue() > ((Number)right).longValue();
                } else {
                    return ((Number)left).intValue() > ((Number)right).intValue();
                }
            } else if (right instanceof Character) {
                if (left instanceof Double) {
                    return ((Number)left).doubleValue() > (char)right;
                } else if (left instanceof Long) {
                    return ((Number)left).longValue() > (char)right;
                } else if (left instanceof Float) {
                    return ((Number)left).floatValue() > (char)right;
                } else {
                    return ((Number)left).intValue() > (char)right;
                }
            }
        } else if (left instanceof Character) {
            if (right instanceof Number) {
                if (right instanceof Double) {
                    return (char)left > ((Number)right).doubleValue();
                } else if (right instanceof Long) {
                    return (char)left > ((Number)right).longValue();
                } else if (right instanceof Float) {
                    return (char)left > ((Number)right).floatValue();
                } else {
                    return (char)left > ((Number)right).intValue();
                }
            } else if (right instanceof Character) {
                return (char)left > (char)right;
            }
        }

        throw new ClassCastException("Cannot apply [>] operation to types " +
                "[" + left.getClass().getCanonicalName() + "] and [" + right.getClass().getCanonicalName() + "].");
    }

    public static boolean gte(final Object left, final Object right) {
        if (left instanceof Number) {
            if (right instanceof Number) {
                if (left instanceof Double || right instanceof Double) {
                    return ((Number)left).doubleValue() >= ((Number)right).doubleValue();
                } else if (left instanceof Float || right instanceof Float) {
                    return ((Number)left).floatValue() >= ((Number)right).floatValue();
                } else if (left instanceof Long || right instanceof Long) {
                    return ((Number)left).longValue() >= ((Number)right).longValue();
                } else {
                    return ((Number)left).intValue() >= ((Number)right).intValue();
                }
            } else if (right instanceof Character) {
                if (left instanceof Double) {
                    return ((Number)left).doubleValue() >= (char)right;
                } else if (left instanceof Long) {
                    return ((Number)left).longValue() >= (char)right;
                } else if (left instanceof Float) {
                    return ((Number)left).floatValue() >= (char)right;
                } else {
                    return ((Number)left).intValue() >= (char)right;
                }
            }
        } else if (left instanceof Character) {
            if (right instanceof Number) {
                if (right instanceof Double) {
                    return (char)left >= ((Number)right).doubleValue();
                } else if (right instanceof Long) {
                    return (char)left >= ((Number)right).longValue();
                } else if (right instanceof Float) {
                    return (char)left >= ((Number)right).floatValue();
                } else {
                    return (char)left >= ((Number)right).intValue();
                }
            } else if (right instanceof Character) {
                return (char)left >= (char)right;
            }
        }

        throw new ClassCastException("Cannot apply [>] operation to types " +
                "[" + left.getClass().getCanonicalName() + "] and [" + right.getClass().getCanonicalName() + "].");
    }

    // Conversion methods for Def to primitive types.

    public static boolean DefToboolean(final Object value) {
        return (boolean)value;
    }

    public static byte DefTobyteImplicit(final Object value) {
        return (byte)value;
    }

    public static short DefToshortImplicit(final Object value) {
        if (value instanceof Byte) {
            return (byte)value;
        } else {
            return (short)value;
        }
    }

    public static char DefTocharImplicit(final Object value) {
        if (value instanceof Byte) {
            return (char)(byte)value;
        } else {
            return (char)value;
        }
    }

    public static int DefTointImplicit(final Object value) {
        if (value instanceof Byte) {
            return (byte)value;
        } else if (value instanceof Short) {
            return (short)value;
        } else if (value instanceof Character) {
            return (char)value;
        } else {
            return (int)value;
        }
    }

    public static long DefTolongImplicit(final Object value) {
        if (value instanceof Byte) {
            return (byte)value;
        } else if (value instanceof Short) {
            return (short)value;
        } else if (value instanceof Character) {
            return (char)value;
        } else if (value instanceof Integer) {
            return (int)value;
        } else {
            return (long)value;
        }
    }

    public static float DefTofloatImplicit(final Object value) {
        if (value instanceof Byte) {
            return (byte)value;
        } else if (value instanceof Short) {
            return (short)value;
        } else if (value instanceof Character) {
            return (char)value;
        } else if (value instanceof Integer) {
            return (int)value;
        } else if (value instanceof Long) {
            return (long)value;
        } else {
            return (float)value;
        }
    }

    public static double DefTodoubleImplicit(final Object value) {
        if (value instanceof Byte) {
            return (byte)value;
        } else if (value instanceof Short) {
            return (short)value;
        } else if (value instanceof Character) {
            return (char)value;
        } else if (value instanceof Integer) {
            return (int)value;
        } else if (value instanceof Long) {
            return (long)value;
        } else if (value instanceof Float) {
            return (float)value;
        } else {
            return (double)value;
        }
    }

    public static byte DefTobyteExplicit(final Object value) {
        if (value instanceof Character) {
            return (byte)(char)value;
        } else {
            return ((Number)value).byteValue();
        }
    }

    public static short DefToshortExplicit(final Object value) {
        if (value instanceof Character) {
            return (short)(char)value;
        } else {
            return ((Number)value).shortValue();
        }
    }

    public static char DefTocharExplicit(final Object value) {
        if (value instanceof Character) {
            return ((Character)value);
        } else {
            return (char)((Number)value).intValue();
        }
    }

    public static int DefTointExplicit(final Object value) {
        if (value instanceof Character) {
            return (char)value;
        } else {
            return ((Number)value).intValue();
        }
    }

    public static long DefTolongExplicit(final Object value) {
        if (value instanceof Character) {
            return (char)value;
        } else {
            return ((Number)value).longValue();
        }
    }

    public static float DefTofloatExplicit(final Object value) {
        if (value instanceof Character) {
            return (char)value;
        } else {
            return ((Number)value).floatValue();
        }
    }

    public static double DefTodoubleExplicit(final Object value) {
        if (value instanceof Character) {
            return (char)value;
        } else {
            return ((Number)value).doubleValue();
        }
    }
}
