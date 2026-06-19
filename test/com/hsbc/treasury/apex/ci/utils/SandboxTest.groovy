package com.hsbc.treasury.apex.ci.utils

import org.junit.Test
import org.junit.Assert

class SandboxTest {

    @Test
    void renderBuildsArrayForm() {
        def script = Sandbox.render(['mvn', '-Dk=v', 'clean', 'package'])
        Assert.assertTrue(script.contains("set -e"))
        Assert.assertTrue(script.contains('ARGS=('))
        Assert.assertTrue(script.contains("'mvn'"))
        Assert.assertTrue(script.contains("'-Dk=v'"))
        Assert.assertTrue(script.contains("'clean'"))
        Assert.assertTrue(script.contains("'package'"))
    }

    @Test
    void renderEscapesSingleQuotes() {
        def script = Sandbox.render(['echo', "it's a test"])
        // 单引号应转义
        Assert.assertTrue(script.contains("'\\''"))
    }

    @Test
    void runShellRequiresScript() {
        try {
            Sandbox.runShell(null, ['echo'])
            Assert.fail("expected")
        } catch (Exception ex) {
            Assert.assertTrue(ex.message.contains('PipelineContext'))
        }
    }

    @Test
    void runShellRequiresNonEmpty() {
        def s = new MockScript()
        def ctx = com.hsbc.treasury.apex.ci.core.PipelineContext.builder().script(s).build()
        try {
            Sandbox.runShell(ctx, [])
            Assert.fail("expected")
        } catch (Exception ex) {
            Assert.assertTrue(ex.message.contains('Empty'))
        }
    }
}
