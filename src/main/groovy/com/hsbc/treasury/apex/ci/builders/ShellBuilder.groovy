package com.hsbc.treasury.apex.ci.builders

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.core.DynamicParams
import com.hsbc.treasury.apex.ci.utils.Sandbox
import com.hsbc.treasury.apex.ci.errors.ApexCIException

/**
 * 通用 Shell 命令构建器（兜底）。
 *
 *   shell {
 *       commands = [['echo', 'hi'], ['ls', '-la']]
 *       label = 'misc'
 *   }
 */

class ShellBuilder extends AbstractBuilder implements Serializable {
    private static final long serialVersionUID = 1L

    @Override
    String getLanguage() { return 'shell' }

    @Override
    boolean detect(File projectDir) { return true }

    @Override
    Object parseConfig(Closure body) {
        def cfg = [commands: [], label: 'shell']
        if (body == null) return cfg
        body.delegate = cfg
        body.resolveStrategy = Closure.DELEGATE_FIRST
        body()
        return cfg
    }

    @Override
    Object execute(PipelineContext ctx, Closure body, Map opts = [:]) {
        def cfg = parseConfig(body)
        List<?> cmds = (List<?>) cfg.commands ?: []
        if (cmds.isEmpty()) throw new ApexCIException("shell.commands cannot be empty")
        String label = (String) cfg.label ?: 'shell'
        List<List<String>> results = []
        for (Object c : cmds) {
            List<String> cmd = ((List<?>) c).collect { it.toString() }
            cmd = platformAdapt(cmd, ctx)
            Sandbox.runShell(ctx, cmd, label)
            results << cmd
        }
        return ['label': label, 'count': results.size()]
    }
}
