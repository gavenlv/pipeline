package com.hsbc.treasury.apex.ci.core

import com.hsbc.treasury.apex.ci.scanners.ScanResult
import com.hsbc.treasury.apex.ci.errors.ApexCIException
import org.junit.Test
import org.junit.Assert

import java.util.concurrent.Callable

class AsyncCollectorTest {

    @Test
    void awaitsAllAndReportsStatus() {
        def tasks = [
            AsyncResult.start('a', { -> Thread.sleep(30); new ScanResult(scanner: 'a', status: 'OK', high: 0) } as Callable),
            AsyncResult.start('b', { -> Thread.sleep(10); new ScanResult(scanner: 'b', status: 'OK', high: 1) } as Callable)
        ]
        def results = AsyncCollector.awaitAll(tasks, 5000L)
        Assert.assertEquals(2, results.size())
        def byName = results.collectEntries { [it.name, it.status] }
        Assert.assertEquals('OK', byName['a'])
        Assert.assertEquals('OK', byName['b'])
    }

    @Test
    void marksFailed() {
        def tasks = [
            AsyncResult.start('ok', { -> new ScanResult(scanner: 'ok', status: 'OK') } as Callable),
            AsyncResult.start('bad', { -> throw new IllegalArgumentException('boom') } as Callable)
        ]
        def results = AsyncCollector.awaitAll(tasks, 5000L)
        Assert.assertEquals('OK', results[0].status)
        Assert.assertEquals('FAILED', results[1].status)
    }

    @Test
    void assertAllOkThrowsIfAnyFailed() {
        def tasks = [
            AsyncResult.start('ok', { -> new ScanResult(scanner: 'ok', status: 'OK') } as Callable),
            AsyncResult.start('bad', { -> throw new RuntimeException('x') } as Callable)
        ]
        def results = AsyncCollector.awaitAll(tasks, 5000L)
        try {
            AsyncCollector.assertAllOk(results)
            Assert.fail("expected throw")
        } catch (ApexCIException ex) {
            Assert.assertTrue(ex.message.contains('failed'))
        }
    }
}
