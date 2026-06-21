package com.hsbc.treasury.apex.ci.core

/**
 * 轻量 sleep 抽象，便于测试 mock 与 CPS 友好调用。
 * 在 Jenkins 中会被注入为 script.sleep(...)
 */
interface Sleeper {
    void sleep(int seconds)
}
