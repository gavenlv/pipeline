package com.hsbc.treasury.apex.ci.core

import com.hsbc.treasury.apex.ci.utils.MockScript
import com.hsbc.treasury.apex.ci.errors.ApexCIException
import org.junit.Test
import org.junit.Assert

class PipelineContextTest {

    @Test
    void builderProducesValidContext() {
        def s = new MockScript()
        def ctx = PipelineContext.builder()
                .script(s)
                .env(['A': '1'])
                .params(['P': 'x'])
                .workDir('/tmp/work')
                .build()
        Assert.assertSame(s, ctx.script)
        Assert.assertEquals('1', ctx.env['A'])
        Assert.assertEquals('x', ctx.params['P'])
        Assert.assertEquals('/tmp/work', ctx.workDir)
    }

    @Test
    void envIsImmutable() {
        def ctx = PipelineContext.builder().env(['A': '1']).build()
        try {
            (ctx.env as Map)['A'] = '2'
            // groovy 可能未抛错，验证访问
            // 校验 unmodifiableMap 行为
            boolean readOnly = false
            try {
                ((Map) ctx.env).put('B', 'x')
            } catch (UnsupportedOperationException uoe) {
                readOnly = true
            }
            Assert.assertTrue("env should be read-only", readOnly)
        } catch (UnsupportedOperationException ignore) {
            // OK
        }
    }

    @Test
    void attrsAreMutable() {
        def ctx = PipelineContext.builder().build()
        ctx.setAttr('k', 'v')
        Assert.assertEquals('v', ctx.getAttr('k'))
        Assert.assertEquals('default', ctx.getAttr('missing', 'default'))
        Assert.assertTrue(ctx.hasAttr('k'))
    }

    @Test
    void withEnvMerges() {
        def ctx = PipelineContext.builder().env(['A': '1', 'B': '2']).build()
        def merged = ctx.withEnv(['B': 'override', 'C': '3'])
        Assert.assertEquals('1', merged.env['A'])
        Assert.assertEquals('override', merged.env['B'])
        Assert.assertEquals('3', merged.env['C'])
    }
}
