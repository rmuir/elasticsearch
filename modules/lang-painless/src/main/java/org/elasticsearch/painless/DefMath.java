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

@SuppressWarnings("unused")
public class DefMath {
    
    private static int bwnot(int v) {
        return ~v;
    }
    
    private static long bwnot(long v) {
        return ~v;
    }
    
    private static float bwnot(float v) {
        throw new ClassCastException("Cannot apply not [~] to type [float]");
    }
    
    private static double bwnot(double v) {
        throw new ClassCastException("Cannot apply not [~] to type [double]");
    }
    
    private static boolean bwnot(boolean v) {
        throw new ClassCastException("Cannot apply not [~] to type [boolean]");
    }
    
    private static Object bwnot(Object unary) {
        if (unary instanceof Long) {
            return ~(Long)unary;
        } else if (unary instanceof Integer) {
            return ~(Integer)unary;
        } else if (unary instanceof Short) {
            return ~(Short)unary;
        } else if (unary instanceof Character) {
            return ~(Character)unary;
        } else if (unary instanceof Byte) {
            return ~(Byte)unary;
        }

        throw new ClassCastException("Cannot apply [~] operation to type " +
                "[" + unary.getClass().getCanonicalName() + "].");
    }
    
    private static int neg(int v) {
        return -v;
    }
    
    private static long neg(long v) {
        return -v;
    }
    
    private static float neg(float v) {
        return -v;
    }
    
    private static double neg(double v) {
        return -v;
    }
    
    private static boolean neg(boolean v) {
        throw new ClassCastException("Cannot apply [-] operation to type [boolean]");
    }
    
    private static Object neg(final Object unary) {
        if (unary instanceof Double) {
            return -(Double)unary;
        } else if (unary instanceof Long) {
            return -(Long)unary;
        } else if (unary instanceof Integer) {
            return -(Integer)unary;
        } else if (unary instanceof Float) {
            return -(Float)unary;
        } else if (unary instanceof Short) {
            return -(Short)unary;
        } else if (unary instanceof Character) {
            return -(Character)unary;
        } else if (unary instanceof Byte) {
            return -(Byte)unary;
        }

        throw new ClassCastException("Cannot apply [-] operation to type " +
                "[" + unary.getClass().getCanonicalName() + "].");
    }
    
    private static int mul(int a, int b) {
        return a * b;
    }
    
    private static long mul(long a, long b) {
        return a * b;
    }
    
    private static float mul(float a, float b) {
        return a * b;
    }
    
    private static double mul(double a, double b) {
        return a * b;
    }
    
    private static boolean mul(boolean a, boolean b) {
        throw new ClassCastException("Cannot apply [*] operation to type [boolean]");
    }
    
