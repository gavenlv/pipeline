package com.hsbc.treasury.apex.ci.scanners

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.core.Step
import com.hsbc.treasury.apex.ci.errors.ApexCIException

/**
 * Scanner 结果基类。
 */

class ScanResult implements Serializable {
    private static final long serialVersionUID = 1L

    String scanner
    String status = 'OK'        // OK | WARN | FAILED | SKIPPED
    int high = 0
    int medium = 0
    int low = 0
    long elapsedMs = 0
    String reportPath
    Map<String, Object> extras = [:]

    boolean passed(String severity = 'high') {
        switch (severity) {
            case 'high':   return high == 0
            case 'medium': return high == 0 && medium == 0
            case 'low':    return high == 0 && medium == 0 && low == 0
            default:       return high == 0
        }
    }

    @Override
    String toString() {
        return "${scanner}=${status} H=${high} M=${medium} L=${low} ${elapsedMs}ms".toString()
    }
}
