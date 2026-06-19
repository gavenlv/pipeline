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
    DynamicParams params

    static NodeBuildConfig fromClosure(Closure body) {
        def cfg = new NodeBuildConfig()
        if (body == null) return cfg
        body.delegate = cfg
        body.resolveStrategy = Closure.DELEGATE_FIRST
        body()
        if (cfg.scripts == null) cfg.scripts = ['install', 'build']
        return cfg
    }
}
