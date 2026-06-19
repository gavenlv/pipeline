package com.hsbc.treasury.apex.ci.core

import com.hsbc.treasury.apex.ci.utils.MockScript
import org.junit.Test
import org.junit.Assert

class StageTest {

    @Test
    void emptyStageExecutesNoop() {
        def s = new MockScript()
        def ctx = PipelineContext.builder().script(s).build()
        def st = new Stage('empty')
        st.execute(ctx)
        Assert.assertEquals(0, s.shCalls.size())
    }

    @Test
    void sequentialStepsExecuteInOrder() {
        def s = new MockScript()
        def ctx = PipelineContext.builder().script(s).build()
        def st = new Stage('seq')
        st.step(new LambdaStep('a', { c -> c.setAttr('order', (c.getAttr('order', '') as String) + 'a') }))
        st.step(new LambdaStep('b', { c -> c.setAttr('order', (c.getAttr('order', '') as String) + 'b') }))
        st.execute(ctx)
        Assert.assertEquals('ab', ctx.getAttr('order', ''))
    }

    @Test
    void parallelStepsGoThroughScriptParallel() {
        def s = new MockScript()
        def ctx = PipelineContext.builder().script(s).build()
        def st = new Stage('par').withParallel(true)
        st.step(new LambdaStep('a', { c -> c.setAttr('a', '1') }))
        st.step(new LambdaStep('b', { c -> c.setAttr('b', '2') }))
        st.execute(ctx)
        Assert.assertEquals(1, s.parallels.size())
        Assert.assertEquals(2, s.parallels[0].blocks.size())
    }

    @Test
    void failFastSkipsAfterFailure() {
        def s = new MockScript()
        def ctx = PipelineContext.builder().script(s).build()
        def st = new Stage('fail').withFailFast(true)
        st.step(new LambdaStep('ok1', { c -> c.setAttr('hits', (c.getAttr('hits', 0) as int) + 1) }))
        st.step(new LambdaStep('bad',  { c -> throw new RuntimeException('boom') }))
        st.step(new LambdaStep('ok2', { c -> c.setAttr('hits', (c.getAttr('hits', 0) as int) + 1) }))
        try {
            st.execute(ctx)
            Assert.fail("Expected exception")
        } catch (Exception ex) {
            String all = rootMessage(ex)
            Assert.assertTrue("expected boom, got: " + all, all.contains('boom'))
        }
        // 第一步执行了，第三步未执行
        Assert.assertEquals(1, ctx.getAttr('hits', 0))
    }

    private static String rootMessage(Throwable t) {
        StringBuilder sb = new StringBuilder()
        Throwable cur = t
        while (cur != null) {
            if (cur.message) sb.append(cur.message).append(' | ')
            cur = cur.cause
        }
        return sb.toString()
    }
}
