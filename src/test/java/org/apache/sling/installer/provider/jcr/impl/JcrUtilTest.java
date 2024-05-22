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
