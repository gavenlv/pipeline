package com.hsbc.treasury.apex.ci.core

import com.hsbc.treasury.apex.ci.errors.ApexCIException

/**
 * 轻量 sleep 抽象，便于测试 mock 与 CPS 友好调用。
 * 在 Jenkins 中会被注入为 script.sleep(...)
 */
interface Sleeper {
    void sleep(int seconds)
}

/** 默认实现：调用 script.sleep(n)（CPS-safe） */
class JenkinsSleeper implements Sleeper, Serializable {
    private static final long serialVersionUID = 1L
    private final Object script
    JenkinsSleeper(Object script) { this.script = script }
    @Override
    void sleep(int seconds) {
        if (script != null && script.respondsTo(script, 'sleep')) {
            script.sleep(seconds)
        } else {
            Thread.sleep(seconds * 1000L)
        }
    }
}

/** 测试用：立即返回，不真睡 */
class NoOpSleeper implements Sleeper, Serializable {
    private static final long serialVersionUID = 1L
    @Override void sleep(int seconds) { /* no-op */ }
}
