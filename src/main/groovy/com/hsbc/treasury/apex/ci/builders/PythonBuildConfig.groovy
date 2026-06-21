package com.hsbc.treasury.apex.ci.builders

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.core.DynamicParams

/**
 * Python 构建配置。
 */

class PythonBuildConfig implements Serializable {
    private static final long serialVersionUID = 1L

    int pythonVersion = 3
    String packageManager = 'pip'       // pip | poetry | pipenv
    String venv = '.venv'
    List<String> commands = ['install', 'test']
    String requirementsFile = 'requirements.txt'
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

    static PythonBuildConfig fromClosure(Closure body) {
        def cfg = new PythonBuildConfig()
        if (body == null) return cfg
        body.delegate = cfg
        body.resolveStrategy = Closure.DELEGATE_FIRST
        body()
        if (cfg.commands == null) cfg.commands = ['install', 'test']
        if (cfg.params == null) cfg.params = new DynamicParams()
        return cfg
    }
}
