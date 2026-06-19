package com.hsbc.treasury.apex.ci.utils

import com.hsbc.treasury.apex.ci.core.PipelineContext
import org.junit.Test
import org.junit.Assert

class UtilTest {

    @Test
    void humanizeDurationForVariousMagnitudes() {
        Assert.assertEquals('0ms', Util.humanizeDuration(0))
        Assert.assertEquals('500ms', Util.humanizeDuration(500))
        Assert.assertEquals('5s', Util.humanizeDuration(5000))
        Assert.assertEquals('1m30s', Util.humanizeDuration(90_000))
        Assert.assertEquals('2h5m', Util.humanizeDuration(2 * 60 * 60 * 1000 + 5 * 60 * 1000))
    }

    @Test
    void resolvePathPrefersAbsolute() {
        def s = new MockScript()
        def ctx = PipelineContext.builder().script(s).workDir('/ws').build()
        Assert.assertEquals('/abs', Util.resolvePath(ctx, '/abs'))
        Assert.assertEquals('/ws/a/b/c', Util.resolvePath(ctx, 'a/b/c'))
        Assert.assertNull(Util.resolvePath(ctx, null))
    }
}
