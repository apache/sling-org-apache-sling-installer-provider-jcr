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

import org.apache.sling.installer.api.UpdateResult;
import org.junit.Test;

import java.util.Hashtable;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Comprehensive test suite for JCR installer update handling functionality.
 * This test class validates the behavior of the JCR installer when processing
 * configuration updates, ensuring proper URL transformation and resource type
 * handling across various scenarios.
 */
public class JcrHandleUpdateTest extends JcrInstallTestBase {

    // Test constants for better maintainability
    private static final String CONFIG_RESOURCE_TYPE = "config";
    private static final String BUNDLE_RESOURCE_TYPE = "bundle";
    private static final String EMPTY_RESOURCE_TYPE = "";
    private static final String JCR_INSTALL_SCHEME = "jcrinstall:";
    private static final String EXPECTED_JSON_EXTENSION = ".cfg.json";
    
    private static final String TEST_CONFIG_NAME = "testConfiguration";
    private static final String TEST_CONFIG_PATH = "/path/to/" + TEST_CONFIG_NAME;
    private static final String APPS_BASE_PATH = "/apps/myapp";
    private static final String LIBS_BASE_PATH = "/libs/sling";

    /**
     * Tests URL transformation for various configuration file extensions.
     * 
     * Verifies that different file extensions (.config, .properties, .cfg, .xml, .json)
     * are properly converted to the standardized .cfg.json format.
     */
    @Test
    public void testTransformConfigurationFileExtensionsToStandardizedFormat() throws Exception {
        // Given: Various configuration file extensions
        String[] fileExtensions = {".config", ".properties", ".cfg", ".xml", ".json"};
        
        for (String extension : fileExtensions) {
            // When: Handling update with different file extension
            String originalUrl = JCR_INSTALL_SCHEME + TEST_CONFIG_PATH + extension;
            UpdateResult updateResult = installer.handleUpdate(
                CONFIG_RESOURCE_TYPE, 
                TEST_CONFIG_NAME, 
                originalUrl, 
                new Hashtable<>(), 
                null
            );
            
            // Then: URL should be transformed to standardized format
            String expectedUrl = JCR_INSTALL_SCHEME + TEST_CONFIG_PATH + EXPECTED_JSON_EXTENSION;
            assertEquals("URL should be transformed for extension: " + extension, 
                        expectedUrl, updateResult.getURL());
        }
    }

    /**
     * Tests URL transformation for deeply nested directory structures.
     * 
     * Ensures that complex path hierarchies are handled correctly during
     * the URL transformation process.
     */
    @Test
    public void testHandleDeeplyNestedDirectoryPaths() throws Exception {
        // Given: Deeply nested configuration paths
        String[] nestedPaths = {
            "/apps/myapp/config/install/" + TEST_CONFIG_NAME + ".config",
            "/libs/sling/installer/provider/jcr/" + TEST_CONFIG_NAME + ".config"
        };
        
        for (String path : nestedPaths) {
            // When: Handling update with nested path
            String originalUrl = JCR_INSTALL_SCHEME + path;
            UpdateResult updateResult = installer.handleUpdate(
                CONFIG_RESOURCE_TYPE, 
                TEST_CONFIG_NAME, 
                originalUrl, 
                new Hashtable<>(), 
                null
            );
            
            // Then: Path should be preserved with transformed extension
            String expectedUrl = JCR_INSTALL_SCHEME + path.replace(".config", EXPECTED_JSON_EXTENSION);
            assertEquals("Nested path should be handled correctly: " + path, 
                        expectedUrl, updateResult.getURL());
        }
    }

