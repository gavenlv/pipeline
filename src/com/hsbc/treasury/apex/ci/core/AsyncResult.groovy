package com.hsbc.treasury.apex.ci.core

import com.hsbc.treasury.apex.ci.errors.ApexCIException

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 异步任务句柄。
 * - 任务在后台线程执行；通过 isDone() / get() 访问结果
 * - get() 会阻塞直到完成
 * - cancel() 尝试中断（合作式）
 * - getOrThrow() 提供异常自动转译
 *
 * 设计目标：在 Jenkins 中，业务方可以一次提交多个 Scanner 任务，
 * 不需要为每个 Scanner 单独写 stage。
 */

class AsyncResult<T> implements Serializable {
    private static final long serialVersionUID = 1L

    private final String name
    private final FutureTask<T> task
    private final Sleeper sleeper
    private final long pollIntervalMs

    AsyncResult(String name, Callable<T> callable, Sleeper sleeper = new NoOpSleeper(), long pollIntervalMs = 100L) {
        this.name = name
        this.task = new FutureTask<T>(callable)
        this.sleeper = sleeper
        this.pollIntervalMs = pollIntervalMs
    }

    /** 启动后台执行（幂等：内部判重） */
    synchronized void start() {
        if (!task.isDone() && !task.isCancelled()) {
            Thread t = new Thread(task, "apex-async-${name}")
            t.setDaemon(true)
            t.start()
        }
    }

    boolean isDone() { task.isDone() }
    boolean isCancelled() { task.isCancelled() }
    String getName() { name }

    /** 同步等待至完成 */
    T get() {
        try {
            return task.get()
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt()
            throw new ApexCIException("Interrupted while waiting for ${name}", ie)
        } catch (ExecutionException ee) {
            throw new ApexCIException("Async task ${name} failed: ${ee.cause?.message}", ee.cause)
        }
    }

    /** 同步等待，超时则抛异常 */
    T get(long timeoutMs) {
        try {
            return task.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (TimeoutException te) {
            throw new ApexCIException("Async task ${name} timeout after ${timeoutMs}ms", te)
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt()
            throw new ApexCIException("Interrupted while waiting for ${name}", ie)
        } catch (ExecutionException ee) {
            throw new ApexCIException("Async task ${name} failed: ${ee.cause?.message}", ee.cause)
        }
    }

    boolean cancel(boolean mayInterruptIfRunning = true) {
        return task.cancel(mayInterruptIfRunning)
    }

    T getOrThrow() {
        if (isCancelled()) throw new ApexCIException("${name} cancelled")
        return get()
    }

    /** 工厂方法：接受闭包，自动转 Callable */
    static <T> AsyncResult<T> start(String name, Closure<T> body) {
        return start(name, (Callable<T>) body::call)
    }

    /** 工厂方法：直接接受 Callable */
    static <T> AsyncResult<T> start(String name, Callable<T> callable) {
        def r = new AsyncResult<T>(name, callable)
        r.start()
        return r
    }
}
