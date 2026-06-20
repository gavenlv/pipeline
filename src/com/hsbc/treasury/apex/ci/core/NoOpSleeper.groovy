package com.hsbc.treasury.apex.ci.core

/** 测试用：立即返回，不真睡 */
class NoOpSleeper implements Sleeper, Serializable {
    private static final long serialVersionUID = 1L
    @Override void sleep(int seconds) { /* no-op */ }
}
