package com.hsbc.treasury.apex.ci.version

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.errors.ApexCIException
import com.hsbc.treasury.apex.ci.utils.MockScript
import org.junit.Assert
import org.junit.Test

class VersionManagerTest {

    private static PipelineContext freshCtx() {
        def script = new MockScript()
        return PipelineContext.builder().script(script).env([:]).build()
    }

    @Test
    void resolveBumpPatch() {
        PipelineContext ctx = freshCtx()
        String v = new VersionManager(ctx, '1.2.3', VersionManager.BumpType.PATCH).resolve()
        Assert.assertEquals('1.2.4', v)
        // 副作用：ctx 应记录 base / next / bump
        Assert.assertEquals('1.2.3', ctx.getAttr('version.base'))
        Assert.assertEquals('1.2.4', ctx.getAttr('version.next'))
        Assert.assertEquals('PATCH',  ctx.getAttr('version.bump'))
    }

    @Test
    void resolveBumpMinorClearsPreRelease() {
        PipelineContext ctx = freshCtx()
        String v = new VersionManager(ctx, '1.2.3-rc.1', VersionManager.BumpType.MINOR).resolve()
        Assert.assertEquals('1.3.0', v)
    }

    @Test
    void resolveBumpMajor() {
        String v = new VersionManager(freshCtx(), '1.9.9', VersionManager.BumpType.MAJOR).resolve()
        Assert.assertEquals('2.0.0', v)
    }

    @Test
    void resolveReleaseClearsPreRelease() {
        String v = new VersionManager(freshCtx(), '1.3.0-rc.5', VersionManager.BumpType.RELEASE).resolve()
        Assert.assertEquals('1.3.0', v)
    }

    @Test
    void resolvePrereleaseDefault() {
        // 没有 preReleaseTag → 默认 rc.1
        String v = new VersionManager(freshCtx(), '1.3.0', VersionManager.BumpType.PRERELEASE).resolve()
        Assert.assertEquals('1.3.0-rc.1', v)
    }

    @Test
    void resolvePrereleaseIncrements() {
        // 已有 rc.3 → rc.4
        String v = new VersionManager(freshCtx(), '1.3.0-rc.3', VersionManager.BumpType.PRERELEASE).resolve()
        Assert.assertEquals('1.3.0-rc.4', v)
    }

    @Test
    void resolvePrereleaseWithCustomTag() {
        // 自定义 preReleaseTag
        String v = new VersionManager(freshCtx(), '1.3.0', VersionManager.BumpType.PRERELEASE, 'beta.1').resolve()
        Assert.assertEquals('1.3.0-beta.1', v)
    }

    @Test
    void resolveAppendsBuildMeta() {
        String v = new VersionManager(freshCtx(), '1.2.3', VersionManager.BumpType.PATCH,
            null, 'abc1234').resolve()
        Assert.assertEquals('1.2.4+abc1234', v)
    }

    @Test
    void resolveKeepsBuildMetaAcrossBump() {
        // 升级 + 带 buildMeta
        String v = new VersionManager(freshCtx(), '1.2.3-rc.1+build.5', VersionManager.BumpType.MINOR,
            null, 'abc1234').resolve()
        Assert.assertEquals('1.3.0+abc1234', v)
    }

    @Test
    void resolveUpdatesManifest() {
        PipelineContext ctx = freshCtx()
        new VersionManager(ctx, '1.2.3', VersionManager.BumpType.PATCH).resolve()
        new VersionManager(ctx, '1.2.4', VersionManager.BumpType.MINOR).resolve()
        Map manifest = (Map) ctx.getAttr('version.manifest')
        Assert.assertEquals(2, manifest.size())
        Assert.assertTrue(manifest.containsKey('1.2.4'))
        Assert.assertTrue(manifest.containsKey('1.3.0'))
    }

    @Test
    void invalidBaseVersionThrows() {
        try {
            new VersionManager(freshCtx(), 'not-a-version', VersionManager.BumpType.PATCH).resolve()
            Assert.fail("expected throw")
        } catch (ApexCIException ignore) {}
    }

    @Test
    void autoReadsEnvAndResolves() {
        // 模拟 BUILD_VERSION=2.0.0; BUMP_TYPE=major; BUILD_META=abc
        // 注：env 是不可变 map，需要通过 withEnv 派生
        PipelineContext baseCtx = freshCtx()
        PipelineContext ctx = baseCtx.withEnv([
            'BUILD_VERSION': '2.0.0',
            'BUMP_TYPE':     'major',
            'BUILD_META':    'abc'
        ])
        String v = VersionManager.auto(ctx).resolve()
        Assert.assertEquals('3.0.0+abc', v)
    }

    @Test
    void autoRequiresBuildVersionEnv() {
        try {
            VersionManager.auto(freshCtx()).resolve()
            Assert.fail("expected throw")
        } catch (ApexCIException ignore) {}
    }
}
