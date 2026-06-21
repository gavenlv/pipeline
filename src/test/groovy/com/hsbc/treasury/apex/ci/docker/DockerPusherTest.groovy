package com.hsbc.treasury.apex.ci.docker

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.utils.MockScript
import org.junit.Test
import org.junit.Assert

class DockerPusherTest {

    @Test
    void tagCommandIsListForm() {
        def cmd = DockerPusher.tagCommand('src:1', 'dst:2')
        Assert.assertEquals('docker', cmd[0])
        Assert.assertEquals('tag', cmd[1])
        Assert.assertEquals('src:1', cmd[2])
        Assert.assertEquals('dst:2', cmd[3])
    }

    @Test
    void pushCommandIsListForm() {
        def cmd = DockerPusher.pushCommand('img:1')
        Assert.assertEquals(['docker', 'push', 'img:1'], cmd)
    }

    @Test
    void loginCommandTargetsRegistry() {
        def cmd = DockerPusher.loginCommand('ghcr.io')
        Assert.assertEquals('docker', cmd[0])
        Assert.assertEquals('login', cmd[1])
        Assert.assertEquals('ghcr.io', cmd[3])
    }

    @Test
    void tagExecutesAgainstScript() {
        def s = new MockScript()
        def ctx = PipelineContext.builder().script(s).build()
        new DockerPusher().tag(ctx, 'src:1', 'dst:1')
        Assert.assertEquals(1, s.shCalls.size())
        String script = s.shCalls[0].script as String
        Assert.assertTrue(script.contains('docker'))
        Assert.assertTrue(script.contains('tag'))
    }
}
