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

import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Properties;

final class SystemProperties {
    private static Dictionary<Object,Object> INITIAL_PROPERTIES = null;
    private static final Object LOCK = new Object();
    
    static Dictionary<Object,Object> getInitialProperties() {
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
            final Properties sysprops = System.getProperties();
            INITIAL_PROPERTIES = new Dictionary<Object,Object>() {

                @Override
                public int size() {
                    return sysprops.size();
                }

                @Override
                public boolean isEmpty() {
                    return sysprops.isEmpty();
                }

                @Override
                public Enumeration<Object> keys() {
                    return sysprops.keys();
                }

                @Override
                public Enumeration<Object> elements() {
                    return sysprops.elements();
                }

                @Override
                public Object get(Object key) {
                    return sysprops.get(key);
                }

                @Override
                public Object put(Object key, Object value) {
                    throw new UnsupportedOperationException("collection is read-only");
                }

                @Override
                public Object remove(Object key) {
                    throw new UnsupportedOperationException("collection is read-only");
                }
            };
        }
    }
}
