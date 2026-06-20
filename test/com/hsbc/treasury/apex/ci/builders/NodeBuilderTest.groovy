package com.hsbc.treasury.apex.ci.builders

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.errors.BuildException
import com.hsbc.treasury.apex.ci.utils.MockScript
import org.junit.Assert
import org.junit.Test

/**
 * Node 构建器（npm / yarn / pnpm）的全场景测试。
 * 覆盖：检测、配置解析、命令拼装、参数合并、失败快速失败。
 */
class NodeBuilderTest {

    private static Map<String, Object> fresh() {
        def s = new MockScript()
        def ctx = PipelineContext.builder().script(s).build()
        return ['ctx': ctx, 's': s]
    }

    @Test
    void detectsNodeProjectByPackageJson() {
        File d = File.createTempDir()
        try {
            new File(d, 'package.json').text = '{}'
            Assert.assertTrue(new NodeBuilder().detect(d))
        } finally { d.deleteDir() }
    }

    @Test
    void doesNotDetectNodeWhenNoPackageJson() {
        File d = File.createTempDir()
        try {
            Assert.assertFalse(new NodeBuilder().detect(d))
        } finally { d.deleteDir() }
    }

    @Test
    void parseConfigHonoursPackageManager() {
        def cfg = NodeBuildConfig.fromClosure { packageManager = 'pnpm'; scripts = ['build'] }
        Assert.assertEquals('pnpm', cfg.packageManager)
        Assert.assertEquals(['build'], cfg.scripts)
    }

    @Test
    void executeRunsNpmCommand() {
        Map f = fresh()
        def ctx = f.ctx
        def s = f.s
        new NodeBuilder().execute(ctx, { packageManager = 'npm'; scripts = ['install', 'test'] })
        Assert.assertTrue(s.shCalls.size() >= 1)
        String cmd = s.shCalls[0].script as String
        Assert.assertTrue(cmd.contains('npm'))
        Assert.assertTrue(cmd.contains('install'))
        Assert.assertTrue(cmd.contains('test'))
        // npm run <script> 形式
        Assert.assertTrue(cmd.contains('run'))
    }

    @Test
    void executeRunsYarnCommand() {
        Map f = fresh()
        def ctx = f.ctx
        def s = f.s
        new NodeBuilder().execute(ctx, { packageManager = 'yarn'; scripts = ['build'] })
        String cmd = s.shCalls[0].script as String
        Assert.assertTrue(cmd.contains('yarn'))
        Assert.assertTrue(cmd.contains('build'))
    }

    @Test
    void executeRunsPnpmCommand() {
        Map f = fresh()
        def ctx = f.ctx
        def s = f.s
        new NodeBuilder().execute(ctx, { packageManager = 'pnpm'; scripts = ['ci', 'test'] })
        String cmd = s.shCalls[0].script as String
        Assert.assertTrue(cmd.contains('pnpm'))
        Assert.assertTrue(cmd.contains('ci'))
        Assert.assertTrue(cmd.contains('test'))
    }

    @Test
    void dynamicParamsAppended() {
        Map f = fresh()
        def ctx = f.ctx
        def s = f.s
        new NodeBuilder().execute(ctx, {
            packageManager = 'npm'
            scripts = ['build']
            params { flag('--legacy-peer-deps'); property('registry', 'https://r.example.com') }
        })
        String cmd = s.shCalls[0].script as String
        Assert.assertTrue(cmd.contains('--legacy-peer-deps'))
        Assert.assertTrue(cmd.contains('registry=https://r.example.com'))
    }

    @Test
    void emptyScriptsFailsFast() {
        Map f = fresh()
        def ctx = f.ctx
        try {
            new NodeBuilder().execute(ctx, { packageManager = 'npm'; scripts = [] })
            Assert.fail("expected throw")
        } catch (BuildException ex) {
            Assert.assertTrue(ex.message.contains('scripts'))
        }
    }

    @Test
    void unsupportedPackageManagerFailsFast() {
        Map f = fresh()
        def ctx = f.ctx
        try {
            new NodeBuilder().execute(ctx, { packageManager = 'bun'; scripts = ['build'] })
            Assert.fail("expected throw")
        } catch (BuildException ex) {
            Assert.assertTrue(ex.message.contains('packageManager'))
        }
    }

    @Test
    void contextAttrsRecordedAfterBuild() {
        Map f = fresh()
        def ctx = f.ctx
        new NodeBuilder().execute(ctx, { packageManager = 'yarn'; scripts = ['build'] })
        Assert.assertEquals('yarn', ctx.getAttr('node.build.pm'))
    }
}
