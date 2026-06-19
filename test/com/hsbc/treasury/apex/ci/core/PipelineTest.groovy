package com.hsbc.treasury.apex.ci.core

import com.hsbc.treasury.apex.ci.utils.MockScript
import org.junit.Test
import org.junit.Assert

class PipelineTest {

    @Test
    void runsStagesInOrder() {
        def s = new MockScript()
        def ctx = PipelineContext.builder().script(s).build()
        def p = Pipeline.builder()
                .name('test')
                .stage('S1', { it.step(new LambdaStep('a', { c -> c.setAttr('order', (c.getAttr('order', '') as String) + '1') })) })
                .stage('S2', { it.step(new LambdaStep('b', { c -> c.setAttr('order', (c.getAttr('order', '') as String) + '2') })) })
                .build()
        p.run(ctx)
        Assert.assertEquals('12', ctx.getAttr('order', ''))
        Assert.assertEquals(['S1', 'S2'], p.stageNames())
        // 模拟 script.stage 被调用过两次
        Assert.assertTrue(s.stages.size() >= 2)
    }
}
