package com.hsbc.treasury.apex.ci.scanners

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.utils.Sandbox

/**
 * 容器镜像安全扫描（Trivy、Clair 等）。
 */

abstract class AbstractContainerScanner extends AbstractScanner implements Serializable {
    private static final long serialVersionUID = 1L

    @Override
    String getType() { return 'container' }

    @Override
    ScanResult execute(PipelineContext ctx) {
        return runScan(ctx)
    }

    protected abstract ScanResult runScan(PipelineContext ctx)
}
