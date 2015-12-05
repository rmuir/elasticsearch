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

package org.elasticsearch.script.javascript;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.Scorer;
import org.elasticsearch.SpecialPermission;
import org.elasticsearch.bootstrap.BootstrapInfo;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.script.*;
import org.elasticsearch.script.javascript.support.NativeList;
import org.elasticsearch.script.javascript.support.NativeMap;
import org.elasticsearch.script.javascript.support.ScriptValueConverter;
import org.elasticsearch.search.lookup.LeafSearchLookup;
import org.elasticsearch.search.lookup.SearchLookup;
import org.mozilla.javascript.*;
import org.mozilla.javascript.Script;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.AccessController;
import java.security.CodeSource;
import java.security.PrivilegedAction;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 *
 */
public class JavaScriptScriptEngineService extends AbstractComponent implements ScriptEngineService {

    private final AtomicLong counter = new AtomicLong();

    private static WrapFactory wrapFactory = new CustomWrapFactory();

    private Scriptable globalScope;

    // one time initialization of rhino security manager integration
    private static final CodeSource DOMAIN;
    private static final int OPTIMIZATION_LEVEL = 1;
    
    private static final Set<String> ALLOWED_CLASSES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            // rhino runtime
            org.mozilla.javascript.ContextFactory.class.getName(),
            org.mozilla.javascript.Callable.class.getName(),
            org.mozilla.javascript.NativeFunction.class.getName(),
            org.mozilla.javascript.Script.class.getName(),
            org.mozilla.javascript.ScriptRuntime.class.getName(),
            org.mozilla.javascript.Undefined.class.getName(),
            org.mozilla.javascript.optimizer.OptRuntime.class.getName(),
            // jdk classes
            java.lang.Boolean.class.getName(),
            java.lang.Byte.class.getName(),
            java.lang.Character.class.getName(),
            java.lang.Double.class.getName(),
            java.lang.Integer.class.getName(),
            java.lang.Long.class.getName(),
            java.lang.Math.class.getName(),
            java.lang.Object.class.getName(),
            java.lang.Short.class.getName(),
            java.lang.String.class.getName(),
            java.math.BigDecimal.class.getName(),
            java.util.ArrayList.class.getName(),
            java.util.Arrays.class.getName(),
            java.util.Date.class.getName(),
            java.util.HashMap.class.getName(),
            java.util.HashSet.class.getName(),
            java.util.Iterator.class.getName(),
            java.util.List.class.getName(),
            java.util.Map.class.getName(),
            java.util.Set.class.getName(),
            java.util.UUID.class.getName(),
            // joda-time
            org.joda.time.DateTime.class.getName(),
            org.joda.time.DateTimeUtils.class.getName(),
            org.joda.time.DateTimeZone.class.getName(),
            org.joda.time.Instant.class.getName()
    )));
    
    static {
        try {
            DOMAIN = new CodeSource(new URL("file:" + BootstrapInfo.UNTRUSTED_CODEBASE), (Certificate[]) null);
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
        ContextFactory factory = new ContextFactory() {
            @Override
            protected void onContextCreated(Context cx) {
                cx.setWrapFactory(wrapFactory);
                cx.setOptimizationLevel(OPTIMIZATION_LEVEL);
            }
        };
        factory.initApplicationClassLoader(AccessController.doPrivileged(new PrivilegedAction<ClassLoader>() {
            @Override
            public ClassLoader run() {
                return new ClassLoader(JavaScriptScriptEngineService.class.getClassLoader()) {
                    @Override
                    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                        if (System.getSecurityManager() == null || ALLOWED_CLASSES.contains(name)) {
                            return super.loadClass(name, resolve);
                        } else {
                            throw new ClassNotFoundException(name);
                        }
                    }
                };
            }
        }));
        factory.seal();
        ContextFactory.initGlobal(factory);
        SecurityController.initGlobal(new PolicySecurityController() {
            @Override
            public GeneratedClassLoader createClassLoader(ClassLoader parent, Object securityDomain) {
                // don't let scripts compile other scripts
                SecurityManager sm = System.getSecurityManager();
                if (sm != null) {
                    sm.checkPermission(new SpecialPermission());
                }
                // check the domain, this is all we allow
                if (securityDomain != DOMAIN) {
                    throw new SecurityException("illegal securityDomain: " + securityDomain);
                }
                
                return super.createClassLoader(parent, securityDomain);
            }
        });
    }

    /** ensures this engine is initialized */
    public static void init() {}

    @Inject
    public JavaScriptScriptEngineService(Settings settings) {
        super(settings);

        Context ctx = Context.enter();
        try {
            globalScope = ctx.initStandardObjects(null, true);
        } finally {
            Context.exit();
        }
    }

    @Override
    public void close() {

    }

    @Override
    public void scriptRemoved(@Nullable CompiledScript compiledScript) {
        // Nothing to do here
    }

    @Override
    public String[] types() {
        return new String[]{"js", "javascript"};
    }

    @Override
    public String[] extensions() {
        return new String[]{"js"};
    }

    @Override
    public boolean sandboxed() {
        return false;
    }

    @Override
    public Object compile(String script) {
        Context ctx = Context.enter();
        try {
            return ctx.compileString(script, generateScriptName(), 1, DOMAIN);
        } finally {
            Context.exit();
        }
    }

    @Override
    public ExecutableScript executable(CompiledScript compiledScript, Map<String, Object> vars) {
        Context ctx = Context.enter();
        try {
            Scriptable scope = ctx.newObject(globalScope);
            scope.setPrototype(globalScope);
            scope.setParentScope(null);
            for (Map.Entry<String, Object> entry : vars.entrySet()) {
                ScriptableObject.putProperty(scope, entry.getKey(), entry.getValue());
            }

            return new JavaScriptExecutableScript((Script) compiledScript.compiled(), scope);
        } finally {
            Context.exit();
        }
    }

    @Override
    public SearchScript search(final CompiledScript compiledScript, final SearchLookup lookup, @Nullable final Map<String, Object> vars) {
        Context ctx = Context.enter();
        try {
            final Scriptable scope = ctx.newObject(globalScope);
            scope.setPrototype(globalScope);
            scope.setParentScope(null);

            return new SearchScript() {

              @Override
              public LeafSearchScript getLeafSearchScript(LeafReaderContext context) throws IOException {
                final LeafSearchLookup leafLookup = lookup.getLeafSearchLookup(context);
                for (Map.Entry<String, Object> entry : leafLookup.asMap().entrySet()) {
                    ScriptableObject.putProperty(scope, entry.getKey(), entry.getValue());
                }

                if (vars != null) {
                    for (Map.Entry<String, Object> entry : vars.entrySet()) {
                        ScriptableObject.putProperty(scope, entry.getKey(), entry.getValue());
                    }
                }

                return new JavaScriptSearchScript((Script) compiledScript.compiled(), scope, leafLookup);
              }

              @Override
              public boolean needsScores() {
                  // TODO: can we reliably know if a javascript script makes use of _score
                  return true;
              }
            };
        } finally {
            Context.exit();
        }
    }

    private String generateScriptName() {
        return "Script" + counter.incrementAndGet() + ".js";
    }

    public static class JavaScriptExecutableScript implements ExecutableScript {

        private final Script script;

        private final Scriptable scope;

        public JavaScriptExecutableScript(Script script, Scriptable scope) {
            this.script = script;
            this.scope = scope;
        }

        @Override
        public Object run() {
            Context ctx = Context.enter();
            try {
                return ScriptValueConverter.unwrapValue(script.exec(ctx, scope));
            } finally {
                Context.exit();
            }
        }

        @Override
        public void setNextVar(String name, Object value) {
            ScriptableObject.putProperty(scope, name, value);
        }

        @Override
        public Object unwrap(Object value) {
            return ScriptValueConverter.unwrapValue(value);
        }
    }

    public static class JavaScriptSearchScript implements LeafSearchScript {

        private final Script script;

        private final Scriptable scope;

        private final LeafSearchLookup lookup;

        public JavaScriptSearchScript(Script script, Scriptable scope, LeafSearchLookup lookup) {
            this.script = script;
            this.scope = scope;
            this.lookup = lookup;
        }

        @Override
        public void setScorer(Scorer scorer) {
            Context ctx = Context.enter();
            try {
              ScriptableObject.putProperty(scope, "_score", wrapFactory.wrapAsJavaObject(ctx, scope, new ScoreAccessor(scorer), ScoreAccessor.class));
            } finally {
              Context.exit();
            }
        }

        @Override
        public void setDocument(int doc) {
            lookup.setDocument(doc);
        }

        @Override
        public void setNextVar(String name, Object value) {
            ScriptableObject.putProperty(scope, name, value);
        }

        @Override
        public void setSource(Map<String, Object> source) {
            lookup.source().setSource(source);
        }

        @Override
        public Object run() {
            Context ctx = Context.enter();
            try {
                return ScriptValueConverter.unwrapValue(script.exec(ctx, scope));
            } finally {
                Context.exit();
            }
        }

        @Override
        public float runAsFloat() {
            return ((Number) run()).floatValue();
        }

        @Override
        public long runAsLong() {
            return ((Number) run()).longValue();
        }

        @Override
        public double runAsDouble() {
            return ((Number) run()).doubleValue();
        }

        @Override
        public Object unwrap(Object value) {
            return ScriptValueConverter.unwrapValue(value);
        }
    }

    /**
     * Wrap Factory for Rhino Script Engine
     */
    public static class CustomWrapFactory extends WrapFactory {

        public CustomWrapFactory() {
            setJavaPrimitiveWrap(false); // RingoJS does that..., claims its annoying...
        }

        public Scriptable wrapAsJavaObject(Context cx, Scriptable scope, Object javaObject, Class staticType) {
            if (javaObject instanceof Map) {
                return NativeMap.wrap(scope, (Map) javaObject);
            }
            if (javaObject instanceof List) {
                return NativeList.wrap(scope, (List) javaObject, staticType);
            }
            return super.wrapAsJavaObject(cx, scope, javaObject, staticType);
        }
    }
}
