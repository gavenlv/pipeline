package com.hsbc.treasury.apex.ci.core

class StageSpec implements Serializable {
    private static final long serialVersionUID = 1L
    final Stage stage
    StageSpec(Stage s) { this.stage = s }

    void step(Step s) { stage.steps << s }
    void step(String name, Closure body) { stage.steps << new LambdaStep(name, body) }
    void withFailFast(boolean f) { stage.failFast = f }
    void withParallel(boolean p) { stage.parallel = p }
    void withRetry(Retry r)     { stage.retry = r }
}
