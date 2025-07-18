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
package org.apache.maven.buildcache;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.maven.SessionScoped;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.buildcache.checksum.MavenProjectInput;
import org.apache.maven.buildcache.xml.CacheConfig;
import org.apache.maven.buildcache.xml.build.ProjectsInputInfo;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.internal.builder.BuilderCommon;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SessionScoped
@Named
public class DefaultProjectInputCalculator implements ProjectInputCalculator {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultProjectInputCalculator.class);

    private final Provider<MavenSession> providerSession;
    private final RemoteCacheRepository remoteCache;
    private final CacheConfig cacheConfig;
    private final RepositorySystem repoSystem;
    private final NormalizedModelProvider normalizedModelProvider;
    private final MultiModuleSupport multiModuleSupport;
    private final ArtifactHandlerManager artifactHandlerManager;

    private final ConcurrentMap<String, ProjectsInputInfo> checkSumMap = new ConcurrentHashMap<>();

    private static final ThreadLocal<Set<String>> CURRENTLY_CALCULATING = ThreadLocal.withInitial(LinkedHashSet::new);

    @Inject
    public DefaultProjectInputCalculator(
            Provider<MavenSession> providerSession,
            RemoteCacheRepository remoteCache,
            CacheConfig cacheConfig,
            RepositorySystem repoSystem,
            NormalizedModelProvider rawModelProvider,
            MultiModuleSupport multiModuleSupport,
            ArtifactHandlerManager artifactHandlerManager) {
        this.providerSession = providerSession;
        this.remoteCache = remoteCache;
        this.cacheConfig = cacheConfig;
        this.repoSystem = repoSystem;
        this.normalizedModelProvider = rawModelProvider;
        this.multiModuleSupport = multiModuleSupport;
        this.artifactHandlerManager = artifactHandlerManager;
    }

    @Override
    public ProjectsInputInfo calculateInput(MavenProject project) {
        LOGGER.info(
                "Going to calculate checksum for project [groupId={}, artifactId={}, version={}]",
                project.getGroupId(),
                project.getArtifactId(),
                project.getVersion());

        String key = BuilderCommon.getKey(project);
        // NOTE: Do not use ConcurrentHashMap.computeIfAbsent() here because of recursive calls
        // this could lead to runtime exception - IllegalStateException("Recursive update")
        // in jdk 8 the result of attempt to modify items with the same hash code could lead to infinite loop
        ProjectsInputInfo projectsInputInfo = checkSumMap.get(key);
        if (projectsInputInfo != null) {
            return projectsInputInfo;
        }
        projectsInputInfo = calculateInputInternal(key, project);
        checkSumMap.put(key, projectsInputInfo);
        return projectsInputInfo;
    }

    private ProjectsInputInfo calculateInputInternal(String key, MavenProject project) {
        Set<String> projectsSet = CURRENTLY_CALCULATING.get();

        if (!projectsSet.add(key)) {
            throw new IllegalStateException("Checksum for project is already calculating. "
                    + "Is there a cyclic dependencies? [project=" + key
                    + ", setOfCalculatingProjects=" + projectsSet + "]");
        }
        try {
            final MavenSession mavenSession = providerSession.get();
            final MavenProjectInput input = new MavenProjectInput(
                    project,
                    normalizedModelProvider,
                    multiModuleSupport,
                    this,
                    mavenSession,
                    cacheConfig,
                    repoSystem,
                    remoteCache,
                    artifactHandlerManager);
            return input.calculateChecksum();
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate checksums for " + project.getArtifactId(), e);
        } finally {
            projectsSet.remove(key);
        }
    }
}
