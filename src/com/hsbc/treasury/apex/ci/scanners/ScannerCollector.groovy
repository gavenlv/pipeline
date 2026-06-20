package com.hsbc.treasury.apex.ci.scanners

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.core.AsyncResult
import com.hsbc.treasury.apex.ci.core.AsyncCollector
import com.hsbc.treasury.apex.ci.core.CollectedResult
import com.hsbc.treasury.apex.ci.errors.ApexCIException

import java.util.concurrent.Callable

/**
 * 集中提交 / 收集多个扫描器任务，并发执行。
 *
 *   scanner {
 *       sast   { ctx -> runSonar(ctx) }
 *       sca    { ctx -> runSnyk(ctx) }
 *       container(image: 'myapp:1.0.0') { ctx -> runTrivy(ctx) }
 *       failOn = ['high']
 *   }
 */

class ScannerCollector implements Serializable {
    private static final long serialVersionUID = 1L

    private final List<Map<String, Object>> entries = []
    List<String> failOn = ['high']
    long totalTimeoutMs = -1L
    Object script

    ScannerCollector withScript(Object s) { this.script = s; return this }
    ScannerCollector withFailOn(List<String> severities) {
        this.failOn = severities?.collect { it?.toString()?.toLowerCase() }?.findAll { it } as List<String> ?: []
        return this
    }
    ScannerCollector withTimeout(long millis) { this.timeoutMs = millis; return this }

    void sast(String name = 'sast', Closure body) { add('sast', name, body) }
    void sca(String name = 'sca', Closure body) { add('sca', name, body) }
    void container(String name = 'container', Closure body) { add('container', name, body) }
    void generic(String name, Closure body) { add('generic', name, body) }

    private void add(String type, String name, Closure body) {
        entries << [type: type, name: name, body: body]
    }

    List<CollectedResult<ScanResult>> run(PipelineContext ctx) {
        if (entries.isEmpty()) return []
        List<AsyncResult<ScanResult>> tasks = []
        for (Map<String, Object> e : entries) {
            String taskName = "${e['type']}-${e['name']}".toString()
            Closure body = (Closure) e['body']
            AsyncResult<ScanResult> ar = AsyncResult.start(taskName, { -> invokeBody(ctx, body) } as Callable)
            tasks << ar
        }
        return AsyncCollector.awaitAll(tasks, totalTimeoutMs)
    }

    private static ScanResult invokeBody(PipelineContext ctx, Closure body) {
        return (ScanResult) body.call(ctx)
    }

    /** 收集后统一判断：failOn 中任一严重度非零 → 抛错 */
    void assertPassed(List<CollectedResult<ScanResult>> results) {
        List<String> failed = []
        for (CollectedResult<ScanResult> r : results) {
            if (r.status == 'FAILED' || r.status == 'TIMEOUT') {
                failed << "${r.name}:${r.status}:${r.error?.message}".toString()
                continue
            }
            ScanResult sr = (ScanResult) r.value
            for (String sev : failOn) {
                int count = 0
                if (sev == 'high') count = sr.high
                else if (sev == 'medium') count = sr.medium
                else if (sev == 'low') count = sr.low
                if (count > 0) {
                    failed << "${r.name}:${sev}=${count}".toString()
                    break
                }
            }
        }
        if (!failed.isEmpty()) {
            throw new ApexCIException("Scanners failed gates: ${failed.join('; ')}".toString())
        }
    }
}
