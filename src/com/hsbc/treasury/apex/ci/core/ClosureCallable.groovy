package com.hsbc.treasury.apex.ci.core

import java.util.concurrent.Callable

/**
 * 将 Closure 包装为 Callable，避免在 Jenkins Groovy 沙箱中使用方法引用语法。
 */
class ClosureCallable<T> implements Callable<T>, Serializable {
    private static final long serialVersionUID = 1L
    private final Closure<T> closure

    ClosureCallable(Closure<T> closure) {
        this.closure = closure
    }

    @Override
    T call() {
        return closure.call()
    }
}
