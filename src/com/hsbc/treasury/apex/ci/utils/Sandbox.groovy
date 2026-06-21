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
            Map shOpts = [script: render(cmd), returnStdout: true, label: label]
            // 强制使用 bash（Debian/Ubuntu 上 /bin/sh=dash 不支持数组）
            try { shOpts.interpreter = '/bin/bash' } catch (Throwable ignore) { }
            if (timeoutSec > 0) shOpts.timeout = timeoutSec
            def out = ctx.script.sh(shOpts).toString().trim()
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
            Map shOpts = [script: render(cmd), label: label]
            try { shOpts.interpreter = '/bin/bash' } catch (Throwable ignore) { }
            if (timeoutSec > 0) shOpts.timeout = timeoutSec
            ctx.script.sh(shOpts)
            return 0
        } catch (Throwable t) {
            return 1
        }
    }

    /** 将 [mvn, -Dk=v, clean, package] 渲染为 bash 数组形式（最安全）。 */
    static String render(List<String> cmd) {
        StringBuilder sb = new StringBuilder()
        // 显式 shebang 强制 bash，避免 /bin/sh -> dash 解析失败
        sb.append("#!/usr/bin/env bash\n")
        sb.append("set -e\n")
        sb.append("ARGS=(\n")
        for (String c : cmd) {
            sb.append('  ').append(quote(c)).append('\n')
        }
        // Groovy 字符串里要转义 ${} 和 $ ，否则会被 GString 吃掉
        // 用 ARGS[0] 而不是 cmd[0]（cmd 在 bash 里没定义）
        sb.append(")\nexec \"").append('$').append("{ARGS[0]}\" \"").append('$').append("{ARGS[@]:1}\"\n")
        return sb.toString()
    }

    private static String quote(String s) {
        if (s == null) return "''"
        return "'" + s.replace("'", "'\\''") + "'"
    }
}
