package com.hsbc.treasury.apex.ci.scanners

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.utils.Sandbox
import com.hsbc.treasury.apex.ci.errors.ApexCIException

/**
 * SAST（静态应用安全测试）扫描器抽象。
 * 子类：SonarScannerStep、SpotBugsStep 等。
 */

abstract class AbstractSastScanner extends AbstractScanner implements Serializable {
    private static final long serialVersionUID = 1L

    @Override
    String getType() { return 'sast' }

    @Override
    ScanResult execute(PipelineContext ctx) {
        return runScan(ctx)
    }

    protected abstract ScanResult runScan(PipelineContext ctx)

    protected List<String> buildCommand(String tool, List<String> args) {
        List<String> cmd = [tool]
        cmd.addAll(args)
        return cmd
    }

    protected ScanResult parseTextReport(String text) {
        ScanResult r = new ScanResult(scanner: getType())
        if (text == null) { r.status = 'SKIPPED'; return r }
        // 简化版正则解析：
        // 期望输出形如 "high=1,medium=2,low=3"
        text.split(/[\r\n,]/).each { String line ->
            String t = line.trim()
            if (!t) return
            def m = (t =~ /(\w+)\s*=\s*(\d+)/)
            m.each { mm ->
                String k = mm[1].toLowerCase()
                int v = Integer.parseInt(mm[2])
                if (k == 'high') r.high = v
                else if (k == 'medium') r.medium = v
                else if (k == 'low') r.low = v
            }
        }
        if (r.high > 0) r.status = 'FAILED'
        else if (r.medium > 0) r.status = 'WARN'
        else r.status = 'OK'
        return r
    }
}
