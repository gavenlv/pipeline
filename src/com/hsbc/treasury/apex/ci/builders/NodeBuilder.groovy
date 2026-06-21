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

        // 1) 装依赖（如果有 lock 文件优先 ci，否则 install）
        if (cfg.install != false) {
            List<String> installCmd = [cfg.packageManager]
            if (new File(ctx?.script?.pwd()?.toString() ?: '.', 'package-lock.json').exists() && cfg.packageManager == 'npm') {
                installCmd << 'ci' << '--no-audit' << '--no-fund'
            } else {
                installCmd << 'install' << '--no-audit' << '--no-fund'
            }
            installCmd = platformAdapt(installCmd, ctx)
            com.hsbc.treasury.apex.ci.utils.Sandbox.runShell(ctx, installCmd, "node-${cfg.packageManager}-install".toString())
        }

        // 2) 依次执行 scripts：每个脚本用 `npm run <name>`，串行（set -e 失败即停）
        for (String s : cfg.scripts) {
            List<String> runCmd = [cfg.packageManager, 'run', s]
            if (cfg.params != null) runCmd = mergeDynamicParams(runCmd, cfg.params)
            runCmd = platformAdapt(runCmd, ctx)
            com.hsbc.treasury.apex.ci.utils.Sandbox.runShell(ctx, runCmd, "node-${cfg.packageManager}-${s}".toString())
        }
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