    /**
     * Tests handling of different resource types.
     * 
     * Verifies that only configuration resources are processed while
     * other resource types (bundles, empty) are properly rejected.
     */
    @Test
    public void testOnlyProcessConfigurationResourceTypes() throws Exception {
        // Given: Different resource types to test
        String[] nonConfigResourceTypes = {BUNDLE_RESOURCE_TYPE, EMPTY_RESOURCE_TYPE};
        
        for (String resourceType : nonConfigResourceTypes) {
            // When: Handling update with non-configuration resource type
            String testUrl = JCR_INSTALL_SCHEME + TEST_CONFIG_PATH + ".config";
            UpdateResult updateResult = installer.handleUpdate(
                resourceType, 
                TEST_CONFIG_NAME, 
                testUrl, 
                new Hashtable<>(), 
                null
            );
            
            // Then: Update should be rejected for non-configuration types
            assertNull("Update should be rejected for resource type: " + resourceType, updateResult);
        }
    }

    /**
     * Tests URL transformation with various configuration identifiers.
     * 
     * Validates that different naming patterns (alphanumeric, numeric, 
     * special characters) are handled correctly.
     */
    @Test
    public void testHandleVariousConfigurationIdentifiers() throws Exception {
        // Given: Different configuration identifiers
        String[] configIdentifiers = {"myConfiguration", "123", "my-config"};
        
        for (String identifier : configIdentifiers) {
            // When: Handling update with different identifier
            String originalUrl = JCR_INSTALL_SCHEME + "/path/to/" + identifier + ".config";
            UpdateResult updateResult = installer.handleUpdate(
                CONFIG_RESOURCE_TYPE, 
                identifier, 
                originalUrl, 
                new Hashtable<>(), 
                null
            );
            
            // Then: URL should be transformed correctly
            String expectedUrl = JCR_INSTALL_SCHEME + "/path/to/" + identifier + EXPECTED_JSON_EXTENSION;
            assertEquals("Identifier should be handled correctly: " + identifier, 
                        expectedUrl, updateResult.getURL());
        }
    }

    /**
     * Tests update handling with configuration data payload.
     * 
     * Verifies that configuration updates with actual property data
     * are processed correctly and the URL transformation still occurs.
     */
    @Test
    public void testProcessConfigurationUpdatesWithDataPayload() throws Exception {
        // Given: Configuration data with various property types
        Map<String, Object> configurationData = createTestConfigurationData();
        
        // When: Handling update with configuration data
        String originalUrl = JCR_INSTALL_SCHEME + TEST_CONFIG_PATH + ".config";
        UpdateResult updateResult = installer.handleUpdate(
            CONFIG_RESOURCE_TYPE, 
            TEST_CONFIG_NAME, 
            originalUrl, 
            new Hashtable<>(configurationData), 
            null
        );
        
        // Then: URL should be transformed and data should be processed
        String expectedUrl = JCR_INSTALL_SCHEME + TEST_CONFIG_PATH + EXPECTED_JSON_EXTENSION;
        assertEquals("Configuration update with data should be processed", 
                    expectedUrl, updateResult.getURL());
    }

    /**
     * Tests URL transformation for different root path priorities.
     * 
     * Validates that configuration updates in different root paths
     * (/apps, /libs) are handled consistently.
     */
    @Test
    public void testHandleDifferentRootPathPriorities() throws Exception {
        // Given: Different root paths with varying priorities
        String[] rootPaths = {APPS_BASE_PATH, LIBS_BASE_PATH};
        
        for (String rootPath : rootPaths) {
            // When: Handling update in different root path
            String originalUrl = JCR_INSTALL_SCHEME + rootPath + "/" + TEST_CONFIG_NAME + ".config";
            UpdateResult updateResult = installer.handleUpdate(
                CONFIG_RESOURCE_TYPE, 
                TEST_CONFIG_NAME, 
                originalUrl, 
                new Hashtable<>(), 
                null
            );
            
            // Then: URL should be transformed while preserving root path
            String expectedUrl = JCR_INSTALL_SCHEME + rootPath + "/" + TEST_CONFIG_NAME + EXPECTED_JSON_EXTENSION;
            assertEquals("Root path should be preserved: " + rootPath, 
                        expectedUrl, updateResult.getURL());
        }
    }

