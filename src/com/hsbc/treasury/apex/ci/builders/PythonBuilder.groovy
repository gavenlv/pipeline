package com.hsbc.treasury.apex.ci.builders

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.errors.BuildException

/**
 * Python 项目构建器。
 */

class PythonBuilder extends AbstractBuilder implements Serializable {
    private static final long serialVersionUID = 1L

    @Override
    String getLanguage() { return 'python' }

    @Override
    boolean detect(File projectDir) {
        File d = projectDir ?: new File('.')
        return new File(d, 'pyproject.toml').exists() ||
               new File(d, 'setup.py').exists() ||
               new File(d, 'requirements.txt').exists()
    }

    @Override
    Object parseConfig(Closure body) { return PythonBuildConfig.fromClosure(body) }

    @Override
    Object execute(PipelineContext ctx) {
        throw new BuildException("Use execute(ctx, body).")
    }

    Object execute(PipelineContext ctx, Closure body) {
        PythonBuildConfig cfg = (PythonBuildConfig) parseConfig(body)
        validate(cfg)
        ctx.script?.echo("==> [Python:${cfg.packageManager}] venv=${cfg.venv} commands=${cfg.commands}")

        List<String> cmd = []
        switch (cfg.packageManager) {
            case 'pip':
                cmd = [cfg.pythonVersion >= 3 ? 'python3' : 'python', '-m', 'pip']
                break
            case 'poetry':
                cmd = ['poetry']
                break
            case 'pipenv':
                cmd = ['pipenv']
                break
            default:
                throw new BuildException("Unsupported packageManager: ${cfg.packageManager}")
        }
        cfg.commands.each { c -> cmd << c }
        if (cfg.params != null) cmd = mergeDynamicParams(cmd, cfg.params)
        cmd = platformAdapt(cmd, ctx)

        com.hsbc.treasury.apex.ci.utils.Sandbox.runShell(ctx, cmd, "python-${cfg.packageManager}".toString())
        ctx.setAttr("python.build.pm", cfg.packageManager)
        return ['pm': cfg.packageManager, 'commands': cfg.commands]
    }

    private static void validate(PythonBuildConfig cfg) {
        if (cfg.commands == null || cfg.commands.isEmpty()) throw new BuildException("commands cannot be empty")
        if (!(cfg.packageManager in ['pip', 'poetry', 'pipenv'])) {
            throw new BuildException("Unsupported packageManager: ${cfg.packageManager}")
        }
    }
}
