package com.hsbc.treasury.apex.ci.integration

import com.hsbc.treasury.apex.ci.builders.BuilderFactory
import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.scanners.ScanResult
import com.hsbc.treasury.apex.ci.scanners.ScanRunner
import com.hsbc.treasury.apex.ci.utils.MockScript
import com.hsbc.treasury.apex.ci.version.SemVer
import com.hsbc.treasury.apex.ci.version.VersionManager
import org.junit.Assert
import org.junit.Test

/**
 * 自动版本管理（auto upgrade）集成测试。
 *
 * 场景：完整模拟一个发布流水线——
 *  1. 拉代码 → 读取 BUILD_VERSION 环境变量
 *  2. 根据 BUMP_TYPE 计算下一个版本
 *  3. 触发构建（Java + Node 多语言）
 *  4. 触发扫描（SAST + SCA + container）
 *  5. 门禁通过后，把版本号写入 manifest
 *  6. 业务方基于 version.next 决定 Docker tag / 制品版本
 */
class VersionUpgradeIntegrationTest {

    @Test
    void patchBumpOnMainBranch() {
        def script = new MockScript()
        PipelineContext baseCtx = PipelineContext.builder().script(script).build()
        PipelineContext ctx = baseCtx.withEnv([
            'BUILD_VERSION': '1.2.3',
            'BUMP_TYPE':     'patch',
            'BUILD_META':    'abc1234'
        ])

        // 业务方：阶段 1 计算版本
        String next = VersionManager.auto(ctx).resolve()
        Assert.assertEquals('1.2.4+abc1234', next)
        Assert.assertEquals('1.2.3', ctx.getAttr('version.base'))
        Assert.assertEquals('1.2.4+abc1234', ctx.getAttr('version.next'))

        // 阶段 2 build（多语言）
        BuilderFactory.of('java').execute(ctx, {
            buildTool = 'maven'
            goals = ['clean', 'verify']
        }, [:])
        BuilderFactory.of('node').execute(ctx, {
            packageManager = 'npm'
            scripts = ['install', 'test', 'build']
        }, [:])

        // 阶段 3 scan
        ScanRunner runner = new ScanRunner(script: script, ctx: ctx)
        runner.sast { -> new ScanResult(scanner: 'sast', status: 'OK', high: 0) }
        runner.sca  { -> new ScanResult(scanner: 'sca',  status: 'OK', high: 0) }
        runner.container { -> new ScanResult(scanner: 'container', status: 'OK', high: 0) }
        runner.failOn = ['high']
        runner.run()
        runner.assertPassed()

        // 阶段 4 用 version.next 作为 Docker tag
        String dockerTag = ctx.getAttr('version.next') as String
        Assert.assertEquals('1.2.4+abc1234', dockerTag)
    }

    @Test
    void prereleaseBumpOnFeatureBranch() {
        def script = new MockScript()
        PipelineContext ctx = PipelineContext.builder().script(script).build()
        ctx = ctx.withEnv([
            'BUILD_VERSION':   '1.3.0',
            'BUMP_TYPE':       'prerelease',
            'PRERELEASE_TAG':  'rc.feature-x',
            'BUILD_META':      'gitsha-001'
        ])

        String next = VersionManager.auto(ctx).resolve()
        Assert.assertEquals('1.3.0-rc.feature-x+gitsha-001', next)
    }

    @Test
    void releaseBumpClearsPreRelease() {
        def script = new MockScript()
        PipelineContext ctx = PipelineContext.builder().script(script).build()
        ctx = ctx.withEnv([
            'BUILD_VERSION': '2.0.0-rc.5',
            'BUMP_TYPE':     'release',
            'BUILD_META':    'main'
        ])

        String next = VersionManager.auto(ctx).resolve()
        Assert.assertEquals('2.0.0+main', next)
    }

    @Test
    void majorBumpResetsMinorPatch() {
        def script = new MockScript()
        PipelineContext ctx = PipelineContext.builder().script(script).build()
        ctx = ctx.withEnv([
            'BUILD_VERSION': '1.9.99',
            'BUMP_TYPE':     'major'
        ])

        String next = VersionManager.auto(ctx).resolve()
        Assert.assertEquals('2.0.0', next)
    }

