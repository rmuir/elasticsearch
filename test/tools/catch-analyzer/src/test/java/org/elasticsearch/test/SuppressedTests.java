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

public class SuppressedTests extends BaseTestCase {
    /** drops the exception on the floor */
    @SwallowsExceptions
    public int escapes() {
        try {
            return Integer.parseInt("bogus");
        } catch (Exception e) {
            return 0;
        }
    }
    
    public void testEscapes() throws Exception {
        assertOK(getClass().getMethod("escapes"));
    }
    
    /** drops the exception on the floor */
    @SwallowsExceptions
    public int escapesWithResources() throws Exception {
        try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
            assert stream != null;
            return Integer.parseInt("bogus");
        } catch (Exception e) {
            return 0;
        }
    }
    
    public void testEscapesWithResources() throws Exception {
        assertOK(getClass().getMethod("escapesWithResources"));
    }
    
    /** does the right thing, but annotated wrong */
    @SwallowsExceptions
    public int actuallyThrows() throws Exception {
        try {
            return Integer.parseInt("bogus");
        } catch (Exception e) {
            throw e;
        }
    }
    
    public void testActuallyThrows() throws Exception {
        assertFails(getClass().getMethod("actuallyThrows"), "Does not swallow any exception");
    }
    
    /** does the right thing, but annotated wrong */
    @SwallowsExceptions
    public int actuallyThrowsWithResources() throws Exception {
        try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
            assert stream != null;
            return Integer.parseInt("bogus");
        } catch (Exception e) {
            throw e;
        }
    }
    
    public void testActuallyThrowsWithResources() throws Exception {
        assertFails(getClass().getMethod("actuallyThrowsWithResources"), "Does not swallow any exception");
    }
}
