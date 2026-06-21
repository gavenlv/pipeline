package com.hsbc.treasury.apex.ci.core

import com.hsbc.treasury.apex.ci.errors.ApexCIException

/**
 * 重试策略与执行器。
 * - 线性 / 指数退避
 * - 异常过滤
 *
 * 注意：CPS 下不要在 Stage 构造里调用 Retry.none()，会被识别为 CPS
 * 方法调用。改为 lazy init 或在方法体内 new Retry(...)。
 */

class Retry implements Serializable {
    private static final long serialVersionUID = 1L

    int maxAttempts = 1
    long initialDelayMs = 0L
    double backoffMultiplier = 1.0
    int maxDelayMs = 60000
    List<Class<? extends Throwable>> retryOn = [Exception]
    Sleeper sleeper

    static Retry none() {
        return new Retry(maxAttempts: 1, initialDelayMs: 0L, backoffMultiplier: 1.0)
    }

    static Retry linear(int attempts, long delayMs = 0L) {
        return new Retry(maxAttempts: attempts, initialDelayMs: delayMs, backoffMultiplier: 1.0, sleeper: new NoOpSleeper())
    }

    static Retry exponential(int attempts, long initialMs = 500L, double multiplier = 2.0) {
        return new Retry(maxAttempts: attempts, initialDelayMs: initialMs, backoffMultiplier: multiplier, sleeper: new NoOpSleeper())
    }

    /** 实例方法：执行 body，异常时按策略重试 */
    Object execute(Closure body) {
        return execute(this, body)
    }

    /** 静态方法：执行 body（兼容旧 API） */
    static <T> T execute(Retry retry, Closure<T> body) {
        if (retry == null) throw new ApexCIException("Retry config required")
        if (body == null) throw new ApexCIException("body closure required")
        Sleeper sl = retry.sleeper ?: new NoOpSleeper()
        int attempt = 1
        long delay = retry.initialDelayMs
        while (true) {
            try {
                return body.call()
            } catch (Throwable t) {
                if (attempt >= retry.maxAttempts) {
                    throw new ApexCIException("Retry exhausted after ${attempt} attempts: ${t.message}", t)
                }
                if (!shouldRetry(retry, t)) {
                    throw new ApexCIException("Non-retryable: ${t.message}", t)
                }
                if (delay > 0) {
                    int sleepSeconds = (int) (Math.min(delay, retry.maxDelayMs) / 1000L)
                    if (sleepSeconds > 0) sl.sleep(sleepSeconds)
                }
                attempt++
                delay = (long) (delay * retry.backoffMultiplier)
            }
        }
    }

    private static boolean shouldRetry(Retry retry, Throwable t) {
        if (retry.retryOn == null || retry.retryOn.isEmpty()) return true
        for (Class<?> cls : retry.retryOn) {
            if (cls.isInstance(t)) return true
        }
        return false
    }
}
