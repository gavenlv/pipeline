package com.hsbc.treasury.apex.ci.scanners

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.utils.Sandbox
import com.hsbc.treasury.apex.ci.errors.ScanException

/**
 * 通用命令行扫描器：给定工具名 + 解析器，输出 ScanResult。
 *
 *   genericScanner('trivy') {
 *       image = 'myapp:1.0.0'
 *       args  = ['image', '--severity', 'HIGH,CRITICAL']
 *       reportFile = 'trivy.json'
 *   }
 */

class GenericCliScanner extends AbstractScanner implements Serializable {
    private static final long serialVersionUID = 1L

    private final String tool
    private final Closure body
    private final Closure parser

    GenericCliScanner(String tool, Closure body, Closure parser = null) {
        this.tool = tool
        this.body = body
        this.parser = parser ?: defaultParser
    }

    @Override
    String getType() { return "cli-${tool}".toString() }

    @Override
    ScanResult execute(PipelineContext ctx) {
        Map<String, Object> cfg = [args: [], reportFile: null]
        if (body != null) {
            body.delegate = cfg
            body.resolveStrategy = Closure.DELEGATE_FIRST
            body()
        }
        List<String> cmd = [tool] + ((List<?>) cfg['args'] ?: []).collect { it.toString() }
        ctx.script?.echo("==> [scan:${tool}] ${cmd.join(' ')}")
        String out = Sandbox.runShell(ctx, cmd, "scan-${tool}".toString())
        ScanResult r = (ScanResult) parser.call(out, cfg)
        if (cfg['reportFile']) r.reportPath = cfg['reportFile'].toString()
        return r
    }

    private static Closure getDefaultParser() {
        return { String text, Map cfg ->
            ScanResult r = new ScanResult()
            r.status = 'OK'
            if (text == null) { r.status = 'SKIPPED'; return r }
            // 文本中找 high= / medium= / low=
            (text =~ /(?i)high\s*[:=]\s*(\d+)/).each { r.high = Integer.parseInt(it[1]) }
            (text =~ /(?i)medium\s*[:=]\s*(\d+)/).each { r.medium = Integer.parseInt(it[1]) }
            (text =~ /(?i)low\s*[:=]\s*(\d+)/).each { r.low = Integer.parseInt(it[1]) }
            if (r.high > 0) r.status = 'FAILED'
            else if (r.medium > 0) r.status = 'WARN'
            return r
        }
    }

    static Closure defaultParser = GenericCliScanner.getDefaultParser()
}
