package com.hsbc.treasury.apex.ci.builders

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.errors.BuildException

/**
 * Node 项目构建器。
 */

class NodeBuilder extends AbstractBuilder implements Serializable {
    private static final long serialVersionUID = 1L

    @Override
    String getLanguage() { return 'node' }

    @Override
    boolean detect(File projectDir) {
        return new File(projectDir ?: new File('.'), 'package.json').exists()
    }

    @Override
    Object parseConfig(Closure body) { return NodeBuildConfig.fromClosure(body) }

    @Override
    Object execute(PipelineContext ctx, Closure body, Map opts = [:]) {
        NodeBuildConfig cfg = (NodeBuildConfig) parseConfig(body)
        validate(cfg)
        ctx?.log("==> [Node:${cfg.packageManager}] scripts=${cfg.scripts}")

        List<String> cmd = [cfg.packageManager]
        cfg.scripts.each { s ->
            cmd << 'run' << s
        }
        if (cfg.params != null) cmd = mergeDynamicParams(cmd, cfg.params)
        cmd = platformAdapt(cmd, ctx)

        if (cfg.registry) {
            ctx?.log("[Node] using registry: ${cfg.registry}")
        }

        com.hsbc.treasury.apex.ci.utils.Sandbox.runShell(ctx, cmd, "node-${cfg.packageManager}".toString())
        ctx?.setAttr("node.build.pm", cfg.packageManager)
        return ['pm': cfg.packageManager, 'scripts': cfg.scripts]
    }

    private static void validate(NodeBuildConfig cfg) {
        if (cfg.scripts == null || cfg.scripts.isEmpty()) throw new BuildException("scripts cannot be empty")
        if (!(cfg.packageManager in ['npm', 'yarn', 'pnpm'])) {
            throw new BuildException("Unsupported packageManager: ${cfg.packageManager}")
        }
    }
}
