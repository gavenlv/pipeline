package com.hsbc.treasury.apex.ci.core

import com.hsbc.treasury.apex.ci.errors.ApexCIException

/**
 * 阶段：可顺序或并行执行多个 Step。
 * 并行执行在 Jenkins 中走 script.parallel() 沙箱友好模式。
 */

class Stage implements Serializable {
    private static final long serialVersionUID = 1L

    final String name
    private final List<Step> steps = []
    private boolean failFast = true
    private Retry retry = null
    private boolean parallel = false

    Stage(String name) { this.name = name }

    Retry getRetry() {
        if (retry == null) {
            retry = new Retry(maxAttempts: 1, initialDelayMs: 0L)
        }
        return retry
    }

    Stage step(Step s) {
        steps << s
        return this
    }

    Stage steps(Closure body) {
        body.delegate = new StageSpec(this)
        body.resolveStrategy = Closure.DELEGATE_FIRST
        body()
        return this
    }

    Stage withFailFast(boolean f) { this.failFast = f; return this }
    Stage withParallel(boolean p) { this.parallel = p; return this }
    Stage withRetry(Retry r)     { this.retry = r; return this }

    int size() { steps.size() }

    List<Step> getSteps() { return steps }
    boolean getParallel() { return parallel }
    boolean getFailFast() { return failFast }

    /** 在给定的 script 上真正执行（沙箱安全） */
    void execute(PipelineContext ctx) {
        if (steps.isEmpty()) return
        if (parallel && steps.size() > 1) {
            def blocks = [:]
            steps.eachWithIndex { Step s, int i ->
                blocks["${name}-${i}-${s.name}".toString()] = {
                    try { s.run(ctx) } catch (Throwable t) {
                        if (failFast) { throw t } else { ctx.script?.echo("[WARN] ${s.name} failed: ${t.message}") }
                    }
                }
            }
            ctx.script.parallel(blocks)
        } else {
            for (Step s : steps) {
                try {
                    Retry.execute(retry, { s.run(ctx) } as Closure)
                } catch (Throwable t) {
                    if (failFast) {
                        throw new ApexCIException("Stage ${name} step ${s.name} failed: ${t.message}", t)
                    } else {
                        ctx.script?.echo("[WARN] ${s.name} failed: ${t.message}")
                    }
                }
            }
        }
    }
}
