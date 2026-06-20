package com.hsbc.treasury.apex.ci.core

import com.hsbc.treasury.apex.ci.errors.ApexCIException

/**
 * 顶层 Pipeline 容器：装配 stages、并行运行、收集结果。
 * 用法：
 *   def p = Pipeline.builder().name('main')
 *                       .stage('Build',  { it.step(new JavaBuildStep(...)) })
 *                       .stage('Test',   { it.step(new TestStep(...)) })
 *                       .stage('Scans',  { it.withParallel(true); it.step(new SastStep(...)) })
 *                       .build()
 *   p.run(ctx)
 */

class Pipeline implements Serializable {
    private static final long serialVersionUID = 1L

    final String name
    private final List<Stage> stages = []
    private boolean failFast = true
    private String description

    private Pipeline(PipelineBuilder b) {
        this.name = b.name ?: 'apex-pipeline'
        this.failFast = b.failFast
        this.description = b.description
        this.stages.addAll(b.stages)
    }

    static PipelineBuilder builder() { return new PipelineBuilder() }

    void run(PipelineContext ctx) {
        long t0 = System.currentTimeMillis()
        ctx.script?.echo("==> [Pipeline ${name}] start (stages=${stages.size()})")
        for (Stage s : stages) {
            try {
                long st0 = System.currentTimeMillis()
                if (ctx.script != null && ctx.script.respondsTo('stage')) {
                    ctx.script.stage(s.name) { s.execute(ctx) }
                } else {
                    s.execute(ctx)
                }
                ctx.script?.echo("==> [${s.name}] OK in ${System.currentTimeMillis() - st0}ms")
            } catch (Throwable t) {
                if (failFast) {
                    throw new ApexCIException("Pipeline ${name} failed at stage ${s.name}: ${t.message}", t)
                } else {
                    ctx.script?.echo("[WARN] stage ${s.name} failed: ${t.message}")
                }
            }
        }
        ctx.script?.echo("==> [Pipeline ${name}] done in ${System.currentTimeMillis() - t0}ms")
    }

    List<String> stageNames() { stages.collect { it.name } }

    List<Stage> getStages() { return stages }
    boolean isFailFast() { return failFast }
    String getDescription() { return description }
}
