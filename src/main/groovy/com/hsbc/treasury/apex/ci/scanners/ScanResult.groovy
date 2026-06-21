package com.hsbc.treasury.apex.ci.scanners

/**
 * Scanner 结果。
 * - status: OK | WARN | FAILED | SKIPPED | TIMEOUT
 * - severity counts: critical / high / medium / low
 * - summary: 一行可读总结
 * - findings: 详细问题列表
 * - error: 失败时附带异常
 */
class ScanResult implements Serializable {
    private static final long serialVersionUID = 1L

    String scanner
    String status = 'OK'
    int critical = 0
    int high = 0
    int medium = 0
    int low = 0
    long elapsedMs = 0
    String reportPath
    String summary = ''
    List<Map<String, Object>> findings = []
    Throwable error
    Map<String, Object> extras = [:]

    boolean passed(String severity = 'high') {
        switch (severity) {
            case 'critical': return critical == 0
            case 'high':     return high == 0
            case 'medium':   return high == 0 && medium == 0
            case 'low':      return high == 0 && medium == 0 && low == 0
            default:         return high == 0
        }
    }

    @Override
    String toString() {
        return "${scanner}=${status} C=${critical} H=${high} M=${medium} L=${low} ${elapsedMs}ms".toString()
    }
}
