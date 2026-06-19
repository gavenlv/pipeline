package com.hsbc.treasury.apex.ci.scanners

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.utils.Sandbox

/**
 * SCA（软件成分分析）扫描器抽象：依赖漏洞扫描（OWASP Dependency-Check、Snyk 等）。
 */

abstract class AbstractScaScanner extends AbstractScanner implements Serializable {
    private static final long serialVersionUID = 1L

    @Override
    String getType() { return 'sca' }

    @Override
    ScanResult execute(PipelineContext ctx) {
        return runScan(ctx)
    }

    protected abstract ScanResult runScan(PipelineContext ctx)
}