    @Test
    void manifestRecordsAllDecisions() {
        def script = new MockScript()
        PipelineContext ctx = PipelineContext.builder().script(script).build()

        // 多次升级
        new VersionManager(ctx, '1.0.0', VersionManager.BumpType.PATCH).resolve()
        new VersionManager(ctx, '1.0.1', VersionManager.BumpType.MINOR).resolve()
        new VersionManager(ctx, '1.1.0', VersionManager.BumpType.MAJOR).resolve()

        Map manifest = (Map) ctx.getAttr('version.manifest')
        Assert.assertEquals(3, manifest.size())
        Assert.assertTrue(manifest.containsKey('1.0.1'))
        Assert.assertTrue(manifest.containsKey('1.1.0'))
        Assert.assertTrue(manifest.containsKey('2.0.0'))

        // 每个决策都记录了 base / bump / resolvedAt
        def entry = manifest['2.0.0']
        Assert.assertEquals('1.1.0', entry['base'])
        Assert.assertEquals('MAJOR',  entry['bump'])
        Assert.assertNotNull(entry['resolvedAt'])
    }

    @Test
    void semverComparisonForUpgradeEligibility() {
        // 业务方：先检查现有 release 版本，再决定是否升级
        SemVer current = SemVer.parse('1.2.3')
        SemVer latest  = SemVer.parse('1.3.0')
        SemVer pre     = SemVer.parse('1.3.0-rc.1')

        // 1.3.0 > 1.2.3：业务方应升级
        Assert.assertTrue(latest > current)
        // 1.3.0 > 1.3.0-rc.1：预发布 < 正式（SemVer 2.0.0 §11）
        Assert.assertTrue(latest > pre)
        Assert.assertTrue(pre < latest)
        // 1.3.0-rc.1 > 1.2.3：预发布有更高的 major.minor
        Assert.assertTrue(pre > current)
        Assert.assertTrue(current.compareTo(pre) < 0)

        // 升级判定：latest 比 current 新
        boolean shouldUpgrade = latest.compareTo(current) > 0
        Assert.assertTrue(shouldUpgrade)
    }

    @Test
    void semverBuildMetadataIgnoredInComparison() {
        // SemVer 2.0.0 §10：build 段不参与比较
        SemVer a = SemVer.parse('1.0.0+build.1')
        SemVer b = SemVer.parse('1.0.0+build.999')
        Assert.assertEquals(0, a.compareTo(b))
    }

    @Test
    void autoUpgradeWhenEnvMissingThrows() {
        // BUILD_VERSION 缺失时，明确报错（避免静默错误）
        def script = new MockScript()
        PipelineContext ctx = PipelineContext.builder().script(script).build()
        try {
            VersionManager.auto(ctx).resolve()
            Assert.fail("expected throw")
        } catch (com.hsbc.treasury.apex.ci.errors.ApexCIException ex) {
            Assert.assertTrue(ex.message.contains('BUILD_VERSION'))
        }
    }

    @Test
    void manifestPersistsAcrossBuilders() {
        // 验证：manifest 在多个 stage 中累积
        def script = new MockScript()
        PipelineContext ctx = PipelineContext.builder().script(script).build()

        // 阶段 1：算版本
        String v1 = new VersionManager(ctx, '1.0.0', VersionManager.BumpType.PATCH).resolve()
        Assert.assertEquals('1.0.1', v1)

        // 阶段 2：build 完成后，attr 还在
        BuilderFactory.of('java').execute(ctx, {
            buildTool = 'maven'
            goals = ['verify']
        }, [:])

        // 阶段 3：再算一次（升级 rc.1）
        String v2 = new VersionManager(ctx, '1.0.1', VersionManager.BumpType.PRERELEASE, 'rc.1').resolve()
        Assert.assertEquals('1.0.1-rc.1', v2)

        // manifest 应有 2 条
        Map manifest = (Map) ctx.getAttr('version.manifest')
        Assert.assertEquals(2, manifest.size())
    }
}
