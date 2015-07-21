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

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterators;

import org.apache.lucene.util.IOUtils;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.admin.cluster.node.info.PluginInfo;
import org.elasticsearch.action.admin.cluster.node.info.PluginsInfo;
import org.elasticsearch.bootstrap.Bootstrap;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.component.LifecycleComponent;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.io.FileSystemUtils;
import org.elasticsearch.common.io.PathUtils;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.env.Environment;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.elasticsearch.common.io.FileSystemUtils.isAccessibleDirectory;

/**
 *
 */
public class PluginsService extends AbstractComponent {
    public static final String ES_PLUGIN_PROPERTIES = "plugin-descriptor.properties";
    public static final String LOAD_PLUGIN_FROM_CLASSPATH = "plugins.load_classpath_plugins";

    public static final String PLUGINS_INFO_REFRESH_INTERVAL_KEY = "plugins.info_refresh_interval";


    private final Environment environment;

    /**
     * We keep around a list of jvm plugins
     */
    private final ImmutableList<Tuple<PluginInfo, Plugin>> plugins;

    private final ImmutableMap<Plugin, List<OnModuleReference>> onModuleReferences;
    private final boolean loadClasspathPlugins;

    private PluginsInfo cachedPluginsInfo;
    private final TimeValue refreshInterval;
    private long lastRefreshNS;

    static class OnModuleReference {
        public final Class<? extends Module> moduleClass;
        public final Method onModuleMethod;

        OnModuleReference(Class<? extends Module> moduleClass, Method onModuleMethod) {
            this.moduleClass = moduleClass;
            this.onModuleMethod = onModuleMethod;
        }
    }

