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

package org.elasticsearch.plugin.example;

import org.elasticsearch.common.component.LifecycleComponent;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.repositories.RepositoriesModule;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collection;

/**
 *
 */
public class ExampleJvmPlugin implements Plugin {

    private final Settings settings;

    public ExampleJvmPlugin(Settings settings) {
        this.settings = settings;
    }

    @Override
    public String name() {
        return "uber-plugin";
    }

    @Override
    public String description() {
        return "A plugin that extends all extension points";
    }

    @Override
    public Collection<Class<? extends Module>> modules() {
        return null;
    }

    @Override
    public Collection<Module> modules(Settings settings) {
        Collection<Module> modules = new ArrayList<>();
        return modules;
    }

    @Override
    public Collection<Class<? extends LifecycleComponent>> services() {
        Collection<Class<? extends LifecycleComponent>> services = new ArrayList<>();
        if (settings.getAsBoolean("cloud.enabled", true)) {
            services.add(AwsModule.getS3ServiceClass(settings));
            services.add(AwsEc2Service.class);
        }
        return services;
    }

    @Override
    public Collection<Class<? extends Module>> indexModules() {
        return null;
    }

    @Override
    public Collection<? extends Module> indexModules(Settings settings) {
        return null;
    }

    @Override
    public Collection<Class<? extends Closeable>> indexServices() {
        return null;
    }

    @Override
    public Collection<Class<? extends Module>> shardModules() {
        return null;
    }

    @Override
    public Collection<? extends Module> shardModules(Settings settings) {
        return null;
    }

    @Override
    public Collection<Class<? extends Closeable>> shardServices() {
        return null;
    }

    @Override
    public void processModule(Module module) {

    }

    @Override
    public Settings additionalSettings() {
        return null;
    }

    public void onModule(RepositoriesModule repositoriesModule) {
        if (settings.getAsBoolean("cloud.enabled", true)) {
            repositoriesModule.registerRepository(S3Repository.TYPE, S3RepositoryModule.class);
        }
    }


}
