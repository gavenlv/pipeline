package com.hsbc.treasury.apex.ci.core

import com.hsbc.treasury.apex.ci.errors.ApexCIException

import java.util.concurrent.ConcurrentHashMap

/**
 * 轻量级 PipelineContext：仅作为 script 代理 + 业务数据容器。
 *
 * 在 Jenkins CPS 环境下，调用方应使用：
 *   stage('X') { sh '...' }            // 原生 step
 *   apex { ... }                        // 注入共享 ctx
 *
 * 本类只解决"如何在闭包之间共享 script / env / attrs"。
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
                if (b.script.metaClass.respondsTo(b.script, 'pwd')) {
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

    /** 合并 env 返回新 ctx（保持不可变） */
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

    /** 委托 script 执行 echo（不持有 script 的代码中使用） */
    void log(String message) {
        if (script != null && script.metaClass.respondsTo(script, 'echo')) {
            script.echo(message?.toString() ?: '')
        }
    }
}