    /**
     * Constructs a new PluginService
     * @param settings The settings of the system
     * @param environment The environment of the system
     */
    public PluginsService(Settings settings, Environment environment) {
        super(settings);
        this.environment = environment;
        this.loadClasspathPlugins = settings.getAsBoolean(LOAD_PLUGIN_FROM_CLASSPATH, true);

        ImmutableList.Builder<Tuple<PluginInfo, Plugin>> tupleBuilder = ImmutableList.builder();

        // first we load all the default plugins from the settings
        String[] defaultPluginsClasses = settings.getAsArray("plugin.types");
        for (String pluginClass : defaultPluginsClasses) {
            Plugin plugin = loadPlugin(pluginClass, settings);
            PluginInfo pluginInfo = new PluginInfo(plugin.name(), plugin.description(), hasSite(plugin.name()), true, PluginInfo.VERSION_NOT_AVAILABLE);
            if (logger.isTraceEnabled()) {
                logger.trace("plugin loaded from settings [{}]", pluginInfo);
            }
            tupleBuilder.add(new Tuple<>(pluginInfo, plugin));
        }

        // now, find all the ones that are in the classpath
        try {
          loadPluginsIntoClassLoader(environment);
        } catch (IOException ex) {
          throw new IllegalStateException("Can't load plugins into classloader", ex);
        }
        if (loadClasspathPlugins) {
            tupleBuilder.addAll(loadPluginsFromClasspath(settings));
        }
        this.plugins = tupleBuilder.build();

        // We need to build a List of jvm and site plugins for checking mandatory plugins
        Map<String, Plugin> jvmPlugins = new HashMap<>();
        List<String> sitePlugins = new ArrayList<>();

        for (Tuple<PluginInfo, Plugin> tuple : this.plugins) {
            jvmPlugins.put(tuple.v2().name(), tuple.v2());
            if (tuple.v1().isSite()) {
                sitePlugins.add(tuple.v1().getName());
            }
        }
        try {
            // we load site plugins
            ImmutableList<Tuple<PluginInfo, Plugin>> tuples = loadSitePlugins();
            for (Tuple<PluginInfo, Plugin> tuple : tuples) {
                sitePlugins.add(tuple.v1().getName());
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Can't load site  plugins", ex);
        }

        // Checking expected plugins
        String[] mandatoryPlugins = settings.getAsArray("plugin.mandatory", null);
        if (mandatoryPlugins != null) {
            Set<String> missingPlugins = new HashSet<>();
            for (String mandatoryPlugin : mandatoryPlugins) {
                if (!jvmPlugins.containsKey(mandatoryPlugin) && !sitePlugins.contains(mandatoryPlugin) && !missingPlugins.contains(mandatoryPlugin)) {
                    missingPlugins.add(mandatoryPlugin);
                }
            }
            if (!missingPlugins.isEmpty()) {
                throw new ElasticsearchException("Missing mandatory plugins [" + Strings.collectionToDelimitedString(missingPlugins, ", ") + "]");
            }
        }

        logger.info("loaded {}, sites {}", jvmPlugins.keySet(), sitePlugins);

        MapBuilder<Plugin, List<OnModuleReference>> onModuleReferences = MapBuilder.newMapBuilder();
        for (Plugin plugin : jvmPlugins.values()) {
            List<OnModuleReference> list = new ArrayList<>();
            for (Method method : plugin.getClass().getMethods()) {
                if (!method.getName().equals("onModule")) {
                    continue;
                }
                if (method.getParameterTypes().length == 0 || method.getParameterTypes().length > 1) {
                    logger.warn("Plugin: {} implementing onModule with no parameters or more than one parameter", plugin.name());
                    continue;
                }
                Class moduleClass = method.getParameterTypes()[0];
                if (!Module.class.isAssignableFrom(moduleClass)) {
                    logger.warn("Plugin: {} implementing onModule by the type is not of Module type {}", plugin.name(), moduleClass);
                    continue;
                }
                list.add(new OnModuleReference(moduleClass, method));
            }
            if (!list.isEmpty()) {
                onModuleReferences.put(plugin, list);
            }
        }
        this.onModuleReferences = onModuleReferences.immutableMap();

        this.refreshInterval = settings.getAsTime(PLUGINS_INFO_REFRESH_INTERVAL_KEY, TimeValue.timeValueSeconds(10));
    }

    public ImmutableList<Tuple<PluginInfo, Plugin>> plugins() {
        return plugins;
    }

    public void processModules(Iterable<Module> modules) {
        for (Module module : modules) {
            processModule(module);
        }
    }

    public void processModule(Module module) {
        for (Tuple<PluginInfo, Plugin> plugin : plugins()) {
            plugin.v2().processModule(module);
            // see if there are onModule references
            List<OnModuleReference> references = onModuleReferences.get(plugin.v2());
            if (references != null) {
                for (OnModuleReference reference : references) {
                    if (reference.moduleClass.isAssignableFrom(module.getClass())) {
                        try {
                            reference.onModuleMethod.invoke(plugin.v2(), module);
                        } catch (Exception e) {
                            logger.warn("plugin {}, failed to invoke custom onModule method", e, plugin.v2().name());
                        }
                    }
                }
            }
        }
    }

    public Settings updatedSettings() {
        Settings.Builder builder = Settings.settingsBuilder()
                .put(this.settings);
        for (Tuple<PluginInfo, Plugin> plugin : plugins) {
            builder.put(plugin.v2().additionalSettings());
        }
        return builder.build();
    }

    public Collection<Class<? extends Module>> modules() {
        List<Class<? extends Module>> modules = new ArrayList<>();
        for (Tuple<PluginInfo, Plugin> plugin : plugins) {
            modules.addAll(plugin.v2().modules());
        }
        return modules;
    }

    public Collection<Module> modules(Settings settings) {
        List<Module> modules = new ArrayList<>();
        for (Tuple<PluginInfo, Plugin> plugin : plugins) {
            modules.addAll(plugin.v2().modules(settings));
        }
        return modules;
    }

    public Collection<Class<? extends LifecycleComponent>> services() {
        List<Class<? extends LifecycleComponent>> services = new ArrayList<>();
        for (Tuple<PluginInfo, Plugin> plugin : plugins) {
            services.addAll(plugin.v2().services());
        }
        return services;
    }

    public Collection<Class<? extends Module>> indexModules() {
        List<Class<? extends Module>> modules = new ArrayList<>();
        for (Tuple<PluginInfo, Plugin> plugin : plugins) {
            modules.addAll(plugin.v2().indexModules());
        }
        return modules;
    }

    public Collection<Module> indexModules(Settings settings) {
        List<Module> modules = new ArrayList<>();
        for (Tuple<PluginInfo, Plugin> plugin : plugins) {
            modules.addAll(plugin.v2().indexModules(settings));
        }
        return modules;
    }

    public Collection<Class<? extends Closeable>> indexServices() {
        List<Class<? extends Closeable>> services = new ArrayList<>();
        for (Tuple<PluginInfo, Plugin> plugin : plugins) {
            services.addAll(plugin.v2().indexServices());
        }
        return services;
    }

    public Collection<Class<? extends Module>> shardModules() {
        List<Class<? extends Module>> modules = new ArrayList<>();
        for (Tuple<PluginInfo, Plugin> plugin : plugins) {
            modules.addAll(plugin.v2().shardModules());
        }
        return modules;
    }

    public Collection<Module> shardModules(Settings settings) {
        List<Module> modules = new ArrayList<>();
        for (Tuple<PluginInfo, Plugin> plugin : plugins) {
            modules.addAll(plugin.v2().shardModules(settings));
        }
        return modules;
    }

    public Collection<Class<? extends Closeable>> shardServices() {
        List<Class<? extends Closeable>> services = new ArrayList<>();
        for (Tuple<PluginInfo, Plugin> plugin : plugins) {
            services.addAll(plugin.v2().shardServices());
        }
        return services;
    }

    /**
     * Get information about plugins (jvm and site plugins).
     * Information are cached for 10 seconds by default. Modify `plugins.info_refresh_interval` property if needed.
     * Setting `plugins.info_refresh_interval` to `-1` will cause infinite caching.
     * Setting `plugins.info_refresh_interval` to `0` will disable caching.
     * @return List of plugins information
     */
    synchronized public PluginsInfo info() {
        if (refreshInterval.millis() != 0) {
            if (cachedPluginsInfo != null &&
                    (refreshInterval.millis() < 0 || (System.nanoTime() - lastRefreshNS) < refreshInterval.nanos())) {
                if (logger.isTraceEnabled()) {
                    logger.trace("using cache to retrieve plugins info");
                }
                return cachedPluginsInfo;
            }
            lastRefreshNS = System.nanoTime();
        }

        if (logger.isTraceEnabled()) {
            logger.trace("starting to fetch info on plugins");
        }
        cachedPluginsInfo = new PluginsInfo();

        // We first add all JvmPlugins
        for (Tuple<PluginInfo, Plugin> plugin : this.plugins) {
            if (logger.isTraceEnabled()) {
                logger.trace("adding jvm plugin [{}]", plugin.v1());
            }
            cachedPluginsInfo.add(plugin.v1());
        }

        try {
            // We reload site plugins (in case of some changes)
            for (Tuple<PluginInfo, Plugin> plugin : loadSitePlugins()) {
                if (logger.isTraceEnabled()) {
                    logger.trace("adding site plugin [{}]", plugin.v1());
                }
                cachedPluginsInfo.add(plugin.v1());
            }
        } catch (IOException ex) {
            logger.warn("can load site plugins info", ex);
        }

        return cachedPluginsInfo;
    }

    static final String PLUGIN_LIB_PATTERN = "glob:**.{jar,zip}";
    private static void loadPluginsIntoClassLoader(Environment environment) throws IOException {
        ESLogger logger = Loggers.getLogger(Bootstrap.class);

        Path pluginsDirectory = environment.pluginsFile();
        if (!isAccessibleDirectory(pluginsDirectory, logger)) {
            return;
        }

        // note: there's only one classloader here, but Uwe gets upset otherwise.
        ClassLoader classLoader = Bootstrap.class.getClassLoader();
        Class<?> classLoaderClass = classLoader.getClass();
        Method addURL = null;
        while (!classLoaderClass.equals(Object.class)) {
            try {
                addURL = classLoaderClass.getDeclaredMethod("addURL", URL.class);
                addURL.setAccessible(true);
                break;
            } catch (NoSuchMethodException e) {
                // no method, try the parent
                classLoaderClass = classLoaderClass.getSuperclass();
            }
        }

        if (addURL == null) {
            logger.debug("failed to find addURL method on classLoader [" + classLoader + "] to add methods");
            return;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginsDirectory)) {

            for (Path plugin : stream) {
                // We check that subdirs are directories and readable
                if (!isAccessibleDirectory(plugin, logger)) {
                    continue;
                }

                logger.trace("--- adding plugin [{}]", plugin.toAbsolutePath());

                try {
                    // add the root
                    addURL.invoke(classLoader, plugin.toUri().toURL());
                    // gather files to add
                    List<Path> libFiles = new ArrayList<>();
                    libFiles.addAll(Arrays.asList(files(plugin)));
                    Path libLocation = plugin.resolve("lib");
                    if (Files.isDirectory(libLocation)) {
                        libFiles.addAll(Arrays.asList(files(libLocation)));
                    }

                    PathMatcher matcher = PathUtils.getDefaultFileSystem().getPathMatcher(PLUGIN_LIB_PATTERN);

                    // if there are jars in it, add it as well
                    for (Path libFile : libFiles) {
                        if (!matcher.matches(libFile)) {
                            continue;
                        }
                        addURL.invoke(classLoader, libFile.toUri().toURL());
                    }
                } catch (Throwable e) {
                    logger.warn("failed to add plugin [" + plugin + "]", e);
                }
            }
        }
    }

