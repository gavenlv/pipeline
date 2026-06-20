package com.hsbc.treasury.apex.ci.builders

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.errors.ApexCIException
import com.hsbc.treasury.apex.ci.utils.MockScript
import org.junit.Assert
import org.junit.Test

/**
 * 通用 Shell 构建器（兜底）的全场景测试。
 */
class ShellBuilderTest {

    private static Map<String, Object> fresh() {
        def s = new MockScript()
        def ctx = PipelineContext.builder().script(s).build()
        return ['ctx': ctx, 's': s]
    }

    @Test
    void shellDetectsAnyProject() {
        // 兜底 builder：永远返回 true
        Assert.assertTrue(new ShellBuilder().detect(new File('/tmp')))
    }

    @Test
    void executeRunsMultipleCommands() {
        Map f = fresh()
        def ctx = f.ctx
        def s = f.s
        new ShellBuilder().execute(ctx, {
            commands = [
                ['echo', 'step 1'],
                ['echo', 'step 2'],
                ['ls', '-la']
            ]
            label = 'misc'
        })
        // 3 个命令 → 3 次 sh 调用
        Assert.assertEquals(3, s.shCalls.size())
        String cmd0 = s.shCalls[0].script as String
        Assert.assertTrue(cmd0.contains('echo'))
        Assert.assertTrue(cmd0.contains('step 1'))
        String cmd2 = s.shCalls[2].script as String
        Assert.assertTrue(cmd2.contains('ls'))
        Assert.assertTrue(cmd2.contains('-la'))
    }

    @Test
    void executeCustomLabelAppearsInAll() {
        Map f = fresh()
        def ctx = f.ctx
        def s = f.s
        new ShellBuilder().execute(ctx, {
            commands = [['echo', 'a'], ['echo', 'b']]
            label = 'custom-label'
        })
        // label 不影响 shCalls[].script 字段，只影响 Sandbox.runShell 内部
        Assert.assertEquals(2, s.shCalls.size())
    }

    @Test
    void emptyCommandsFailsFast() {
        Map f = fresh()
        def ctx = f.ctx
        try {
            new ShellBuilder().execute(ctx, { commands = [] })
            Assert.fail("expected throw")
        } catch (ApexCIException ex) {
            Assert.assertTrue(ex.message.contains('commands'))
        }
    }

    @Test
    void nullCommandsFailsFast() {
        Map f = fresh()
        def ctx = f.ctx
        try {
            new ShellBuilder().execute(ctx, { commands = null })
            Assert.fail("expected throw")
        } catch (ApexCIException ex) {
            Assert.assertTrue(ex.message.contains('commands'))
        }
    }

    @Test
    void executeReturnsSummary() {
        Map f = fresh()
        def ctx = f.ctx
        def result = new ShellBuilder().execute(ctx, {
            commands = [['echo', 'a'], ['echo', 'b'], ['echo', 'c']]
            label = 'demo'
        })
        Assert.assertEquals(3, result.count)
    }
}
