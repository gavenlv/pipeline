package com.hsbc.treasury.apex.ci.core

import com.hsbc.treasury.apex.ci.errors.ApexCIException

/**
 * 集中收集 AsyncResult 列表，并发等待 / 容错获取。
 *
 * 用法：
 *   def results = []
 *   results << AsyncResult.start('sast',  { scanner.sast(); return 'sast' })
 *   results << AsyncResult.start('sca',   { scanner.sca();  return 'sca'  })
 *   def all = AsyncCollector.awaitAll(results, 600_000L)
 *   if (all.any { it.status == 'FAILED' }) error 'scan failed'
 */

class AsyncCollector implements Serializable {
    private static final long serialVersionUID = 1L

    /** 等待所有结果全部完成（或失败） */
    static <T> List<CollectedResult<T>> awaitAll(List<AsyncResult<T>> tasks, long totalTimeoutMs = -1L) {
        List<CollectedResult<T>> out = new ArrayList<>(tasks.size())
        long start = System.currentTimeMillis()
        for (AsyncResult<T> ar : tasks) {
            CollectedResult<T> r = new CollectedResult<>()
            r.name = ar.getName()
            long t0 = System.currentTimeMillis()
            try {
                long remaining = totalTimeoutMs < 0 ? -1L : (totalTimeoutMs - (System.currentTimeMillis() - start))
                r.value = remaining < 0 ? ar.get() : ar.get(remaining)
                r.status = 'OK'
            } catch (ApexCIException ex) {
                if (ex.message?.contains('timeout')) {
                    r.status = 'TIMEOUT'
                } else {
                    r.status = 'FAILED'
                }
                r.error = ex.cause ?: ex
            } catch (Throwable t) {
                r.status = 'FAILED'
                r.error = t
            }
            r.elapsedMs = System.currentTimeMillis() - t0
            out << r
        }
        return out
    }

    /** 简版：仅返回成功的 value 列表 */
    static <T> List<T> valuesOf(List<CollectedResult<T>> results) {
        return results.findAll { it.status == 'OK' }.collect { it.value }
    }

    /** 任一失败即抛异常 */
    static <T> void assertAllOk(List<CollectedResult<T>> results) {
        def failed = results.findAll { it.status != 'OK' }
        if (!failed.isEmpty()) {
            def msg = failed.collect { "${it.name}:${it.status}:${it.error?.message}" }.join('; ')
            throw new ApexCIException("Async tasks failed: ${msg}")
        }
    }
}
