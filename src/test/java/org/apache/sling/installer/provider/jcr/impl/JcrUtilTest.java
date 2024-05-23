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

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class JcrUtilTest {

    @Test
    public void testGetPidWhenPidIsSameAsFactoryPid() {
        String pid = JcrUtil.getPid("a.b.c", "a.b.c");
        assertEquals("a.b.c~a.b.c", pid);
    }

    @Test
    public void testGetPidWhenPidIsDifferentFromFactoryPid() {
        String pid = JcrUtil.getPid("a.b.cc", "a.b.c");
        assertEquals("a.b.cc~a.b.c", pid);
    }

    @Test
    public void testGetPidWhenPidIsDifferentFromFactoryPid2() {
        String pid = JcrUtil.getPid("aa.b.c", "a.b.c");
        assertEquals("aa.b.c~a.b.c", pid);
    }

    @Test
    public void testGetPidWhenPidIsInExpectedFormat() {
        String pid = JcrUtil.getPid("a.b.c", "a.b.c~c1");
        assertEquals("a.b.c~c1", pid);
    }

    @Test
    public void testGetPidWhenPidIsNotInExpectedFormat() {
        String pid = JcrUtil.getPid("a.b.c", "a.b.c.c1");
        assertEquals("a.b.c~c1", pid);
    }

    @Test
    public void testGetPidWhenFactoryPidIsNull() {
        String pid = JcrUtil.getPid(null, "a.b.c.c1");
        assertEquals("a.b.c.c1", pid);
    }
}
