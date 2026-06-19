package com.hsbc.treasury.apex.ci.core

import com.hsbc.treasury.apex.ci.errors.ApexCIException
import org.junit.Test
import org.junit.Assert

import java.util.concurrent.Callable

class AsyncResultTest {

    @Test
    void getBlocksAndReturnsValue() {
        def r = AsyncResult.start('ok', { -> Thread.sleep(50); return 42 } as Callable)
        Assert.assertTrue(r.isDone() || r.get() == 42)
        Assert.assertEquals(42, r.get())
        Assert.assertTrue(r.isDone())
    }

    @Test
    void getWrapsException() {
        def r = AsyncResult.start('bad', { -> throw new IllegalStateException('nope') } as Callable)
        try {
            r.get()
            Assert.fail("Expected exception")
        } catch (ApexCIException ex) {
            Assert.assertTrue(ex.message.contains('bad') || ex.cause?.message?.contains('nope'))
        }
    }

    @Test
    void getWithTimeoutThrows() {
        def r = AsyncResult.start('slow', { -> Thread.sleep(500); return 1 } as Callable)
        try {
            r.get(50L)
            Assert.fail("Expected timeout")
        } catch (ApexCIException ex) {
            Assert.assertTrue(ex.message.toLowerCase().contains('timeout'))
        }
    }

    @Test
    void cancelMarksCancelled() {
        def r = AsyncResult.start('c', { -> Thread.sleep(200); return 1 } as Callable)
        boolean cancelled = r.cancel(true)
        Assert.assertTrue(cancelled || r.isCancelled())
    }
}
