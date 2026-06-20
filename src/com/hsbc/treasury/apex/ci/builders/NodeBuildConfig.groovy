package com.hsbc.treasury.apex.ci.builders

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.core.DynamicParams
import com.hsbc.treasury.apex.ci.utils.Sandbox
import com.hsbc.treasury.apex.ci.errors.BuildException

/**
 * Node / npm / yarn / pnpm 构建配置。
 */

class NodeBuildConfig implements Serializable {
    private static final long serialVersionUID = 1L

    int nodeVersion = 20
    String packageManager = 'npm'       // npm | yarn | pnpm
    List<String> scripts = ['install', 'build', 'test']
    String registry = ''
    boolean useCache = true
    DynamicParams params = new DynamicParams()

    void setParams(DynamicParams p) { this.params = (p != null) ? p : new DynamicParams() }

    /** 显式接受 Closure 的方法，支持 `params { ... }` 块。 */
    Object params(Closure body) {
        if (this.params == null) this.params = new DynamicParams()
        body.delegate = this.params
        body.resolveStrategy = Closure.DELEGATE_FIRST
        body()
        return this
    }

    void setParams(Closure body) {
        if (this.params == null) this.params = new DynamicParams()
        body.delegate = this.params
        body.resolveStrategy = Closure.DELEGATE_FIRST
        body()
    }

    static NodeBuildConfig fromClosure(Closure body) {
        def cfg = new NodeBuildConfig()
        if (body == null) return cfg
        body.delegate = cfg
        body.resolveStrategy = Closure.DELEGATE_FIRST
        body()
        if (cfg.scripts == null) cfg.scripts = ['install', 'build']
        if (cfg.params == null) cfg.params = new DynamicParams()
        return cfg
    }
}
