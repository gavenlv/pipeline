package com.hsbc.treasury.apex.ci.builders

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.errors.BuildException
import com.hsbc.treasury.apex.ci.utils.MockScript
import org.junit.Assert
import org.junit.Test

/**
 * Go 构建器的全场景测试。
 */
class GoBuilderTest {

    private static Map<String, Object> fresh() {
        def s = new MockScript()
        def ctx = PipelineContext.builder().script(s).build()
        return ['ctx': ctx, 's': s]
    }

    @Test
    void detectsGoProjectByGoMod() {
        File d = File.createTempDir()
        try {
            new File(d, 'go.mod').text = 'module x'
            Assert.assertTrue(new GoBuilder().detect(d))
        } finally { d.deleteDir() }
    }

    @Test
    void doesNotDetectGoWhenNoGoMod() {
        File d = File.createTempDir()
        try {
            Assert.assertFalse(new GoBuilder().detect(d))
        } finally { d.deleteDir() }
    }

    @Test
    void buildCommandIncludesPackage() {
        Map f = fresh()
        def ctx = f.ctx
        def s = f.s
        new GoBuilder().execute(ctx, {
            commands = ['build', 'test']
            mainPackage = './...'
        })
        String cmd = s.shCalls[0].script as String
        Assert.assertTrue(cmd.contains('go'))
        Assert.assertTrue(cmd.contains('build'))
        Assert.assertTrue(cmd.contains('test'))
        Assert.assertTrue(cmd.contains('./...'))
    }

    @Test
    void testCommandAddsRaceFlag() {
        Map f = fresh()
        def ctx = f.ctx
        def s = f.s
        new GoBuilder().execute(ctx, {
            commands = ['test']
            withRace = true
            mainPackage = './...'
        })
        String cmd = s.shCalls[0].script as String
        Assert.assertTrue(cmd.contains('test'))
        Assert.assertTrue(cmd.contains('-race'))
    }

    @Test
    void vetCommandIncludesPackage() {
        Map f = fresh()
        def ctx = f.ctx
        def s = f.s
        new GoBuilder().execute(ctx, {
            commands = ['vet']
            mainPackage = './pkg/...'
        })
        String cmd = s.shCalls[0].script as String
        Assert.assertTrue(cmd.contains('vet'))
        Assert.assertTrue(cmd.contains('./pkg/...'))
    }

    @Test
    void emptyCommandsFailsFast() {
        Map f = fresh()
        def ctx = f.ctx
        try {
            new GoBuilder().execute(ctx, { commands = [] })
            Assert.fail("expected throw")
        } catch (BuildException ex) {
            Assert.assertTrue(ex.message.contains('commands'))
        }
    }

    @Test
    void dynamicParamsAppended() {
        Map f = fresh()
        def ctx = f.ctx
        def s = f.s
        new GoBuilder().execute(ctx, {
            commands = ['build']
            mainPackage = '.'
            params { flag('-v'); property('tags', 'integration') }
        })
        String cmd = s.shCalls[0].script as String
        Assert.assertTrue(cmd.contains('-v'))
        Assert.assertTrue(cmd.contains('tags=integration'))
    }

    @Test
    void contextAttrsRecordedAfterBuild() {
        Map f = fresh()
        def ctx = f.ctx
        new GoBuilder().execute(ctx, {
            moduleName = 'github.com/x/y'
            commands = ['build']
        })
        Assert.assertEquals('github.com/x/y', ctx.getAttr('go.build.module'))
    }
}
