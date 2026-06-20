package com.hsbc.treasury.apex.ci.core

class CollectedResult<T> implements Serializable {
    private static final long serialVersionUID = 1L
    String name
    String status          // OK | FAILED | TIMEOUT
    T value
    Throwable error
    long elapsedMs

    @Override
    String toString() { "${name}=${status}(${elapsedMs}ms)" }
}
