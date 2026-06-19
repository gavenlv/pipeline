package com.hsbc.treasury.apex.ci.core


/**
 * 闭包形式的轻量 Step，便于快速嵌入。
 * Sandbox-safe 等级默认 high，调用方需保证闭包无 eval。
 */

class LambdaStep implements Step<Object>, Serializable {
    private static final long serialVersionUID = 1L

    final String name
    private final Closure body

    LambdaStep(String name, Closure body) {
        this.name = name
        this.body = body
    }

    @Override
    String getName() { return name }

    @Override
    boolean isSandboxSafe() { return true }

    @Override
    Object run(PipelineContext ctx) { return body.call(ctx) }
}
