package com.hsbc.treasury.apex.ci.version

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.errors.ApexCIException

/**
 * 自动版本管理（auto upgrade）。
 *
 * 设计目标：
 *  - 业务方在 Jenkinsfile 里声明 "我要发布的版本"，库根据规则自动计算。
 *  - 计算规则：BumpType（patch / minor / major / release / prerelease）+ BaseVersion
 *  - 把计算结果记录到 PipelineContext.attrs，供后续 Docker tag / 制品版本使用。
 *  - 可选把决策写入文件（保存到 attr "version.manifest"），方便审计。
 *
 * 典型场景：
 *  - main 分支 + patch       → 1.2.3 → 1.2.4
 *  - main 分支 + minor       → 1.2.3 → 1.3.0
 *  - feature 分支 + prerelease→ 1.2.3 → 1.3.0-rc.1.feature-x
 *  - release 分支 + release  → 1.3.0-rc.2 → 1.3.0
 *
 * 所有计算都走 Java 端，不依赖任何外部命令。沙箱安全。
 */
class VersionManager implements Serializable {
    private static final long serialVersionUID = 1L

    /** bump 类型 */
    enum BumpType {
        PATCH, MINOR, MAJOR, RELEASE, PRERELEASE
    }

    final PipelineContext ctx
    final String baseVersion
    final BumpType bump
    final String preReleaseTag
    final String buildMeta

    private SemVer resolved

    VersionManager(PipelineContext ctx, String baseVersion, BumpType bump,
                   String preReleaseTag = null, String buildMeta = null) {
        this.ctx = ctx
        this.baseVersion = baseVersion
        this.bump = (bump != null) ? bump : BumpType.PATCH
        this.preReleaseTag = preReleaseTag
        this.buildMeta = buildMeta
    }

    /** 入口：从配置计算下一个版本，返回最终字符串 */
    String resolve() {
        if (resolved != null) return resolved.toString()
        SemVer base = SemVer.parse(baseVersion)
        SemVer next = applyBump(base)
        if (buildMeta != null && !buildMeta.isEmpty()) {
            next = next.withBuild(buildMeta)
        }
        resolved = next
        // 记录到 ctx 与 manifest
        if (ctx != null) {
            ctx.setAttr("version.base", base.toString())
            ctx.setAttr("version.next", next.toString())
            ctx.setAttr("version.bump", bump.name())
            Map<String, Object> manifest = (Map<String, Object>) ctx.getAttr("version.manifest", [:])
            manifest[next.toString()] = [
                base    : base.toString(),
                bump    : bump.name(),
                resolvedAt: System.currentTimeMillis()
            ]
            ctx.setAttr("version.manifest", manifest)
            ctx.log("==> [VersionManager] ${base} → ${next} (${bump.name()})".toString())
        }
        return next.toString()
    }

    /** 比较接口暴露，方便测试和业务方条件分支 */
    SemVer getResolved() { resolve(); return resolved }

    private SemVer applyBump(SemVer base) {
        switch (bump) {
            case BumpType.PATCH:
                return base.bumpPatch()
            case BumpType.MINOR:
                return base.bumpMinor()
            case BumpType.MAJOR:
                return base.bumpMajor()
            case BumpType.RELEASE:
                return base.toRelease()
            case BumpType.PRERELEASE:
                String pre = (preReleaseTag != null) ? preReleaseTag : "rc.${(base.pre == null) ? 1 : bumpPreReleaseNumber(base) + 1}".toString()
                return base.withPreRelease(pre)
            default:
                throw new ApexCIException("Unsupported BumpType: ${bump}".toString())
        }
    }

    /** 解析已有预发布段的末尾数字，例如 rc.3 → 3 */
    private static int bumpPreReleaseNumber(SemVer base) {
        if (base.pre == null) return 0
        int dot = base.pre.lastIndexOf('.')
        if (dot < 0) return 0
        try {
            return Integer.parseInt(base.pre.substring(dot + 1))
        } catch (NumberFormatException ignore) {
            return 0
        }
    }

    // ============================================================
    // 静态便捷构造（DSL 友好）
    // ============================================================

    /** 从环境变量 BUILD_VERSION / BUMP_TYPE 自动推断 */
    static VersionManager auto(PipelineContext ctx) {
        String base = (ctx?.env?.get('BUILD_VERSION') ?: ctx?.env?.get('BASE_VERSION'))?.toString()
        String bump = (ctx?.env?.get('BUMP_TYPE') ?: 'patch').toString()
        String pre  = ctx?.env?.get('PRERELEASE_TAG')?.toString()
        String meta = (ctx?.env?.get('BUILD_META') ?: ctx?.env?.get('GIT_COMMIT_SHORT'))?.toString()
        if (base == null) throw new ApexCIException("VersionManager.auto: env BUILD_VERSION is required")
        return new VersionManager(ctx, base, BumpType.valueOf(bump.toUpperCase()), pre, meta)
    }
}
