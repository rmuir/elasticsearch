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

package org.elasticsearch.plugins;

import org.elasticsearch.common.cli.Terminal;
import org.elasticsearch.common.cli.Terminal.Verbosity;
import org.elasticsearch.env.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.security.Permission;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.security.Policy;
import java.security.URIParameter;
import java.security.UnresolvedPermission;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

class PluginSecurity {
    
    /**
     * Reads plugin policy, prints/confirms exceptions
     */
    static void readPolicy(Path file, Terminal terminal, Environment environment, boolean batch) throws IOException {
        boolean verbose = terminal.verbosity().enabled(Verbosity.VERBOSE);
        PermissionCollection permissions = parsePermissions(terminal, file, environment.tmpFile());
        List<Permission> requested = Collections.list(permissions.elements());
        if (requested.isEmpty()) {
            terminal.print(Verbosity.VERBOSE, "plugin has a policy file with no additional permissions");
            return;
        }
        
        // sort permissions in a reasonable order
        Collections.sort(requested, new Comparator<Permission>() {
            @Override
            public int compare(Permission o1, Permission o2) {
                int cmp = getCategory(o1).compareTo(getCategory(o2));
                if (cmp == 0) {
                    return getDetail(o1).compareTo(getDetail(o2));
                }
                return cmp;
            }
        });
        
        if (batch) {
            terminal.println(Verbosity.NORMAL, "Batch mode, automatically confirming permissions:");
        } else {
            terminal.println(Verbosity.NORMAL, "Confirm permissions:");
        }
        // if the user specifies verbose, just dump the file
        if (verbose) {
            for (String line : Files.readAllLines(file)) {
                terminal.println(Verbosity.VERBOSE, "%s", line);
            }
            terminal.println(Verbosity.VERBOSE, "See http://docs.oracle.com/javase/8/docs/technotes/guides/security/permissions.html for details about permissions.");
        } else {
            // print all permissions grouped by permission category
            String lastCategory = null;
            // track the amount of permission text per category, too high, and we move to the next line.
            int detailCharsWritten = 0;
            for (Permission permission : requested) {
                String category = getCategory(permission);
                String detail = getDetail(permission);
                if (category.equals(lastCategory)) {
                    if (detailCharsWritten > 70) {
                        terminal.println(Verbosity.NORMAL, ",");
                        terminal.print(Verbosity.NORMAL, "  %" + category.length() + "s  ", "");
                        detailCharsWritten = 0;
                    } else {
                        terminal.print(Verbosity.NORMAL, ", ");
                    }
                } else {
                    detailCharsWritten = 0;
                    if (lastCategory != null) {
                        terminal.println();
                    }
                    terminal.print(Verbosity.NORMAL, "* %s: ", category);
                    lastCategory = category;
                }
                detailCharsWritten += detail.length();
                terminal.print(Verbosity.NORMAL, "%s", detail);
            }
            terminal.println();
            terminal.println(Verbosity.NORMAL, "Pass verbose (-v) for full permission details.");
        }
        if (!batch) {
            terminal.println(Verbosity.NORMAL);
            String text = terminal.readText("Continue with installation? [y/N]");
            if (!text.equalsIgnoreCase("y")) {
                throw new RuntimeException("installation aborted by user");
            }
        }
    }

    /** Retrieves the logical category for a permission name */
    static String getCategory(Permission permission) {
        String clazz;
        if (permission instanceof UnresolvedPermission) {
            clazz = ((UnresolvedPermission)permission).getUnresolvedType();
            try {
                // see if we can resolve it: is it one of ours?
                Class.forName(clazz);
                // mark it as ours so there is no confusion
                clazz = clazz.replaceFirst("^org\\.elasticsearch\\.", "[ES] ");
            } catch (ReflectiveOperationException ignored) {
                // no clue what this permission does, full classname and clearly marked
                return "[3RD PARTY] " + clazz;
            }
        } else {
            clazz = permission.getClass().getSimpleName();
        }
        if (clazz.endsWith("Permission")) {
            clazz = clazz.substring(0, clazz.length() - "Permission".length());
        }
        return clazz;
    }

    /** Format permission name and actions into a string */
    static String getDetail(Permission permission) {
        StringBuilder sb = new StringBuilder();
        
        String name = null;
        if (permission instanceof UnresolvedPermission) {
            name = ((UnresolvedPermission) permission).getUnresolvedName();
        } else {
            name = permission.getName();
        }
        if (name != null && name.length() > 0) {
            sb.append(name);
        }
        
        String actions = null;
        if (permission instanceof UnresolvedPermission) {
            actions = ((UnresolvedPermission) permission).getUnresolvedActions();
        } else {
            actions = permission.getActions();
        }
        if (actions != null && actions.length() > 0) {
            sb.append(" (");
            sb.append(actions);
            sb.append(")");
        }
        return sb.toString();
    }
    
    /**
     * Parses plugin policy into a set of permissions
     */
    static PermissionCollection parsePermissions(Terminal terminal, Path file, Path tmpDir) throws IOException {
        // create a zero byte file for "comparison"
        // this is necessary because the default policy impl automatically grants two permissions:
        // 1. permission to exitVM (which we ignore)
        // 2. read permission to the code itself (e.g. jar file of the code)

        Path emptyPolicyFile = Files.createTempFile(tmpDir, "empty", "tmp");
        final Policy emptyPolicy;
        try {
            emptyPolicy = Policy.getInstance("JavaPolicy", new URIParameter(emptyPolicyFile.toUri()));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        PluginManager.tryToDeletePath(terminal, emptyPolicyFile);
        
        // parse the plugin's policy file into a set of permissions
        final Policy policy;
        try {
            policy = Policy.getInstance("JavaPolicy", new URIParameter(file.toUri()));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        PermissionCollection permissions = policy.getPermissions(PluginSecurity.class.getProtectionDomain());
        // this method is supported with the specific implementation we use, but just check for safety.
        if (permissions == Policy.UNSUPPORTED_EMPTY_COLLECTION) {
            throw new UnsupportedOperationException("JavaPolicy implementation does not support retrieving permissions");
        }
        PermissionCollection actualPermissions = new Permissions();
        for (Permission permission : Collections.list(permissions.elements())) {
            if (!emptyPolicy.implies(PluginSecurity.class.getProtectionDomain(), permission)) {
                actualPermissions.add(permission);
            }
        }
        actualPermissions.setReadOnly();
        return actualPermissions;
    }
}
