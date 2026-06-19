package com.hsbc.treasury.apex.ci.scanners

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.core.Step
import com.hsbc.treasury.apex.ci.core.DynamicParams
import com.hsbc.treasury.apex.ci.utils.Sandbox

/**
 * Scanner 抽象基类。
 */

abstract class AbstractScanner implements Step<ScanResult>, Serializable {
    private static final long serialVersionUID = 1L

    @Override
    String getName() { return "scan-${getType()}".toString() }

    @Override
    boolean isSandboxSafe() { return true }

    @Override
    ScanResult run(PipelineContext ctx) {
        long t0 = System.currentTimeMillis()
        try {
            ScanResult r = execute(ctx)
            r.scanner = r.scanner ?: getType()
            r.elapsedMs = System.currentTimeMillis() - t0
            return r
        } catch (Throwable t) {
            ScanResult r = new ScanResult(scanner: getType(), status: 'FAILED')
            r.elapsedMs = System.currentTimeMillis() - t0
            r.extras['error'] = t.message
            return r
        }
    }

    abstract String getType()
    abstract ScanResult execute(PipelineContext ctx)
}
