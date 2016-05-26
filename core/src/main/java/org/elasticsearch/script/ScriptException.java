package org.elasticsearch.script;

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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.XContentBuilder;

public class ScriptException extends ElasticsearchException {
    private final List<String> scriptStack;
    
    public ScriptException(String message, Throwable cause, List<String> scriptStack) {
        super(message, cause);
        this.scriptStack = Objects.requireNonNull(scriptStack);
    }

    public ScriptException(StreamInput in) throws IOException {
        super(in);
        scriptStack = Arrays.asList(in.readStringArray());
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeStringArray(scriptStack.toArray(new String[0]));
    }
    
    @Override
    protected void innerToXContent(XContentBuilder builder, Params params) throws IOException {
        super.innerToXContent(builder, params);
        if (!scriptStack.isEmpty()) {
            renderScriptStack(builder);
        }
    }
    
    void renderScriptStack(XContentBuilder builder) throws IOException {
        builder.field("script_stack", scriptStack);
       /* builder.startArray("script_stack");
        builder.
        for (String element : scriptStack) {
            builder.value(element.toString());
        }
        builder.endArray();*/
    }

    public List<String> getScriptStack() {
        return scriptStack;
    }
}
