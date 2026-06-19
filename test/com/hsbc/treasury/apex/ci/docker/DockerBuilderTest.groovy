package com.hsbc.treasury.apex.ci.docker

import org.junit.Test
import org.junit.Assert

class DockerBuilderTest {

    @Test
    void assemblesBuildxCommand() {
        def cfg = DockerBuildConfig.fromClosure {
            dockerfile = 'Dockerfile'
            tags = ['1.0.0', 'latest']
            platforms = ['linux/amd64', 'linux/arm64']
            buildArgs = ['NODE_VERSION=20', 'JAR_FILE=app.jar']
            secrets = ['id=npmrc,src=.npmrc']
            cacheFrom = ['type=registry,ref=ghcr.io/x/y:cache']
            noCache = false
            pushOnBuild = false
            context = '.'
        }
        def cmd = DockerBuilder.assembleCommand('ghcr.io/x/y:1.0.0', cfg)
        Assert.assertTrue(cmd.contains('docker'))
        Assert.assertTrue(cmd.contains('buildx'))
        Assert.assertTrue(cmd.contains('build'))
        Assert.assertTrue(cmd.contains('--file'))
        Assert.assertTrue(cmd.contains('Dockerfile'))
        Assert.assertTrue(cmd.contains('--tag'))
        Assert.assertTrue(cmd.contains('ghcr.io/x/y:1.0.0'))
        Assert.assertTrue(cmd.contains('--platform'))
        Assert.assertTrue(cmd.contains('linux/amd64'))
        Assert.assertTrue(cmd.contains('linux/arm64'))
        Assert.assertTrue(cmd.contains('--build-arg'))
        Assert.assertTrue(cmd.contains('NODE_VERSION=20'))
        Assert.assertTrue(cmd.contains('--secret'))
        Assert.assertTrue(cmd.contains('id=npmrc,src=.npmrc'))
        Assert.assertTrue(cmd.contains('--cache-from'))
        Assert.assertTrue(cmd.contains('--load'))
        Assert.assertTrue(cmd.contains('.'))
    }

    @Test
    void pushFlagIncludedWhenPushOnBuild() {
        def cfg = DockerBuildConfig.fromClosure { pushOnBuild = true; tags = ['t'] }
        def cmd = DockerBuilder.assembleCommand('img:1', cfg)
        Assert.assertTrue(cmd.contains('--push'))
        Assert.assertFalse(cmd.contains('--load'))
    }

    @Test
    void noCacheFlagIncluded() {
        def cfg = DockerBuildConfig.fromClosure { noCache = true }
        def cmd = DockerBuilder.assembleCommand('img:1', cfg)
        Assert.assertTrue(cmd.contains('--no-cache'))
    }

    @Test
    void requiresImageRef() {
        try {
            DockerBuilder.assembleCommand(null, DockerBuildConfig.fromClosure { })
            Assert.fail("expected")
        } catch (Exception ex) {
            Assert.assertTrue(ex.message.contains('imageRef'))
        }
    }
}
