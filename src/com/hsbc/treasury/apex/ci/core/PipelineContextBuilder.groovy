package com.hsbc.treasury.apex.ci.core

class PipelineContextBuilder implements Serializable {
    private static final long serialVersionUID = 1L
    Object script
    String workDir
    Map<String, String> env = [:]
    Map<String, Object> params = [:]
    Map<String, Object> attrs = [:]
    Sleeper sleeper
    String nodeLabel

    PipelineContextBuilder script(Object s)        { this.script = s; return this }
    PipelineContextBuilder workDir(String d)       { this.workDir = d; return this }
    PipelineContextBuilder env(Map<String, String> e) { this.env = e; return this }
    PipelineContextBuilder params(Map<String, Object> p) { this.params = p; return this }
    PipelineContextBuilder attrs(Map<String, Object> a)  { this.attrs = a; return this }
    PipelineContextBuilder sleeper(Sleeper s)      { this.sleeper = s; return this }
    PipelineContextBuilder nodeLabel(String l)     { this.nodeLabel = l; return this }
    PipelineContext build()         { return new PipelineContext(this) }
}
