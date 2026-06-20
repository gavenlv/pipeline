package com.hsbc.treasury.apex.ci.core

/** 默认实现：调用 script.sleep(n)（CPS-safe） */
class JenkinsSleeper implements Sleeper, Serializable {
    private static final long serialVersionUID = 1L
    private final Object script
    JenkinsSleeper(Object script) { this.script = script }
    @Override
    void sleep(int seconds) {
        if (script != null && script.metaClass.respondsTo(script, 'sleep')) {
            script.sleep(seconds)
        } else {
            Thread.sleep(seconds * 1000L)
        }
    }
}
