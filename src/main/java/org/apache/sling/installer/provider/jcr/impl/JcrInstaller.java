/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.installer.provider.jcr.impl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Dictionary;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.observation.Event;
import javax.jcr.observation.EventIterator;
import javax.jcr.observation.EventListener;

import org.apache.sling.installer.api.InstallableResource;
import org.apache.sling.installer.api.OsgiInstaller;
import org.apache.sling.installer.api.UpdateHandler;
import org.apache.sling.installer.api.UpdateResult;
import org.apache.sling.installer.api.serializer.ConfigurationSerializer;
import org.apache.sling.installer.api.serializer.ConfigurationSerializerFactory;
import org.apache.sling.jcr.api.SlingRepository;
import org.apache.sling.serviceusermapping.ServiceUserMapped;
import org.apache.sling.settings.SlingSettingsService;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main class of jcrinstall, runs as a service, observes the
 * repository for changes in folders having names that match
 * configurable regular expressions, and registers resources
 * found in those folders with the OSGi installer for installation.
 */
@Component(immediate = true, service = UpdateHandler.class, property = Constants.SERVICE_RANKING+":Integer=100")
@Designate(ocd = JcrInstaller.Configuration.class)
public class JcrInstaller implements UpdateHandler {

    public static final long RUN_LOOP_DELAY_MSEC = 500L;
    public static final String URL_SCHEME = "jcrinstall";

    private final Logger logger = LoggerFactory.getLogger(getClass());

    /** Counters, used for statistics and testing */
    private final AtomicLongArray counters = new AtomicLongArray(COUNTERS_COUNT);
    public static final int SCAN_FOLDERS_COUNTER = 0;
    public static final int UPDATE_FOLDERS_LIST_COUNTER = 1;
    public static final int RUN_LOOP_COUNTER = 2;
    public static final int COUNTERS_COUNT = 3;
    
    private static final String NT_FILE = "nt:file";
    private static final String NT_RESOURCE = "nt:resource";
    private static final String PROP_DATA = "jcr:data";
    private static final String PROP_MODIFIED = "jcr:lastModified";
    private static final String PROP_ENC = "jcr:encoding";
    private static final String PROP_MIME = "jcr:mimeType";
    private static final String MIME_TXT = "text/plain";
    private static final String ENCODING = "UTF-8";

    private static final String CONFIG_FILE_EXTENSION = ".cfg.json";

    @ObjectClassDefinition(id = "org.apache.sling.installer.provider.jcr.impl.JcrInstaller", name = "Apache Sling JCR Installer", description = "Installs OSGi bundles and configurations found in the JCR Repository.")
    static @interface Configuration {
        @AttributeDefinition(name = "Schemes", description = "For these schemes this installer writes back configurations.")
        String[] handler_schemes() default JcrInstaller.URL_SCHEME;

        @AttributeDefinition(name = "Installation folders name regexp", description = "JCRInstall looks in repository folders having a name that match this regular expression (under the root paths, which are defined by the ResourceResolver search path) for resources to install. Folders having names that match this expression, followed by dotted run mode selectors (like \"install.author.production\") are also included.")
        String sling_jcrinstall_folder_name_regexp() default ".*/(install|config)$";

        @AttributeDefinition(name = "Max hierarchy depth of install folders", description = "Folders that are nested deeper than this value under the repository root are ignored")
        int sling_jcrinstall_folder_max_depth() default 4;

        @AttributeDefinition(name = "New Config Path", description = "New configurations are stored at this location. If this path is relative, the resource resolver search path with highest priority is prepended. Otherwise this path is used as is.")
        String sling_jcrinstall_new_config_path() default "sling/install";

        @AttributeDefinition(name = "Search Path", description = "List of paths under which jcrinstall looks for installable resources. Combined with the installations folders name regexp to select folders for scanning. Each path is followed by a colon and the priority of resources found under that path, resources with higher values override resources with lower values which represent the same OSGi entity (configuration, bundle, etc).")
        String[] sling_jcrinstall_search_path() default { "/libs:100", "/apps:200" };

