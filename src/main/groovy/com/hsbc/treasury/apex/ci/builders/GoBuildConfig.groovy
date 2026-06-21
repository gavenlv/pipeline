package com.hsbc.treasury.apex.ci.builders

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.core.DynamicParams

/**
 * Go 构建配置。
 */

class GoBuildConfig implements Serializable {
    private static final long serialVersionUID = 1L

    int goVersion = 1
    String moduleName = ''
    List<String> commands = ['build', 'test']
    String mainPackage = './...'
    boolean withRace = false
    DynamicParams params = new DynamicParams()

    void setParams(DynamicParams p) { this.params = (p != null) ? p : new DynamicParams() }

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

    static GoBuildConfig fromClosure(Closure body) {
        def cfg = new GoBuildConfig()
        if (body == null) return cfg
        body.delegate = cfg
        body.resolveStrategy = Closure.DELEGATE_FIRST
        body()
        if (cfg.commands == null) cfg.commands = ['build', 'test']
        if (cfg.params == null) cfg.params = new DynamicParams()
        return cfg
    }
}
