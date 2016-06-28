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

import java.io.ByteArrayOutputStream;

/** basic tests, using try-with resources */
public class SimpleWithResourcesTests extends BaseTestCase {

    /** drops the exception on the floor */
    public int escapes() {
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            assert os != null;
            return Integer.parseInt("bogus");
        } catch (Exception e) {
            return 0;
        }
    }
    
    public void testEscapes() throws Exception {
        check(getClass().getMethod("escapes"), 1, "Escapes without throwing");
    }
    
    /** drops the exception on the floor (sometimes) */
    public int escapesSometimes() throws Exception {
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            assert os != null;
            return Integer.parseInt("bogus");
        } catch (Exception e) {
            if (e.getMessage().equals("ok")) {
                return 0;
            } else {
                throw e;
            }
        }
    }
    
    public void testEscapesSometimes() throws Exception {
        check(getClass().getMethod("escapesSometimes"), 1, "Escapes without throwing");
    }
    
    /** drops the exception on the floor (sometimes, with loop) */
    public int escapesSometimesLoop() throws Exception {
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            assert os != null;
            return Integer.parseInt("bogus");
        } catch (Exception e) {
            while (e != null) {
              throw e;
            }
            return 0;
        }
    }
    
    public void testEscapesSometimesLoop() throws Exception {
        check(getClass().getMethod("escapesSometimesLoop"), 1, "Escapes without throwing");
    }
    
    /** throws something else (does not pass the exception) */
    public int throwsSomethingElse() {
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            assert os != null;
            return Integer.parseInt("bogus");
        } catch (Exception e) {
            throw new NullPointerException();
        }
    }
    
    public void testThrowsSomethingElse() throws Exception {
        check(getClass().getMethod("throwsSomethingElse"), 1, "Throws a different exception");
    }
    
    /** throws exception back directly */
    public int throwsExceptionBack() throws Exception {
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            assert os != null;
            return Integer.parseInt("bogus");
        } catch (RuntimeException e) {
            throw e;
        }
    }
    
    public void testThrowsExceptionBack() throws Exception {
        check(getClass().getMethod("throwsExceptionBack"), 0, null);
    }
    
    /** throws exception boxed in another */
    public int throwsBoxedException() throws Exception {
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            assert os != null;
            return Integer.parseInt("bogus");
        } catch (RuntimeException e) {
            throw new RuntimeException(e);
        }
    }
    
    public void testThrowsBoxedException() throws Exception {
        check(getClass().getMethod("throwsBoxedException"), 0, null);
    }
    
    /** throws exception boxed in another (via initCause) */
    public int throwsBoxedExceptionInitCause() throws Exception {
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            assert os != null;
            return Integer.parseInt("bogus");
        } catch (RuntimeException e) {
            RuntimeException f = new RuntimeException();
            f.initCause(e);
            throw f;
        }
    }
    
    public void testThrowsBoxedExceptionInitCause() throws Exception {
        check(getClass().getMethod("throwsBoxedExceptionInitCause"), 0, null);
    }
    
    /** throws exception boxed in another (via addSuppressed) */
    public int throwsBoxedExceptionAddSuppressed() throws Exception {
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            assert os != null;
            return Integer.parseInt("bogus");
        } catch (RuntimeException e) {
            RuntimeException f = new RuntimeException();
            f.addSuppressed(e);
            throw f;
        }
    }
    
    public void testThrowsBoxedExceptionAddSuppressed() throws Exception {
        check(getClass().getMethod("throwsBoxedExceptionAddSuppressed"), 0, null);
    }
}
