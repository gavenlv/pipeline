package com.hsbc.treasury.apex.ci.scanners

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.errors.ApexCIException
import com.hsbc.treasury.apex.ci.reporters.ConsoleReporter

/**
 * ScanRunner — 轻量并发扫描运行器。
 *
 * 区别于旧的 ScannerCollector：不再使用自定义 FutureTask + 线程池，
 * 而是把所有注册的 scanner 任务作为 Jenkins 原生 parallel 的分支执行。
 * 沙箱安全、错误隔离、门禁判断一致。
 */
class ScanRunner implements Serializable {
    private static final long serialVersionUID = 1L

    Object script
    PipelineContext ctx
    List<String> failOn = ['high']
    long timeoutMin = 30L
    boolean failFast = true

    /** 内部列表: [name, type, closure, image, timeoutMin] */
    private final List<Map<String, Object>> entries = []

    void sast(String name = 'sast', Closure body)          { add('sast',      name, body) }
    void sca(String name = 'sca', Closure body)            { add('sca',       name, body) }
    void container(String name = 'container', Closure body) { add('container', name, body) }
    void generic(String name, Closure body)                { add('generic',   name, body) }

    private void add(String type, String name, Closure body) {
        entries << [type: type, name: name, body: body]
    }

    int getScannerCount() { return entries.size() }

    /**
     * 在 Jenkins 原生 parallel 中运行所有 scanner，返回 [name: ScanResult] 形式 map。
     * 调用方负责在外部包裹 stage('Security') { runner.run(); runner.assertPassed() }。
     */
    Map<String, ScanResult> run() {
        if (entries.isEmpty()) return [:]
        if (script == null) throw new ApexCIException("ScanRunner.run: script is null")

        Map<String, Closure> branches = [:]
        for (Map<String, Object> e : entries) {
            String taskName = "${e['type']}-${e['name']}".toString()
            Closure body = (Closure) e['body']
            branches[taskName] = makeBranch(body)
        }

        if (branches.size() == 1) {
            // 单分支直接执行，避免 parallel 副作用
            String onlyName = branches.keySet().iterator().next()
            Map<String, ScanResult> res = [:]
            res[onlyName] = invokeSafe(onlyName, branches[onlyName])
            return res
        }
        Map<String, Object> raw = script.parallel(branches)
        Map<String, ScanResult> results = [:]
        raw.each { k, v -> results[k as String] = v as ScanResult }
        new ConsoleReporter().reportScan(ctx, results.values() as List)
        return results
    }

    /**
     * 每个 parallel 分支的执行单元：
     * - 业务闭包可以返回 ScanResult；否则包装为 OK 的占位结果
     * - 任意异常被 catchError 隔离，转为 FAILED ScanResult
     * - 支持 timeout（脚本级 timeout）
     */
    private Closure makeBranch(Closure body) {
        return { ->
            try {
                script.timeout(time: timeoutMin, unit: 'MINUTES') {
                    Object out = body.call()
                    if (out instanceof ScanResult) {
                        return out
                    } else if (out == null) {
                        return new ScanResult(scanner: 'unknown', status: 'OK',
                                              summary: 'no-op (closure returned null)', findings: [])
                    } else {
                        return new ScanResult(scanner: 'unknown', status: 'OK',
                                              summary: "returned: ${out.toString()}", findings: [])
                    }
                }
            } catch (Throwable t) {
                return new ScanResult(
                    scanner: 'unknown',
                    status: 'FAILED',
                    summary: "scanner failed: ${t.message}".toString(),
                    findings: [],
                    error: t
                )
            }
        }
    }

    private ScanResult invokeSafe(String name, Closure body) {
        try {
            Object out = body.call()
            if (out instanceof ScanResult) return out
            return new ScanResult(scanner: name, status: 'OK', summary: 'no-op', findings: [])
        } catch (Throwable t) {
            return new ScanResult(scanner: name, status: 'FAILED',
                                  summary: t.message, findings: [], error: t)
        }
    }

    /**
     * 门禁判断：若任一 ScanResult 严重度命中 failOn，抛异常。
     * 用户可改 failOn = [] 来跳过门禁。
     */
    void assertPassed(Map<String, ScanResult> results = null) {
        if (results == null) results = run()
        if (results.isEmpty()) return
        if (!failOn) return
        List<String> failOnLower = failOn.collect { it.toString().toLowerCase() }
        List<String> failed = []
        results.each { name, sr ->
            if (sr.status == 'FAILED') {
                failed << "${name}:FAILED:${sr.summary}".toString()
                return
            }
            for (String sev in failOnLower) {
                int n = 0
                if (sev == 'high') n = sr.high
                else if (sev == 'medium') n = sr.medium
                else if (sev == 'low') n = sr.low
                else if (sev == 'critical') n = sr.critical
                if (n > 0) { failed << "${name}:${sev}=${n}".toString(); break }
            }
        }
        if (!failed.isEmpty()) {
            throw new ApexCIException("Scanner gate failed: ${failed.join('; ')}".toString())
        }
    }
}