    /**
     * Tests URL transformation with complex path structures containing multiple dots.
     * 
     * Ensures that paths with multiple dot-separated segments are handled
     * correctly during the extension transformation process.
     */
    @Test
    public void testHandleComplexPathsWithMultipleDots() throws Exception {
        // Given: Complex path with multiple dot-separated segments
        String complexPath = "/path/to/my.configName.file.config";
        
        // When: Handling update with complex path
        String originalUrl = JCR_INSTALL_SCHEME + complexPath;
        UpdateResult updateResult = installer.handleUpdate(
            CONFIG_RESOURCE_TYPE, 
            TEST_CONFIG_NAME, 
            originalUrl, 
            new Hashtable<>(), 
            null
        );
        
        // Then: Only the final extension should be transformed
        String expectedUrl = JCR_INSTALL_SCHEME + "/path/to/my.configName.file" + EXPECTED_JSON_EXTENSION;
        assertEquals("Complex path with multiple dots should be handled correctly", 
                    expectedUrl, updateResult.getURL());
    }

    /**
     * Tests update handling with null URL for configuration resources.
     * 
     * Verifies that configuration updates without a specific URL
     * are handled by generating a default path.
     */
    @Test
    public void testHandleNullUrlForConfigurationResources() throws Exception {
        // When: Handling configuration update with null URL
        UpdateResult updateResult = installer.handleUpdate(
            CONFIG_RESOURCE_TYPE, 
            TEST_CONFIG_NAME, 
            null, 
            new Hashtable<>(), 
            null
        );
        
        // Then: Default path should be generated
        String expectedUrl = JCR_INSTALL_SCHEME + "/apps/sling/install/" + TEST_CONFIG_NAME + EXPECTED_JSON_EXTENSION;
        assertEquals("Null URL should result in default path generation", 
                    expectedUrl, updateResult.getURL());
    }

    /**
     * Tests update handling with null URL and empty resource type.
     * 
     * Verifies that updates with null URL and empty resource type
     * are properly rejected.
     */
    @Test
    public void testRejectNullUrlWithEmptyResourceType() throws Exception {
        // When: Handling update with null URL and empty resource type
        UpdateResult updateResult = installer.handleUpdate(
            EMPTY_RESOURCE_TYPE, 
            TEST_CONFIG_NAME, 
            null, 
            new Hashtable<>(), 
            null
        );
        
        // Then: Update should be rejected
        assertNull("Update with null URL and empty resource type should be rejected", updateResult);
    }

    /**
     * Tests update handling with null URL but valid configuration data.
     * 
     * Verifies that configuration updates with null URL but containing
     * configuration data are processed correctly.
     */
    @Test
    public void testProcessNullUrlWithValidConfigurationData() throws Exception {
        // Given: Valid configuration data
        Map<String, Object> configurationData = createTestConfigurationData();
        
        // When: Handling update with null URL but valid data
        UpdateResult updateResult = installer.handleUpdate(
            CONFIG_RESOURCE_TYPE, 
            TEST_CONFIG_NAME, 
            null, 
            new Hashtable<>(configurationData), 
            null
        );
        
        // Then: Default path should be generated and data should be processed
        String expectedUrl = JCR_INSTALL_SCHEME + "/apps/sling/install/" + TEST_CONFIG_NAME + EXPECTED_JSON_EXTENSION;
        assertEquals("Null URL with valid data should generate default path", 
                    expectedUrl, updateResult.getURL());
    }

    /**
     * Creates test configuration data with various property types.
     * 
     * @return Map containing test configuration properties
     */
    private Map<String, Object> createTestConfigurationData() {
        Map<String, Object> configurationData = new Hashtable<>();
        configurationData.put("stringProperty", "testValue");
        configurationData.put("numericProperty", 42);
        configurationData.put("booleanProperty", true);
        configurationData.put("complex.property.name", "complexValue");
        return configurationData;
    }
}
