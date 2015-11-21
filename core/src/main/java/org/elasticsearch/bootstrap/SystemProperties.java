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

import org.elasticsearch.common.SuppressForbidden;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

final class SystemProperties {
    private static Map<String,String> INITIAL_PROPERTIES = null;
    private static final Object LOCK = new Object();
    
    static Map<String,String> getInitialProperties() {
        synchronized (LOCK) {
            if (INITIAL_PROPERTIES == null) {
                snapshotSystemProperties();
            }
            return INITIAL_PROPERTIES;
        }
    }
    
    @SuppressForbidden(reason = "snapshots system properties for settings, monitoring, etc")
    // exposed only for evil tests and evil CLI's that change sysprops
    static void snapshotSystemProperties() {
        synchronized (LOCK) {
            // for JVM info, etc
            Properties sysprops = System.getProperties();
            Map<String,String> m = new HashMap<>();
            for (Map.Entry<Object,Object> entry : sysprops.entrySet()) {
                m.put(Objects.toString(entry.getKey()), Objects.toString(entry.getValue()));
            }
            INITIAL_PROPERTIES = Collections.unmodifiableMap(m);
        }
    }
}
