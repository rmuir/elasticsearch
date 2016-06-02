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

import java.util.Objects;

public final class Location {
    private final String sourceName;
    private final int offset;
    
    public Location(String sourceName, int offset) {
        this.sourceName = Objects.requireNonNull(sourceName);
        this.offset = offset;
    }
    
    /**
     *
     */
    public String getSourceName() {
        return sourceName;
    }

    /**
     *
     */
    public int getOffset() {
        return offset;
    }


    public RuntimeException createError(RuntimeException exception) {
        // TODO: add fake frame
        return exception;
    }
    
    public static RuntimeException createErrorStatically(int offset, RuntimeException exception) {
        // TODO: add fake frame
        throw exception;
    }
    

    // This maximum length is theoretically 65535 bytes, but as it's CESU-8 encoded we dont know how large it is in bytes, so be safe
    private static final int MAX_NAME_LENGTH = 256;
    
    /** Computes the file name (mostly important for stacktraces) */
    public static String computeSourceName(String scriptName, String source) {
        StringBuilder fileName = new StringBuilder();
        if (scriptName.equals(PainlessScriptEngineService.INLINE_NAME)) {
            // its an anonymous script, include at least a portion of the source to help identify which one it is
            // but don't create stacktraces with filenames that contain newlines or huge names.

            // truncate to the first newline
            int limit = source.indexOf('\n');
            if (limit >= 0) {
                int limit2 = source.indexOf('\r');
                if (limit2 >= 0) {
                    limit = Math.min(limit, limit2);
                }
            } else {
                limit = source.length();
            }

            // truncate to our limit
            limit = Math.min(limit, MAX_NAME_LENGTH);
            fileName.append(source, 0, limit);

            // if we truncated, make it obvious
            if (limit != source.length()) {
                fileName.append(" ...");
            }
            fileName.append(" @ <inline script>");
        } else {
            // its a named script, just use the name
            // but don't trust this has a reasonable length!
            if (scriptName.length() > MAX_NAME_LENGTH) {
                fileName.append(scriptName, 0, MAX_NAME_LENGTH);
                fileName.append(" ...");
            } else {
                fileName.append(scriptName);
            }
        }
        return fileName.toString();
    }
}
