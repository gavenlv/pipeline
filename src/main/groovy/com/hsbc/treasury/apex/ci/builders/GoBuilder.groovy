package com.hsbc.treasury.apex.ci.builders

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.errors.BuildException

/**
 * Go 项目构建器。
 */

class GoBuilder extends AbstractBuilder implements Serializable {
    private static final long serialVersionUID = 1L

    @Override
    String getLanguage() { return 'go' }

    @Override
    boolean detect(File projectDir) {
        return new File(projectDir ?: new File('.'), 'go.mod').exists()
    }

    @Override
    Object parseConfig(Closure body) { return GoBuildConfig.fromClosure(body) }

    @Override
    Object execute(PipelineContext ctx, Closure body, Map opts = [:]) {
        GoBuildConfig cfg = (GoBuildConfig) parseConfig(body)
        validate(cfg)
        ctx?.log("==> [Go] v=${cfg.goVersion} commands=${cfg.commands}")

        List<String> cmd = ['go']
        cfg.commands.each { c ->
            cmd << c
            if (c == 'test' && cfg.withRace) cmd << '-race'
            if (c in ['build', 'test', 'vet']) cmd << cfg.mainPackage
        }
        if (cfg.params != null) cmd = mergeDynamicParams(cmd, cfg.params)
        cmd = platformAdapt(cmd, ctx)

        com.hsbc.treasury.apex.ci.utils.Sandbox.runShell(ctx, cmd, "go".toString())
        ctx?.setAttr("go.build.module", cfg.moduleName)
        return ['module': cfg.moduleName, 'commands': cfg.commands]
    }

    private static void validate(GoBuildConfig cfg) {
        if (cfg.commands == null || cfg.commands.isEmpty()) throw new BuildException("commands cannot be empty")
    }
}
