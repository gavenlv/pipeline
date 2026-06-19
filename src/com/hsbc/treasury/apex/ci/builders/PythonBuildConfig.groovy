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
    DynamicParams params

    static PythonBuildConfig fromClosure(Closure body) {
        def cfg = new PythonBuildConfig()
        if (body == null) return cfg
        body.delegate = cfg
        body.resolveStrategy = Closure.DELEGATE_FIRST
        body()
        if (cfg.commands == null) cfg.commands = ['install', 'test']
        return cfg
    }
}