        @AttributeDefinition(name = "Signal Node Path", description = "Path of the node in repository whose children would be watched for determining if the watch folder scanning has to be performed or not. If any child node is found at this path then scanning would be paused.")
        String sling_jcrinstall_signal_path() default "/system/sling/installer/jcr/pauseInstallation";

        @AttributeDefinition(name = "Enable Write Back", description = "Enable writing back of changes done through other tools like writing back configurations changed in the web console etc.")
        boolean sling_jcrinstall_enable_writeback() default true;
    }

    private volatile boolean pauseMessageLogged = false;

    /**
     * This class watches the repository for installable resources
     */
    @Reference
    private SlingRepository repository;

    /**
     * Additional installation folders are activated based
     * on the current RunMode. For example, /libs/foo/install.dev
     * if the current run mode is "dev".
     */
    @Reference
    private SlingSettingsService settings;

    /**
     * The OsgiInstaller installs resources in the OSGi framework.
     */
    @Reference
    private OsgiInstaller installer;

    @Reference
    private ServiceUserMapped serviceUserMapped;

    /** Convert Nodes to InstallableResources */
    static interface NodeConverter {
        InstallableResource convertNode(Node n, int priority)
                throws RepositoryException;
    }

    /** Timer used to call updateFoldersList() */
    private final RescanTimer updateFoldersListTimer = new RescanTimer();

    /** Thread that can be cleanly stopped with a flag */
    private final static AtomicInteger bgThreadCounter = new AtomicInteger();

    class StoppableThread extends Thread implements EventListener {

        /** Used for synchronizing. */
        final Object lock = new Object();

        final AtomicBoolean active = new AtomicBoolean(false);

        private final AtomicBoolean running = new AtomicBoolean(false);

        private final InstallerConfig cfg;

        /** Detect newly created folders that we must watch */
        private final List<RootFolderListener> listeners = new LinkedList<RootFolderListener>();

        private volatile RootFolderMoveListener moveEventListener;

        /** Session shared by all WatchedFolder */
        private volatile Session session;

        StoppableThread(final InstallerConfig cfg) throws RepositoryException {
            this.cfg = cfg;
            setName("JcrInstaller." + String.valueOf(bgThreadCounter.incrementAndGet()));
            setDaemon(true);

            try {
                // open session
                session = repository.loginService(/* subservice name */null, repository.getDefaultWorkspace());

                for (final String path : cfg.getRoots()) {
                    listeners.add(new RootFolderListener(session, path, updateFoldersListTimer, cfg));
                    logger.debug("Configured root folder: {}", path);
                }

                // Watch for events on the root - that might be one of our root folders
                session.getWorkspace().getObservationManager().addEventListener(this,
                        Event.NODE_ADDED | Event.NODE_REMOVED,
                        "/",
                        false, // isDeep
                        null,
                        null,
                        true); // noLocal
                // add special observation listener for move events
                if (cfg.getRoots() != null && cfg.getRoots().length > 0) {
                    moveEventListener = new RootFolderMoveListener(session, cfg.getRoots(), updateFoldersListTimer);
                }

                logger.debug("Watching for node events on / to detect removal/add of our root folders");

                // Find paths to watch and create WatchedFolders to manage them
                for (final String root : cfg.getRoots()) {
                    findPathsToWatch(cfg, session, root);
                }

                // Scan watchedFolders and register resources with installer
                final List<InstallableResource> resources = cfg.scanWatchedFolders();
                logger.debug("Registering {} resources with OSGi installer: {}", resources.size(), resources);
                installer.registerResources(URL_SCHEME, resources.toArray(new InstallableResource[resources.size()]));
                this.active.set(true);
            } finally {
                if (!this.active.get()) {
                    shutdown();
                }
            }
        }

