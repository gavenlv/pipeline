package com.hsbc.treasury.apex.ci.reporters

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.scanners.ScanResult

/**
 * 简单控制台报告（沙箱安全：只走 script.echo）。
 */

class ConsoleReporter implements Serializable {
    private static final long serialVersionUID = 1L

    void reportScan(PipelineContext ctx, List<ScanResult> results) {
        if (ctx?.script == null) return
        ctx.script.echo("=".multiply(72))
        ctx.script.echo(" Apex Security Scan Summary")
        ctx.script.echo("=".multiply(72))
        results.each { ScanResult r ->
            ctx.script.echo(" ${r.scanner.padRight(20)} | ${r.status.padRight(8)} | H=${r.high} M=${r.medium} L=${r.low} | ${r.elapsedMs}ms".toString())
        }
        ctx.script.echo("=".multiply(72))
    }
}
