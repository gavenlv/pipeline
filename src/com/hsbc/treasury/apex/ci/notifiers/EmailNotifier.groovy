package com.hsbc.treasury.apex.ci.notifiers

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.scanners.ScanResult

/**
 * 邮件通知：仅拼装纯文本 + 收件人列表。
 * 实际发送交由 Mailer（外部实现）。
 */

class EmailNotifier implements Serializable {
    private static final long serialVersionUID = 1L

    String subject = '[CI] Build report'
    List<String> to = []
    String body

    String buildBody(String status, List<ScanResult> scans, String buildUrl = '') {
        StringBuilder sb = new StringBuilder()
        sb.append("Status: ").append(status).append('\n')
        sb.append("Build : ").append(buildUrl).append('\n')
        sb.append("Scans :\n")
        for (ScanResult r : scans) {
            sb.append("  - ").append(r.toString()).append('\n')
        }
        if (body) sb.append('\n').append(body)
        return sb.toString()
    }

    void notify(PipelineContext ctx, String status, List<ScanResult> scans, String buildUrl = '') {
        if (ctx?.script == null) return
        String text = buildBody(status, scans, buildUrl)
        ctx.script.echo("==> [EmailNotifier] to=${to} subject=${subject}".toString())
        ctx.script.echo(text)
    }
}
