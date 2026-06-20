package com.hsbc.treasury.apex.ci.core

class PipelineBuilder implements Serializable {
    private static final long serialVersionUID = 1L
    String name
    String description
    boolean failFast = true
    List<Stage> stages = []

    PipelineBuilder name(String n)              { this.name = n; return this }
    PipelineBuilder description(String d)       { this.description = d; return this }
    PipelineBuilder withFailFast(boolean f)     { this.failFast = f; return this }

    PipelineBuilder stage(String name, Closure body) {
        Stage s = new Stage(name)
        body.delegate = new StageSpec(s)
        body.resolveStrategy = Closure.DELEGATE_FIRST
        body.call(s)
        this.stages << s
        return this
    }

    Pipeline build() { return new Pipeline(this) }
}
