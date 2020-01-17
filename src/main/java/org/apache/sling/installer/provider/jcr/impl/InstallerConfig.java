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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Dictionary;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.installer.api.InstallableResource;
import org.apache.sling.installer.provider.jcr.impl.JcrInstaller.NodeConverter;
import org.apache.sling.settings.SlingSettingsService;
import org.slf4j.Logger;

public class InstallerConfig {

    /** Write back enabled? */
    private final boolean writeBack;

    /** Our NodeConverters*/
    private final Collection <NodeConverter> converters = new ArrayList<NodeConverter>();

    private final int maxWatchedFolderDepth;

    /** Filter for folder names */
    private final FolderNameFilter folderNameFilter;

    /** The root folders that we watch */
    private final String[] roots;

    /** The path for new configurations. */
    private final String newConfigPath;

    /** The path for pauseInstallation property */
    private final String pauseScanNodePath;

    /** List of watched folders */
    private final List<WatchedFolder> watchedFolders = new LinkedList<WatchedFolder>();

    private final Logger logger;

    public InstallerConfig(
            final Logger logger,
            final JcrInstaller.Configuration configuration,
            final SlingSettingsService settings) {
        this.logger = logger;
        this.writeBack = configuration.sling_jcrinstall_enable_writeback();

        // Setup converters
        converters.add(new FileNodeConverter());
        converters.add(new ConfigNodeConverter());

        maxWatchedFolderDepth = configuration.sling_jcrinstall_folder_max_depth();

        // Configurable folder regexp, system property overrides default value
        String folderNameRegexp = configuration.sling_jcrinstall_folder_name_regexp().trim();
       
        // Setup folder filtering and watching
        this.folderNameFilter = new FolderNameFilter(configuration.sling_jcrinstall_search_path(),
                folderNameRegexp, settings.getRunModes());
        this.roots = folderNameFilter.getRootPaths();

        // setup default path for new configurations
        String newCfgPath = configuration.sling_jcrinstall_new_config_path();
        final boolean postSlash = newCfgPath.endsWith("/");
        if ( !postSlash ) {
            newCfgPath = newCfgPath.concat("/");
        }
        final boolean preSlash = newCfgPath.startsWith("/");
        if ( !preSlash ) {
            newCfgPath = this.folderNameFilter.getRootPaths()[0] + '/' + newCfgPath;
        }
        this.newConfigPath = newCfgPath;

        this.pauseScanNodePath = configuration.sling_jcrinstall_signal_path();
    }

    public String[] getRoots() {
        return this.roots;
    }

    public FolderNameFilter getFolderNameFilter() {
        return this.folderNameFilter;
    }

    public Collection <NodeConverter> getConverters() {
        return this.converters;
    }

    public int getMaxWatchedFolderDepth() {
        return maxWatchedFolderDepth;
    }

    public String getPauseScanNodePath() {
        return pauseScanNodePath;
    }

    public boolean isWriteBack() {
        return this.writeBack;
    }

    public String getNewConfigPath() {
        return this.newConfigPath;
    }

    public List<WatchedFolder> cloneWatchedFolders() {
        synchronized ( this.watchedFolders ) {
            return new ArrayList<WatchedFolder>(this.watchedFolders);
        }
    }

    /**
     * Scan watchedFolders and get installable resources
     */
    public List<InstallableResource> scanWatchedFolders() throws RepositoryException {
        final List<InstallableResource> resources = new LinkedList<InstallableResource>();
        synchronized ( this.watchedFolders ) {
            for(final WatchedFolder f : this.watchedFolders) {
                final WatchedFolder.ScanResult r = f.scan();
                logger.debug("Startup: {} provides resources {}", f, r.toAdd);
                resources.addAll(r.toAdd);
            }
        }
        return resources;
    }

    /**
     * Check all WatchedFolder, in case some were deleted
     */

    public List<String> checkForRemovedWatchedFolders(final Session session) throws RepositoryException {
        final List<String> removedResources = new LinkedList<String>();
        synchronized ( this.watchedFolders ) {
            final Iterator<WatchedFolder> i = this.watchedFolders.iterator();
            while ( i.hasNext() ) {
                final WatchedFolder wf = i.next();

                logger.debug("Item {} exists? {}", wf.getPath(), session.itemExists(wf.getPath()));
                if (!session.itemExists(wf.getPath())) {
                    logger.info("Deleting {}, path does not exist anymore", wf);
                    removedResources.addAll(wf.scan().toRemove);
                    i.remove();
                }
            }
        }
        return removedResources;
    }

    /**
     * Add WatchedFolder to our list if it doesn't exist yet.
     */
    public void addWatchedFolder(final WatchedFolder toAdd) {
        synchronized ( this.watchedFolders ) {
            WatchedFolder existing = null;
            for(WatchedFolder wf : this.watchedFolders) {
                if (wf.getPath().equals(toAdd.getPath())) {
                    existing = wf;
                    break;
                }
            }
            if (existing == null) {
                this.watchedFolders.add(toAdd);
                toAdd.start();
            }
        }
    }

    public boolean anyWatchFolderNeedsScan() {
        synchronized ( this.watchedFolders ) {
            for (final WatchedFolder wf : this.watchedFolders) {
                if (wf.needsScan()) {
                    return true;
                }
            }
        }
        return false;
    }
}
