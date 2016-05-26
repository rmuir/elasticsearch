package org.elasticsearch.painless;

import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.script.ScriptException;

import java.io.IOException;
import java.util.Collections;

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

/** tests for throw/try/catch in painless */
public class TryCatchTests extends ScriptTestCase {

    /** throws an exception */
    public void testThrow() throws IOException {
        ScriptException exception = expectThrows(ScriptException.class, () -> {
            exec("throw new RuntimeException('test')");
        });
        assertEquals("test", exception.getCause().getMessage());
    }
    
    /** catches the exact exception */
    public void testCatch() {
        assertEquals(1, exec("try { if (params.param == 'true') throw new RuntimeException('test'); } " + 
                             "catch (RuntimeException e) { return 1; } return 2;", 
                              Collections.singletonMap("param", "true")));
    }
    
    /** catches superclass of the exception */
    public void testCatchSuperclass() {
        assertEquals(1, exec("try { if (params.param == 'true') throw new RuntimeException('test'); } " + 
                             "catch (Exception e) { return 1; } return 2;", 
                              Collections.singletonMap("param", "true")));
    }
    
    /** tries to catch a different type of exception */
    public void testNoCatch() {
        ScriptException exception = expectThrows(ScriptException.class, () -> {
           exec("try { if (params.param == 'true') throw new RuntimeException('test'); } " + 
                "catch (ArithmeticException e) { return 1; } return 2;", 
                Collections.singletonMap("param", "true"));
        });
        assertEquals("test", exception.getCause().getMessage());
    }
    
    /** throws an exception */
    public void testThrow2() throws IOException {
        ScriptException exception = expectThrows(ScriptException.class, () -> {
            exec("params << 5");
        });
        
        XContentBuilder json = XContentFactory.jsonBuilder().prettyPrint();
        json.startObject();
        exception.toXContent(json, ToXContent.EMPTY_PARAMS);
        json.endObject();
        System.out.println(json.string());
        exception.printStackTrace();
    }
}
