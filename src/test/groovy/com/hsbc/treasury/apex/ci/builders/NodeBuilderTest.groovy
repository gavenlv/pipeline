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
        // shCalls[0] = implicit install step, shCalls[1] = first script, ...
        // Windows 上 platformAdapt 会把 'npm' 变成 'npm.cmd'
        String installCmd = s.shCalls[0].script as String
        Assert.assertTrue("expected npm in install cmd: ${installCmd}".toString(), installCmd.contains('npm'))
        Assert.assertTrue(installCmd.contains('install'))
        // 验证 scripts 中的每个项都通过 `npm run <name>` 执行
        // 渲染后的 bash 脚本把每个 arg 单独成行，所以按行检查
        boolean hasRunInstall = s.shCalls.any { Map call ->
            String c = call.script as String
            (c.contains("'npm'") || c.contains("'npm.cmd'")) && c.contains("'run'") && c.contains("'install'")
        }
        boolean hasRunTest = s.shCalls.any { Map call ->
            String c = call.script as String
            (c.contains("'npm'") || c.contains("'npm.cmd'")) && c.contains("'run'") && c.contains("'test'")
        }
        Assert.assertTrue("expected `npm run install` in one of:\n${s.shCalls*.script}".toString(), hasRunInstall)
        Assert.assertTrue("expected `npm run test` in one of:\n${s.shCalls*.script}".toString(), hasRunTest)
    }

    @Test
    void executeRunsYarnCommand() {
        Map f = fresh()
        def ctx = f.ctx
        def s = f.s
        new NodeBuilder().execute(ctx, { packageManager = 'yarn'; scripts = ['build'] })
        boolean hasYarnBuild = s.shCalls.any { Map call ->
            String c = call.script as String
            (c.contains("'yarn'") || c.contains("'yarn.cmd'")) && c.contains("'run'") && c.contains("'build'")
        }
        Assert.assertTrue("expected `yarn run build` in one of:\n${s.shCalls*.script}".toString(), hasYarnBuild)
    }

    @Test
    void executeRunsPnpmCommand() {
        Map f = fresh()
        def ctx = f.ctx
        def s = f.s
        new NodeBuilder().execute(ctx, { packageManager = 'pnpm'; scripts = ['ci', 'test'] })
        boolean hasRunCi = s.shCalls.any { Map call ->
            String c = call.script as String
            (c.contains("'pnpm'") || c.contains("'pnpm.cmd'")) && c.contains("'run'") && c.contains("'ci'")
        }
        boolean hasRunTest = s.shCalls.any { Map call ->
            String c = call.script as String
            (c.contains("'pnpm'") || c.contains("'pnpm.cmd'")) && c.contains("'run'") && c.contains("'test'")
        }
        Assert.assertTrue("expected `pnpm run ci` in one of:\n${s.shCalls*.script}".toString(), hasRunCi)
        Assert.assertTrue("expected `pnpm run test` in one of:\n${s.shCalls*.script}".toString(), hasRunTest)
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
        // 找最后一条 `npm run build` 调用
        String allCmds = s.shCalls.collect { it.script as String }.join('\n')
        Assert.assertTrue(allCmds.contains('--legacy-peer-deps'))
        Assert.assertTrue(allCmds.contains('registry=https://r.example.com'))
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