    private static Path[] files(Path from) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(from)) {
            return Iterators.toArray(stream.iterator(), Path.class);
        }
    }

    private List<Tuple<PluginInfo,Plugin>> loadPluginsFromClasspath(Settings settings) {
        ImmutableList.Builder<Tuple<PluginInfo, Plugin>> plugins = ImmutableList.builder();

        // Trying JVM plugins: looking for es-plugin.properties files
        try {
            Enumeration<URL> pluginUrls = settings.getClassLoader().getResources(ES_PLUGIN_PROPERTIES);

            // use a set for uniqueness as some classloaders such as groovy's can return the same URL multiple times and
            // these plugins should only be loaded once
            HashSet<URL> uniqueUrls = new HashSet<>(Collections.list(pluginUrls));
            for (URL pluginUrl : uniqueUrls) {
                Properties pluginProps = new Properties();
                InputStream is = null;
                try {
                    is = pluginUrl.openStream();
                    pluginProps.load(is);
                    String pluginClassName = pluginProps.getProperty("plugin");
                    String pluginVersion = pluginProps.getProperty("version", PluginInfo.VERSION_NOT_AVAILABLE);
                    Plugin plugin = loadPlugin(pluginClassName, settings);

                    // Is it a site plugin as well? Does it have also an embedded _site structure
                    Path siteFile = environment.pluginsFile().resolve(plugin.name()).resolve("_site");
                    boolean isSite = isAccessibleDirectory(siteFile, logger);
                    if (logger.isTraceEnabled()) {
                        logger.trace("found a jvm plugin [{}], [{}]{}",
                                plugin.name(), plugin.description(), isSite ? ": with _site structure" : "");
                    }

                    PluginInfo pluginInfo = new PluginInfo(plugin.name(), plugin.description(), isSite, true, pluginVersion);

                    plugins.add(new Tuple<>(pluginInfo, plugin));
                } catch (Throwable e) {
                    logger.warn("failed to load plugin from [" + pluginUrl + "]", e);
                } finally {
                    IOUtils.closeWhileHandlingException(is);
                }
            }
        } catch (IOException e) {
            logger.warn("failed to find jvm plugins from classpath", e);
        }

        return plugins.build();
    }

    private ImmutableList<Tuple<PluginInfo,Plugin>> loadSitePlugins() throws IOException {
        ImmutableList.Builder<Tuple<PluginInfo, Plugin>> sitePlugins = ImmutableList.builder();
        List<String> loadedJvmPlugins = new ArrayList<>();

        // Already known jvm plugins are ignored
        for(Tuple<PluginInfo, Plugin> tuple : plugins) {
            if (tuple.v1().isSite()) {
                loadedJvmPlugins.add(tuple.v1().getName());
            }
        }

        // Let's try to find all _site plugins we did not already found
        Path pluginsFile = environment.pluginsFile();

        if (FileSystemUtils.isAccessibleDirectory(pluginsFile, logger) == false) {
            return sitePlugins.build();
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginsFile)) {
            for (Path pluginFile : stream) {
                if (!loadedJvmPlugins.contains(pluginFile.getFileName().toString())) {
                    Path sitePluginDir = pluginFile.resolve("_site");
                    if (isAccessibleDirectory(sitePluginDir, logger)) {
                        // We have a _site plugin. Let's try to get more information on it
                        String name = pluginFile.getFileName().toString();
                        String version = PluginInfo.VERSION_NOT_AVAILABLE;
                        String description = PluginInfo.DESCRIPTION_NOT_AVAILABLE;

                        // We check if es-plugin.properties exists in plugin/_site dir
                        final Path pluginPropFile = sitePluginDir.resolve(ES_PLUGIN_PROPERTIES);
                        if (Files.exists(pluginPropFile)) {

                            final Properties pluginProps = new Properties();
                            try (final BufferedReader reader = Files.newBufferedReader(pluginPropFile, Charsets.UTF_8)) {
                                pluginProps.load(reader);
                                description = pluginProps.getProperty("description", PluginInfo.DESCRIPTION_NOT_AVAILABLE);
                                version = pluginProps.getProperty("version", PluginInfo.VERSION_NOT_AVAILABLE);
                            } catch (Exception e) {
                                // Can not load properties for this site plugin. Ignoring.
                                logger.debug("can not load {} file.", e, ES_PLUGIN_PROPERTIES);
                            }
                        }

                        if (logger.isTraceEnabled()) {
                            logger.trace("found a site plugin name [{}], version [{}], description [{}]",
                                    name, version, description);
                        }
                        sitePlugins.add(new Tuple<PluginInfo, Plugin>(new PluginInfo(name, description, true, false, version), null));
                    }
                }
            }
        }
        return sitePlugins.build();
    }

    /**
     * @param name plugin name
     * @return if this jvm plugin has also a _site structure
     */
    private boolean hasSite(String name) {
        // Let's try to find all _site plugins we did not already found
        Path pluginsFile = environment.pluginsFile();

        if (!Files.isDirectory(pluginsFile)) {
            return false;
        }

        Path sitePluginDir = pluginsFile.resolve(name).resolve("_site");
        return isAccessibleDirectory(sitePluginDir, logger);
    }

    private Plugin loadPlugin(String className, Settings settings) {
        try {
            Class<? extends Plugin> pluginClass = settings.getClassLoader().loadClass(className).asSubclass(Plugin.class);

            try {
                return pluginClass.getConstructor(Settings.class).newInstance(settings);
            } catch (NoSuchMethodException e) {
                try {
                    return pluginClass.getConstructor().newInstance();
                } catch (NoSuchMethodException e1) {
                    throw new ElasticsearchException("No constructor for [" + pluginClass + "]. A plugin class must " +
                            "have either an empty default constructor or a single argument constructor accepting a " +
                            "Settings instance");
                }
            }

        } catch (Throwable e) {
            throw new ElasticsearchException("Failed to load plugin class [" + className + "]", e);
        }
    }
}
