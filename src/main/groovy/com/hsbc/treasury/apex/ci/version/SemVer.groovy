package com.hsbc.treasury.apex.ci.version

import com.hsbc.treasury.apex.ci.errors.ApexCIException

/**
 * 语义化版本（Semantic Versioning 2.0.0）的解析与比较工具。
 *
 *   1.2.3                  → 正式版
 *   1.2.3-rc.1             → 预发布：包含 alpha / beta / rc / pre
 *   1.2.3-rc.1+build.42    → 元数据：构建号 / commit sha
 *
 * 不实现 SemVer 2.0.0 的"构建元数据参与比较"的规则——我们只把 build 段当字符串拼接。
 */
class SemVer implements Comparable<SemVer>, Serializable {
    private static final long serialVersionUID = 1L

    final int major
    final int minor
    final int patch
    final String pre          // 预发布段（如 rc.1 / beta.2）；可为 null
    final String buildMeta    // 构建元数据（如 build.42 / git sha）；可为 null

    SemVer(int major, int minor, int patch, String preRelease = null, String build = null) {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new ApexCIException("SemVer parts must be non-negative: ${major}.${minor}.${patch}".toString())
        }
        this.major = major
        this.minor = minor
        this.patch = patch
        this.pre = (preRelease != null && !preRelease.trim().isEmpty()) ? preRelease.trim() : null
        this.buildMeta = (build != null && !build.trim().isEmpty()) ? build.trim() : null
    }

    /** 解析 '1.2.3-rc.1+build.42' → SemVer */
    static SemVer parse(String text) {
        if (text == null) throw new ApexCIException("SemVer text is null")
        String s = text.trim()
        if (s.startsWith('v') || s.startsWith('V')) s = s.substring(1)
        String build = null
        int plus = s.indexOf('+')
        if (plus >= 0) {
            build = s.substring(plus + 1)
            s = s.substring(0, plus)
        }
        String pre = null
        int dash = s.indexOf('-')
        if (dash >= 0) {
            pre = s.substring(dash + 1)
            s = s.substring(0, dash)
        }
        String[] parts = s.split(/\./)
        if (parts.length != 3) {
            throw new ApexCIException("SemVer must be major.minor.patch: ${text}".toString())
        }
        try {
            int ma = Integer.parseInt(parts[0])
            int mi = Integer.parseInt(parts[1])
            int pa = Integer.parseInt(parts[2])
            return new SemVer(ma, mi, pa, pre, build)
        } catch (NumberFormatException nfe) {
            throw new ApexCIException("SemVer parts must be integers: ${text}".toString())
        }
    }

    /** 安全解析：无法解析时返回 null 而非抛错 */
    static SemVer tryParse(String text) {
        try { return parse(text) } catch (Throwable ignore) { return null }
    }

    /** 是否为预发布（含 pre / alpha / beta / rc / dev） */
    boolean isPreRelease() { return pre != null }

    /** 升 major：清零 minor/patch，预发布段清空 */
    SemVer bumpMajor()      { return new SemVer(major + 1, 0, 0, null, buildMeta) }
    /** 升 minor：清零 patch */
    SemVer bumpMinor()      { return new SemVer(major, minor + 1, 0, null, buildMeta) }
    /** 升 patch：仅 patch+1 */
    SemVer bumpPatch()      { return new SemVer(major, minor, patch + 1, null, buildMeta) }

    /** 升为预发布：相同 major.minor.patch，加上 preRelease 段 */
    SemVer withPreRelease(String pre) { return new SemVer(major, minor, patch, pre, buildMeta) }

    /** 升为正式版：清空 preRelease 段 */
    SemVer toRelease()      { return new SemVer(major, minor, patch, null, buildMeta) }

    /** 附加 build 段（不参与比较，仅字符串拼接） */
    SemVer withBuild(String b) { return new SemVer(major, minor, patch, pre, b) }

    /** 去掉所有修饰段，只留 major.minor.patch */
    SemVer toCore() { return new SemVer(major, minor, patch) }

    @Override
    int compareTo(SemVer other) {
        if (other == null) return 1
        int c = Integer.compare(this.major, other.major)
        if (c != 0) return c
        c = Integer.compare(this.minor, other.minor)
        if (c != 0) return c
        c = Integer.compare(this.patch, other.patch)
        if (c != 0) return c
        // 预发布版本低于正式版（SemVer 2.0.0 §11）
        if (this.pre == null && other.pre != null) return 1
        if (this.pre != null && other.pre == null) return -1
        if (this.pre != null && other.pre != null) {
            return this.pre.compareTo(other.pre)
        }
        return 0
    }

    @Override
    String toString() {
        StringBuilder sb = new StringBuilder()
        sb.append(major).append('.').append(minor).append('.').append(patch)
        if (pre != null) sb.append('-').append(pre)
        if (buildMeta != null) sb.append('+').append(buildMeta)
        return sb.toString()
    }

    @Override
    boolean equals(Object obj) {
        if (this.is(obj)) return true
        if (!(obj instanceof SemVer)) return false
        return this.compareTo((SemVer) obj) == 0
    }

    @Override
    int hashCode() {
        int h = major * 31 + minor
        h = h * 31 + patch
        if (pre != null) h = h * 31 + pre.hashCode()
        return h
    }
}