        public void shutdown() {
            while (running.get()) {
                try {
                    Thread.sleep(10);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            try {
                if (session != null) {
                    for (final RootFolderListener wfc : listeners) {
                        wfc.cleanup(session);
                    }
                    session.getWorkspace().getObservationManager().removeEventListener(this);
                    if (moveEventListener != null) {
                        moveEventListener.cleanup(session);
                        moveEventListener = null;
                    }
                }
            } catch (final RepositoryException e) {
                logger.warn("Exception in stop()", e);
            }
            if (session != null) {
                session.logout();
                session = null;
            }
            listeners.clear();
        }

        /**
         * @see javax.jcr.observation.EventListener#onEvent(javax.jcr.observation.EventIterator)
         */
        public void onEvent(final EventIterator it) {
            // Got a DELETE or ADD on root - schedule folders rescan if one
            // of our root folders is impacted
            try {
                while (it.hasNext()) {
                    final Event e = it.nextEvent();
                    logger.debug("Got event {}", e);

                    this.checkChanges(e.getPath());
                }
            } catch (final RepositoryException re) {
                logger.warn("RepositoryException in onEvent", re);
            }
        }

        /**
         * Check for changes in any of the root folders
         */
        private void checkChanges(final String path) {
            for (final String root : cfg.getRoots()) {
                if (path.startsWith(root)) {
                    logger.info("Got event for root {}, scheduling scanning of new folders", root);
                    updateFoldersListTimer.scheduleScan();
                }
            }
        }

        @Override
        public final void run() {
            logger.info("Background thread {} starting", Thread.currentThread().getName());
            while (this.active.get()) {
                running.set(true);
                try {
                    runOneCycle(cfg, session);
                } finally {
                    running.set(false);
                }
            }
            logger.info("Background thread {} done", Thread.currentThread().getName());
            counters.set(RUN_LOOP_COUNTER, -1);
        }

        public InstallerConfig getConfiguration() {
            return this.cfg;
        }
    };
    private volatile StoppableThread backgroundThread;

    /**
     * Activate this component.
     * @throws RepositoryException
     */
    @Activate
    protected void activate(final Configuration configuration) throws RepositoryException {
        logger.info("Activating Apache Sling JCR Installer");
        final InstallerConfig cfg = new InstallerConfig(logger, configuration, settings);

        this.backgroundThread = new StoppableThread(cfg);
        backgroundThread.start();
    }

    /**
     * Deactivate this component
     */
    @Deactivate
    protected void deactivate() {
        logger.info("Deactivating Apache Sling JCR Installer");

        if (backgroundThread != null) {
            synchronized (backgroundThread.lock) {
                backgroundThread.active.set(false);
                backgroundThread.lock.notify();
            }
            logger.debug("Waiting for " + backgroundThread.getName() + " Thread to end...");

            this.backgroundThread.shutdown();
            this.backgroundThread = null;
        }
    }

    /** Find the paths to watch under rootPath, according to our folderNameFilter,
     *  and add them to result */
    private void findPathsToWatch(final InstallerConfig cfg, final Session session,
            final String rootPath) throws RepositoryException {
        Session s = null;

        try {
            s = repository.loginService(/* subservice name */null, repository.getDefaultWorkspace());
            if (!s.itemExists(rootPath) || !s.getItem(rootPath).isNode()) {
                logger.info("Bundles root node {} not found, ignored", rootPath);
            } else {
                logger.debug("Bundles root node {} found, looking for bundle folders inside it", rootPath);
                final Node n = (Node) s.getItem(rootPath);
                findPathsUnderNode(cfg, session, n);
            }
        } finally {
            if (s != null) {
                s.logout();
            }
        }
    }

    /**
     * Add n to result if it is a folder that we must watch, and recurse into its children
     * to do the same.
     */
    void findPathsUnderNode(final InstallerConfig cfg, final Session session,
            final Node n) throws RepositoryException {
        final String path = n.getPath();
        final int priority = cfg.getFolderNameFilter().getPriority(path);
        if (priority > 0) {
            cfg.addWatchedFolder(new WatchedFolder(session, path, priority, cfg.getConverters()));
        }
        final int depth = path.split("/").length;
        if (depth > cfg.getMaxWatchedFolderDepth()) {
            logger.debug("Not recursing into {} due to maxWatchedFolderDepth={}", path, cfg.getMaxWatchedFolderDepth());
            return;
        }
        final NodeIterator it = n.getNodes();
        while (it.hasNext()) {
            findPathsUnderNode(cfg, session, it.nextNode());
        }
    }

    /**
     * Add new folders to watch if any have been detected
     * @return a list of InstallableResource that must be unregistered,
     *  	for folders that have been removed
     */
    private List<String> updateFoldersList(final InstallerConfig cfg, final Session session) throws Exception {
        logger.debug("Updating folder list.");

        for (final String root : cfg.getRoots()) {
            findPathsToWatch(cfg, session, root);
        }

        // Check all WatchedFolder, in case some were deleted
        final List<String> removedResources = cfg.checkForRemovedWatchedFolders(session);

        return removedResources;
    }

    InstallerConfig getConfiguration() {
        InstallerConfig cfg = null;
        final StoppableThread st = this.backgroundThread;
        if (st != null) {
            cfg = st.getConfiguration();
        }
        return cfg;
    }

    Session getSession() {
        return this.backgroundThread.session;
    }

    /**
     * Run periodic scans of our watched folders, and watch for folders creations/deletions.
     */
    public void runOneCycle(final InstallerConfig cfg, final Session session) {
        logger.debug("Running watch cycle.");

        try {
            boolean didRefresh = false;

            if (cfg.anyWatchFolderNeedsScan()) {
                session.refresh(false);
                didRefresh = true;
                if (scanningIsPaused(cfg, session)) {
                    if (!pauseMessageLogged) {
                        // Avoid flooding the logs every 500 msec so log at info level once
                        logger.info("Detected signal for pausing the JCR Provider i.e. child nodes found under path {}. " +
                                "JCR Provider scanning would not be performed", cfg.getPauseScanNodePath());
                        pauseMessageLogged = true;
                    }

                    try {
                        Thread.sleep(JcrInstaller.RUN_LOOP_DELAY_MSEC);
                    } catch (InterruptedException ignored) {
                        logger.debug("InterruptedException in scanningIsPaused block");
                    }

                    return;
                } else if (pauseMessageLogged) {
                    pauseMessageLogged = false;
                }
            }

            // Rescan WatchedFolders if needed
            boolean scanWf = false;
            for (final WatchedFolder wf : cfg.cloneWatchedFolders()) {
                if (!wf.needsScan()) {
                    continue;
                }
                scanWf = true;
                if (!didRefresh) {
                    session.refresh(false);
                    didRefresh = true;
                }
                counters.incrementAndGet(SCAN_FOLDERS_COUNTER);

                final WatchedFolder.ScanResult sr = wf.scan();
                boolean toDo = false;
                if (sr.toAdd.size() > 0) {
                    logger.info("Registering resource with OSGi installer: {}", sr.toAdd);
                    toDo = true;
                }
                if (sr.toRemove.size() > 0) {
                    logger.info("Removing resource from OSGi installer: {}", sr.toRemove);
                    toDo = true;
                }
                if (toDo) {
                    installer.updateResources(URL_SCHEME, sr.toAdd.toArray(new InstallableResource[sr.toAdd.size()]),
                            sr.toRemove.toArray(new String[sr.toRemove.size()]));
                }
            }

            // Update list of WatchedFolder if we got any relevant events,
            // or if there were any WatchedFolder events
            if (scanWf || updateFoldersListTimer.expired()) {
                if (!didRefresh) {
                    session.refresh(false);
                    didRefresh = true;
                }
                updateFoldersListTimer.reset();
                counters.incrementAndGet(UPDATE_FOLDERS_LIST_COUNTER);
                final List<String> toRemove = updateFoldersList(cfg, session);
                if (toRemove.size() > 0) {
                    logger.info("Removing resource from OSGi installer (folder deleted): {}", toRemove);
                    installer.updateResources(URL_SCHEME, null,
                            toRemove.toArray(new String[toRemove.size()]));
                }
            }


        } catch (final Exception e) {
            logger.warn("Exception in runOneCycle()", e);
        }

        if (backgroundThread.active.get()) {
            synchronized (backgroundThread.lock) {
                try {
                    backgroundThread.lock.wait(RUN_LOOP_DELAY_MSEC);
                } catch (final InterruptedException ignore) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        counters.incrementAndGet(RUN_LOOP_COUNTER);
    }

    boolean scanningIsPaused(final InstallerConfig cfg, final Session session) throws RepositoryException {
        if (session.nodeExists(cfg.getPauseScanNodePath())) {
            Node node = session.getNode(cfg.getPauseScanNodePath());
            boolean result = node.hasNodes();
            if (result && logger.isDebugEnabled()) {
                List<String> nodeNames = new ArrayList<String>();
                NodeIterator childItr = node.getNodes();
                while (childItr.hasNext()) {
                    nodeNames.add(childItr.nextNode().getName());
                }
                logger.debug("Found child nodes {} at path {}. Scanning will be paused", nodeNames, cfg.getPauseScanNodePath());
            }
            return result;
        }
        return false;
    }

    long getCounterValue(int key) {
        return counters.get(key);
    }

    /**
     * @see org.apache.sling.installer.api.UpdateHandler#handleRemoval(java.lang.String, java.lang.String, java.lang.String)
     */
    public UpdateResult handleRemoval(final String resourceType,
            final String id,
            final String url) {
        // get configuration
        final InstallerConfig cfg = this.getConfiguration();
        if (cfg == null || !cfg.isWriteBack()) {
            return null;
        }
        final int pos = url.indexOf(':');
        final String path = url.substring(pos + 1);

        // check path (SLING-2407)
        // 0. Check protocol
        if (!url.startsWith(URL_SCHEME)) {
            logger.debug("Not removing unmanaged artifact from repository: {}", url);
            return null;
        }
        // 1. Is this a system configuration then don't delete
        final String[] rootPaths = cfg.getFolderNameFilter().getRootPaths();
        final String systemConfigRootPath = rootPaths[rootPaths.length - 1];
        if (path.startsWith(systemConfigRootPath)) {
            logger.debug("Not removing system artifact from repository at {}", path);
            return null;
        }
        // 2. Is this configuration provisioned by us
        boolean found = false;
        int lastSlash = path.lastIndexOf('/');
        while (!found && lastSlash > 1) {
            final String prefix = path.substring(0, lastSlash);
            if (cfg.getFolderNameFilter().getPriority(prefix) != -1) {
                found = true;
            } else {
                lastSlash = prefix.lastIndexOf('/');
            }
        }
        if (found) {
            // remove
            logger.debug("Removing artifact at {}", path);
            Session session = null;
            try {
                session = repository.loginService(/* subservice name */null, repository.getDefaultWorkspace());
                if (session.itemExists(path)) {
                    session.getItem(path).remove();
                    session.save();
                }
            } catch (final RepositoryException re) {
                logger.error("Unable to remove resource from " + path, re);
                return null;
            } finally {
                if (session != null) {
                    session.logout();
                }
            }
            return new UpdateResult(url);
        }
        // not provisioned by us
        logger.debug("Not removing unmanaged artifact from repository at {}", path);
        return null;
    }

    /**
     * @see org.apache.sling.installer.api.UpdateHandler#handleUpdate(java.lang.String, java.lang.String, java.lang.String, java.util.Dictionary, Map)
     */
    public UpdateResult handleUpdate(final String resourceType,
            final String id,
            final String url,
            final Dictionary<String, Object> dict,
            final Map<String, Object> attributes) {
        return this.handleUpdate(resourceType, id, url, null, dict, attributes);
    }

    /**
     * @see org.apache.sling.installer.api.UpdateHandler#handleUpdate(java.lang.String, java.lang.String, java.lang.String, java.io.InputStream, Map)
     */
    public UpdateResult handleUpdate(final String resourceType,
            final String id,
            final String url,
            final InputStream is,
            final Map<String, Object> attributes) {
        return this.handleUpdate(resourceType, id, url, is, null, attributes);
    }

    private String getPathWithHighestPrio(final InstallerConfig cfg, final String oldPath) {
        final String path;
        // check root path, we use the path with highest prio
        final String rootPath = cfg.getFolderNameFilter().getRootPaths()[0] + '/';
        if (!oldPath.startsWith(rootPath)) {
            final int slashPos = oldPath.indexOf('/', 1);
            path = rootPath + oldPath.substring(slashPos + 1);
        } else {
            path = oldPath;
        }
        return path;
    }

    /**
     * Internal implementation of update handling
     */
    private UpdateResult handleUpdate(final String resourceType,
            final String id,
            final String url,
            final InputStream is,
            final Dictionary<String, Object> dict,
            final Map<String, Object> attributes) {
        // get configuration
        final InstallerConfig cfg = this.getConfiguration();
        if (cfg == null || !cfg.isWriteBack()) {
            return null;
        }

        // we only handle add/update of configs for now
        if (!resourceType.equals(InstallableResource.TYPE_CONFIG)) {
            return null;
        }

        Session session = null;
        try {
            session = repository.loginService(/* subservice name */null, repository.getDefaultWorkspace());

            final String path;
            boolean resourceIsMoved = true;
            if (url != null) {
                // update
                final int pos = url.indexOf(':');
                final String oldPath = url.substring(pos + 1);

                // calculate the new node path
                final String nodePath;
                if (url.startsWith(URL_SCHEME + ':')) {
                    nodePath = getPathWithHighestPrio(cfg, oldPath);
                } else {
                    final int lastSlash = url.lastIndexOf('/');
                    final int lastPos = url.lastIndexOf('.');
                    final String name;
                    if (lastSlash == -1 || lastPos < lastSlash) {
                        name = id;
                    } else {
                        name = url.substring(lastSlash + 1, lastPos);
                    }
                    nodePath = getPathWithHighestPrio(cfg, cfg.getNewConfigPath() + name + CONFIG_FILE_EXTENSION);
                }
                // ensure extension 'config'
                if (!nodePath.endsWith(CONFIG_FILE_EXTENSION)) {
                    if (session.itemExists(nodePath)) {
                        session.getItem(nodePath).remove();
                    }
                    path = nodePath + CONFIG_FILE_EXTENSION;
                } else {
                    path = nodePath;
                }

                resourceIsMoved = nodePath.equals(oldPath);
                logger.debug("Update of {} at {}", resourceType, path);
            } else {
                // add
                final String name;
                if (attributes != null && attributes.get(InstallableResource.RESOURCE_URI_HINT) != null) {
                    name = (String) attributes.get(InstallableResource.RESOURCE_URI_HINT);
                } else {
                    name = id;
                }
                path = cfg.getNewConfigPath() + name + CONFIG_FILE_EXTENSION;
                logger.debug("Add of {} at {}", resourceType, path);
            }

            // write to a byte array stream
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            // comments starting with "//"  allowed according to https://osgi.org/specification/osgi.cmpn/7.0.0/service.configurator.html#d0e131566
            baos.write("// Configuration created by Apache Sling JCR Installer\n".getBytes("UTF-8"));
            ConfigurationSerializer serializer = ConfigurationSerializerFactory.create(ConfigurationSerializerFactory.Format.JSON);
            serializer.serialize(dict, baos);
            baos.close();

            // get or create file node
            JcrUtil.createPath(session, path, NT_FILE);
            // get or create resource node
            final Node dataNode = JcrUtil.createPath(session, path + "/jcr:content", NT_RESOURCE);

            dataNode.setProperty(PROP_DATA, new ByteArrayInputStream(baos.toByteArray()));
            dataNode.setProperty(PROP_MODIFIED, Calendar.getInstance());
            dataNode.setProperty(PROP_ENC, ENCODING);
            dataNode.setProperty(PROP_MIME, MIME_TXT);
            session.save();

            final UpdateResult result = new UpdateResult(JcrInstaller.URL_SCHEME + ':' + path);
            // priority
            final int lastSlash = path.lastIndexOf('/');
            final String parentPath = path.substring(0, lastSlash);
            result.setPriority(cfg.getFolderNameFilter().getPriority(parentPath));
            result.setResourceIsMoved(resourceIsMoved);
            return result;
        } catch (final RepositoryException re) {
            logger.error("Unable to add/update resource " + resourceType + ':' + id, re);
            return null;
        } catch (final IOException e) {
            logger.error("Unable to add/update resource " + resourceType + ':' + id, e);
            return null;
        } finally {
            if (session != null) {
                session.logout();
            }
        }
    }

}
