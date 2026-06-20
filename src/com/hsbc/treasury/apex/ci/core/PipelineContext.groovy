package com.hsbc.treasury.apex.ci.core

import com.hsbc.treasury.apex.ci.errors.ApexCIException

import java.util.concurrent.ConcurrentHashMap

/**
 * pipeline 阶段/步骤共享的不可变上下文。
 *
 * - script: Jenkins 提供的 CPS 脚本代理（用于 sh / echo / archiveArtifacts 等）
 * - env:    注入的环境变量
 * - params: 字符串参数 (来自 Jenkins 作业 / multi-branch)
 * - workDir: 工作目录
 * - attrs:   跨 stage 共享的业务数据（弱类型 Map）
 */

class PipelineContext implements Serializable {
    private static final long serialVersionUID = 1L

    final Object script
    final String workDir
    final Map<String, String> env
    final Map<String, Object> params
    final Map<String, Object> attrs
    final Sleeper sleeper
    final String nodeLabel
    final long startedAt

    private PipelineContext(PipelineContextBuilder b) {
        this.script = b.script
        String resolvedWork = b.workDir
        if (resolvedWork == null && b.script != null) {
            try {
                if (b.script.respondsTo('pwd')) {
                    resolvedWork = b.script.pwd()?.toString()
                }
            } catch (Throwable ignore) { }
        }
        this.workDir = resolvedWork ?: '.'
        this.env = Collections.unmodifiableMap(new LinkedHashMap<>(b.env ?: [:]))
        this.params = Collections.unmodifiableMap(new LinkedHashMap<>(b.params ?: [:]))
        this.attrs = new ConcurrentHashMap<>(b.attrs ?: [:])
        this.sleeper = b.sleeper ?: new NoOpSleeper()
        this.nodeLabel = b.nodeLabel
        this.startedAt = System.currentTimeMillis()
    }

    static PipelineContextBuilder builder() { new PipelineContextBuilder() }

    /** 业务方在 stage 之间传值 */
    void setAttr(String k, Object v) { attrs.put(k, v) }
    Object getAttr(String k)         { return attrs.get(k) }
    Object getAttr(String k, Object defaultValue) { return attrs.getOrDefault(k, defaultValue) }
    boolean hasAttr(String k)        { return attrs.containsKey(k) }

    /** 合并 env（如 docker run 注入 KUBECONFIG） */
    PipelineContext withEnv(Map<String, String> more) {
        def merged = new LinkedHashMap<String, String>()
        merged.putAll(this.env)
        merged.putAll(more ?: [:])
        PipelineContextBuilder b = builder()
        b.script = this.script
        b.workDir = this.workDir
        b.env = merged
        b.params = this.params
        b.attrs = this.attrs
        b.sleeper = this.sleeper
        b.nodeLabel = this.nodeLabel
        return b.build()
    }
}
