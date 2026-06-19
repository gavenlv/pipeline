package com.hsbc.treasury.apex.ci.artifact

import org.junit.Test
import org.junit.Assert

class NexusClientTest {

    @Test
    void putCommandInjectsUrl() {
        def c = NexusClient.of('https://nexus.x', 'maven-releases', 'maven2', 'creds')
        def cmd = c.buildPut('com/acme/1.0/x-1.0.jar', '/tmp/x.jar', 'application/java-archive')
        Assert.assertTrue(cmd.contains('curl'))
        Assert.assertTrue(cmd.contains('--upload-file'))
        Assert.assertTrue(cmd.contains('/tmp/x.jar'))
        Assert.assertTrue(cmd.any { it.toString().contains('https://nexus.x/repository/maven-releases/com/acme/1.0/x-1.0.jar') })
    }

    @Test
    void npmPublishUsesRegistry() {
        def c = NexusClient.of('https://nexus.x', 'npm-hosted', 'npm', null)
        def cmd = c.buildNpmPublish()
        Assert.assertEquals('npm', cmd[0])
        Assert.assertTrue(cmd.any { it.toString().contains('https://nexus.x/repository/npm-hosted/') })
    }

    @Test
    void twineTargetsPyPiRepo() {
        def c = NexusClient.of('https://nexus.x', 'pypi-releases', 'pypi', null)
        def cmd = c.buildTwineUpload('dist')
        Assert.assertEquals('twine', cmd[0])
        Assert.assertTrue(cmd.contains('dist/*'))
        Assert.assertTrue(cmd.any { it.toString().contains('https://nexus.x/repository/pypi-releases/') })
    }

    @Test
    void mavenDistributionUrl() {
        def c = NexusClient.of('https://nexus.x', 'maven-releases', 'maven2', null)
        Assert.assertEquals('https://nexus.x/repository/maven-releases/', c.mavenDistributionUrl())
    }

    @Test
    void requiresBaseUrlAndRepository() {
        try {
            NexusClient.of(null, 'maven-releases', 'maven2', null)
            Assert.fail("expected")
        } catch (Exception ignore) { }
        try {
            NexusClient.of('https://x', null, 'maven2', null)
            Assert.fail("expected")
        } catch (Exception ignore) { }
    }
}
