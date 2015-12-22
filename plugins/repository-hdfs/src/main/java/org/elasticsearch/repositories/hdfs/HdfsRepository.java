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
package org.elasticsearch.repositories.hdfs;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.security.AccessController;
import java.security.Principal;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import javax.security.auth.Subject;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.AbstractFileSystem;
import org.apache.hadoop.fs.FileContext;
import org.apache.hadoop.fs.Path;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchGenerationException;
import org.elasticsearch.SpecialPermission;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.SuppressForbidden;
import org.elasticsearch.common.blobstore.BlobPath;
import org.elasticsearch.common.blobstore.BlobStore;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.index.snapshots.IndexShardRepository;
import org.elasticsearch.repositories.RepositoryName;
import org.elasticsearch.repositories.RepositorySettings;
import org.elasticsearch.repositories.blobstore.BlobStoreRepository;

public final class HdfsRepository extends BlobStoreRepository implements FileContextFactory {

    private final BlobPath basePath;
    private final ByteSizeValue chunkSize;
    private final boolean compress;
    private final RepositorySettings repositorySettings;
    private final String path;
    private final String uri;
    private FileContext fc;
    private HdfsBlobStore blobStore;

    @Inject
    public HdfsRepository(RepositoryName name, RepositorySettings repositorySettings, IndexShardRepository indexShardRepository) throws IOException {
        super(name.getName(), repositorySettings, indexShardRepository);

        this.repositorySettings = repositorySettings;

        uri = repositorySettings.settings().get("uri", settings.get("uri"));
        path = repositorySettings.settings().get("path", settings.get("path"));


        this.basePath = BlobPath.cleanPath();
        this.chunkSize = repositorySettings.settings().getAsBytesSize("chunk_size", settings.getAsBytesSize("chunk_size", null));
        this.compress = repositorySettings.settings().getAsBoolean("compress", settings.getAsBoolean("compress", false));
    }

    @Override
    protected void doStart() {
        if (!Strings.hasText(uri)) {
            throw new IllegalArgumentException("No 'uri' defined for hdfs snapshot/restore");
        }

        URI actualUri = URI.create(uri);
        String scheme = actualUri.getScheme();
        if (!Strings.hasText(scheme) || !scheme.toLowerCase(Locale.ROOT).equals("hdfs")) {
            throw new IllegalArgumentException(
                    String.format(Locale.ROOT, "Invalid scheme [%s] specified in uri [%s]; only 'hdfs' uri allowed for hdfs snapshot/restore", scheme, uri));
        }
        String p = actualUri.getPath();
        if (Strings.hasText(p) && !p.equals("/")) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "Use 'path' option to specify a path [%s], not the uri [%s] for hdfs snapshot/restore", p, uri));
        }

        // get configuration
        if (path == null) {
            throw new IllegalArgumentException("No 'path' defined for hdfs snapshot/restore");
        }
        try {
            fc = getFileContext();
            Path hdfsPath = SecurityUtils.execute(fc, new FcCallback<Path>() {
                @Override
                public Path doInHdfs(FileContext fc) throws IOException {
                    return fc.makeQualified(new Path(path));
                }
            });
            logger.debug("Using file-system [{}] for URI [{}], path [{}]", fc.getDefaultFileSystem(), fc.getDefaultFileSystem().getUri(), hdfsPath);
            blobStore = new HdfsBlobStore(settings, this, hdfsPath);
        } catch (IOException e) {
            throw new ElasticsearchGenerationException(String.format(Locale.ROOT, "Cannot create HDFS repository for uri [%s]", actualUri), e);
        }
        super.doStart();
    }

    // as the FileSystem is long-lived and might go away, make sure to check it before it's being used.
    @Override
    public FileContext getFileContext() throws IOException {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            // unprivileged code such as scripts do not have SpecialPermission
            sm.checkPermission(new SpecialPermission());
        }

        try {
            return AccessController.doPrivileged(new PrivilegedExceptionAction<FileContext>() {
                @Override
                public FileContext run() throws IOException {
                    return doGetFileContext();
                }
            });
        } catch (PrivilegedActionException pae) {
            throw (IOException) pae.getException();
        }
    }

    private FileContext doGetFileContext() throws IOException {
        // check if the fs is still alive
        // make a cheap call that triggers little to no security checks
        if (fc != null) {
            try {
                fc.util().exists(fc.getWorkingDirectory());
            } catch (IOException ex) {
                if (ex.getMessage().contains("Filesystem closed")) {
                    fc = null;
                }
                else {
                    throw ex;
                }
            }
        }
        if (fc == null) {
            return initFileContext(repositorySettings);
        }
        return fc;
    }

    @SuppressForbidden(reason = "lesser of two evils")
    private FileContext initFileContext(RepositorySettings repositorySettings) throws IOException {

        Configuration cfg = new Configuration(repositorySettings.settings().getAsBoolean("load_defaults", settings.getAsBoolean("load_defaults", true)));
        cfg.setClassLoader(this.getClass().getClassLoader());
        cfg.reloadConfiguration();

        Map<String, String> map = repositorySettings.settings().getByPrefix("conf.").getAsMap();
        for (Entry<String, String> entry : map.entrySet()) {
            cfg.set(entry.getKey(), entry.getValue());
        }
        
        // create a hadoop user. if we want other auth, it must be done different anyway, and tested.
        Subject subject;
        try {
            Class<?> clazz = Class.forName("org.apache.hadoop.security.User");
            Constructor<?> ctor = clazz.getConstructor(String.class);
            ctor.setAccessible(true);
            Principal principal = (Principal) ctor.newInstance(System.getProperty("user.name"));
            subject = new Subject(false, Collections.singleton(principal), Collections.emptySet(), Collections.emptySet());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }

        URI actualUri = URI.create(uri);
        // disable FS cache
        cfg.setBoolean("fs.hdfs.impl.disable.cache", true);

        // create the AFS manually since through FileContext is relies on Subject.doAs for no reason at all
        try {
            return Subject.doAs(subject, new PrivilegedExceptionAction<FileContext>() {
                @Override
                public FileContext run() throws IOException {
                    AbstractFileSystem fs = AbstractFileSystem.get(actualUri, cfg);
                    return FileContext.getFileContext(fs, cfg);
                }
            });
        } catch (PrivilegedActionException e) {
            throw (IOException) e.getException();
        }
    }

    @Override
    protected BlobStore blobStore() {
        return blobStore;
    }

    @Override
    protected BlobPath basePath() {
        return basePath;
    }

    @Override
    protected boolean isCompress() {
        return compress;
    }

    @Override
    protected ByteSizeValue chunkSize() {
        return chunkSize;
    }

    @Override
    protected void doClose() throws ElasticsearchException {
        super.doClose();

        // TODO: FileContext does not support any close - is there really no way
        // to handle it?
        fc = null;
    }
}