    private static Object mul(Object left, Object right) {
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
    
    private static int div(int a, int b) {
        return a / b;
    }
    
    private static long div(long a, long b) {
        return a / b;
    }
    
    private static float div(float a, float b) {
        return a / b;
    }
    
    private static double div(double a, double b) {
        return a / b;
    }
    
    private static boolean div(boolean a, boolean b) {
        throw new ClassCastException("Cannot apply [/] operation to type [boolean]");
    }
    
    private static Object div(Object left, Object right) {
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
    
    private static int rem(int a, int b) {
        return a % b;
    }
    
    private static long rem(long a, long b) {
        return a % b;
    }
    
    private static float rem(float a, float b) {
        return a % b;
    }
    
    private static double rem(double a, double b) {
        return a % b;
    }
    
    private static boolean rem(boolean a, boolean b) {
        throw new ClassCastException("Cannot apply [%] operation to type [boolean]");
    }
    
    private static Object rem(Object left, Object right) {
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
    
    private static int add(int a, int b) {
        return a + b;
    }
    
    private static long add(long a, long b) {
        return a + b;
    }
    
    private static float add(float a, float b) {
        return a + b;
    }
    
    private static double add(double a, double b) {
        return a + b;
    }
    
    private static boolean add(boolean a, boolean b) {
        throw new ClassCastException("Cannot apply [+] operation to type [boolean]");
    }
    
    private static Object add(Object left, Object right) {
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
    
    private static int sub(int a, int b) {
        return a - b;
    }
    
    private static long sub(long a, long b) {
        return a - b;
    }
    
    private static float sub(float a, float b) {
        return a - b;
    }
    
    private static double sub(double a, double b) {
        return a - b;
    }
    
    private static boolean sub(boolean a, boolean b) {
        throw new ClassCastException("Cannot apply [-] operation to type [boolean]");
    }
    
    private static Object sub(Object left, Object right) {
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
    
    private static boolean eq(int a, int b) {
        return a == b;
    }
    
    private static boolean eq(long a, long b) {
        return a == b;
    }
    
    private static boolean eq(float a, float b) {
        return a == b;
    }
    
    private static boolean eq(double a, double b) {
        return a == b;
    }
    
    private static boolean eq(boolean a, boolean b) {
        return a == b;
    }
    
    private static boolean eq(Object left, Object right) {
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
    
    private static boolean lt(int a, int b) {
        return a < b;
    }
    
    private static boolean lt(long a, long b) {
        return a < b;
    }
    
    private static boolean lt(float a, float b) {
        return a < b;
    }
    
    private static boolean lt(double a, double b) {
        return a < b;
    }
    
    private static boolean lt(boolean a, boolean b) {
        throw new ClassCastException("Cannot apply [<] operation to type [boolean]");    
    }
    
    private static boolean lt(Object left, Object right) {
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

    private static boolean lte(int a, int b) {
        return a <= b;
    }
    
    private static boolean lte(long a, long b) {
        return a <= b;
    }
    
    private static boolean lte(float a, float b) {
        return a <= b;
    }
    
    private static boolean lte(double a, double b) {
        return a <= b;
    }
    
    private static boolean lte(boolean a, boolean b) {
        throw new ClassCastException("Cannot apply [<=] operation to type [boolean]");    
    }
    
    private static boolean lte(Object left, Object right) {
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

    private static boolean gt(int a, int b) {
        return a > b;
    }
    
    private static boolean gt(long a, long b) {
        return a > b;
    }
    
    private static boolean gt(float a, float b) {
        return a > b;
    }
    
    private static boolean gt(double a, double b) {
        return a > b;
    }
    
    private static boolean gt(boolean a, boolean b) {
        throw new ClassCastException("Cannot apply [>] operation to type [boolean]");    
    }
    
    private static boolean gt(Object left, Object right) {
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
    
    private static boolean gte(int a, int b) {
        return a >= b;
    }
    
    private static boolean gte(long a, long b) {
        return a >= b;
    }
    
    private static boolean gte(float a, float b) {
        return a >= b;
    }
    
    private static boolean gte(double a, double b) {
        return a >= b;
    }
    
    private static boolean gte(boolean a, boolean b) {
        throw new ClassCastException("Cannot apply [>=] operation to type [boolean]");    
    }

    private static boolean gte(Object left, Object right) {
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
    
    private static Class<?> unbox(Class<?> clazz) {
        if (clazz == Boolean.class) {
            return boolean.class;
        } else if (clazz == Byte.class) { 
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
    
    private static Class<?> promote(Class<?> clazz) {
        if (clazz == byte.class || clazz == short.class || clazz == char.class || clazz == int.class) { 
            return int.class; 
        } else { 
            return clazz; 
        } 
    }
      
    private static Class<?> promote(Class<?> a, Class<?> b) {
        if (a == boolean.class && b == boolean.class) {
            return boolean.class;
        }
        if (a.isAssignableFrom(Number.class) == false || b.isAssignableFrom(Number.class) == false) {
            return Object.class;
        }
        
        // ordinary numeric promotion
        if (a == double.class || b == double.class) {
            return double.class;
        } else if (a == float.class || b == float.class) {
            return float.class;
        } else if (a == long.class || b == long.class) {
            return long.class;
        } else {
            return int.class;
        }
    }
    
    private static final Lookup PRIV_LOOKUP = MethodHandles.lookup();

    private static final Map<Class<?>,Map<String,MethodHandle>> TYPE_OP_MAPPING = Collections.unmodifiableMap(
        Stream.of(boolean.class, int.class, long.class, float.class, double.class, Object.class)
            .collect(Collectors.toMap(Function.identity(), type -> {
                try {
                    Map<String,MethodHandle> map = new HashMap<>();
                    MethodType unary = MethodType.methodType(type, type);
                    MethodType binary = MethodType.methodType(type, type, type);
                    MethodType comparison = MethodType.methodType(boolean.class, type, type);
                    Class<?> clazz = PRIV_LOOKUP.lookupClass();
                    map.put("bwnot", PRIV_LOOKUP.findStatic(clazz, "bwnot", unary));
                    map.put("neg",   PRIV_LOOKUP.findStatic(clazz, "neg",   unary));
                    map.put("mul",   PRIV_LOOKUP.findStatic(clazz, "mul", binary));
                    map.put("div",   PRIV_LOOKUP.findStatic(clazz, "div", binary));
                    map.put("rem",   PRIV_LOOKUP.findStatic(clazz, "rem", binary));
                    map.put("add",   PRIV_LOOKUP.findStatic(clazz, "add", binary));
                    map.put("sub",   PRIV_LOOKUP.findStatic(clazz, "sub", binary));
                    map.put("eq",    PRIV_LOOKUP.findStatic(clazz, "eq",  comparison));
                    map.put("lt",    PRIV_LOOKUP.findStatic(clazz, "lt",  comparison));
                    map.put("lte",   PRIV_LOOKUP.findStatic(clazz, "lte", comparison));
                    map.put("gt",    PRIV_LOOKUP.findStatic(clazz, "gt",  comparison));
                    map.put("gte",   PRIV_LOOKUP.findStatic(clazz, "gte", comparison));
                    return map;
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            }))
    );
    
    public static MethodHandle lookupUnary(Class<?> receiverClass, String name) {
        MethodHandle handle = TYPE_OP_MAPPING.get(promote(unbox(receiverClass))).get(name);
        if (handle == null) {
            throw new ClassCastException("Cannot apply operator [" + name + "] to type [" + receiverClass + "]");
        }
        return handle;
    }
    
    public static MethodHandle lookupBinary(Class<?> classA, Class<?> classB, String name) {
        MethodHandle handle = TYPE_OP_MAPPING.get(promote(promote(unbox(classA)), promote(unbox(classB)))).get(name);
        if (handle == null) {
            throw new ClassCastException("Cannot apply operator [" + name + "] to types [" + classA + "] and [" + classB + "]");
        }
        return handle;
    }
    
    public static MethodHandle lookupGeneric(String name) {
        return TYPE_OP_MAPPING.get(Object.class).get(name);
    }
}
