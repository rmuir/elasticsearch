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

package org.elasticsearch.script;

import org.apache.lucene.index.LeafReaderContext;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.search.lookup.SearchLookup;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A dummy script engine used for testing. Scripts must be a number. Running the script
 */
public class MockScriptEngine implements ScriptEngineService {

    public static final String NAME = "mockscript";

    public static final List<String> TYPES = Collections.singletonList(NAME);
    
    /** A compiled script, just holds the scripts name, source, and params that were passed in */
    public static class MockCompiledScript {
        public final String name;
        public final String source;
        public final Map<String,String> params;
        
        MockCompiledScript(String name, String source, Map<String,String> params) {
            this.name = name;
            this.source = source;
            this.params = params;
        }
    }

    public static class TestPlugin extends Plugin {

        public TestPlugin() {
        }

        @Override
        public String name() {
            return NAME;
        }

        @Override
        public String description() {
            return "Mock script engine for integration tests";
        }

        public void onModule(ScriptModule module) {
            module.addScriptEngine(new ScriptEngineRegistry.ScriptEngineRegistration(MockScriptEngine.class, MockScriptEngine.TYPES));
        }

    }

    @Override
    public List<String> getTypes() {
        return TYPES;
    }

    @Override
    public List<String> getExtensions() {
        return TYPES;
    }

    @Override
    public boolean isSandboxed() {
        return true;
    }

    @Override
    public Object compile(String scriptName, String scriptSource, Map<String, String> params) {
        return new MockCompiledScript(scriptName, scriptSource, params);
    }

    @Override
    public ExecutableScript executable(CompiledScript compiledScript, @Nullable Map<String, Object> vars) {
        return new AbstractExecutableScript() {
            @Override
            public Object run() {
                return new BytesArray(((MockCompiledScript)compiledScript.compiled()).source);
            }
        };
    }

    @Override
    public SearchScript search(CompiledScript compiledScript, SearchLookup lookup, @Nullable Map<String, Object> vars) {
        return new SearchScript() {
            @Override
            public LeafSearchScript getLeafSearchScript(LeafReaderContext context) throws IOException {
                AbstractSearchScript leafSearchScript = new AbstractSearchScript() {

                    @Override
                    public Object run() {
                        return compiledScript.compiled();
                    }

                };
                leafSearchScript.setLookup(lookup.getLeafSearchLookup(context));
                return leafSearchScript;
            }

            @Override
            public boolean needsScores() {
                return false;
            }
        };
    }

    @Override
    public void scriptRemoved(@Nullable CompiledScript script) {
    }

    @Override
    public void close() throws IOException {
    }
}
