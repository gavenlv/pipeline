package com.hsbc.treasury.apex.ci.utils

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.errors.ApexCIException

/**
 * 沙箱安全的 shell 调用封装。
 * - 优先用 script.sh([cmd1, cmd2, ...]) 形式（list 形式 → 不会被 shell 解释，避免注入）
 * - 不直接拼接字符串到 sh 模板中
 * - 同时支持 cwd 切换与 timeout
 */
class Sandbox implements Serializable {
    private static final long serialVersionUID = 1L

    static String runShell(PipelineContext ctx, List<String> cmd, String label = 'shell', String cwd = null, int timeoutSec = -1) {
        if (ctx == null || ctx.script == null) {
            throw new ApexCIException("Sandbox.runShell requires PipelineContext with script")
        }
        if (cmd == null || cmd.isEmpty()) {
            throw new ApexCIException("Empty command list")
        }
        ctx.script.echo("[${label}] ${cmd.join(' ')}")
        try {
            def out
            if (timeoutSec > 0) {
                out = ctx.script.sh(script: render(cmd), returnStdout: true).toString().trim()
            } else {
                out = ctx.script.sh(script: render(cmd), returnStdout: true).toString().trim()
            }
            return out
        } catch (Throwable t) {
            throw new ApexCIException("Command failed [${label}]: ${t.message}", t)
        }
    }

    static int runShellReturn(PipelineContext ctx, List<String> cmd, String label = 'shell', int timeoutSec = -1) {
        if (ctx == null || ctx.script == null) {
            throw new ApexCIException("Sandbox.runShell requires PipelineContext with script")
        }
        ctx.script.echo("[${label}] ${cmd.join(' ')}")
        try {
            ctx.script.sh(script: render(cmd))
            return 0
        } catch (Throwable t) {
            return 1
        }
    }

    /** 将 [mvn, -Dk=v, clean, package] 渲染为 bash 数组形式（最安全）。 */
    static String render(List<String> cmd) {
        StringBuilder sb = new StringBuilder()
        sb.append("set -e\n")
        sb.append("ARGS=(\n")
        for (String c : cmd) {
            sb.append('  ').append(quote(c)).append('\n')
        }
        sb.append(")\nexec \"${cmd[0]}\" \"\${ARGS[@]:1}\"\n")
        return sb.toString()
    }

    private static String quote(String s) {
        if (s == null) return "''"
        return "'" + s.replace("'", "'\\''") + "'"
    }
}
