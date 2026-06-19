package com.hsbc.treasury.apex.ci.scanners

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.utils.MockScript
import org.junit.Test
import org.junit.Assert

class ScannersTest {

    @Test
    void scanResultSeverityGate() {
        def r = new ScanResult(scanner: 's', status: 'OK', high: 0, medium: 0, low: 0)
        Assert.assertTrue(r.passed('high'))
        Assert.assertTrue(r.passed('low'))

        r.high = 1
        Assert.assertFalse(r.passed('high'))
        Assert.assertFalse(r.passed('low'))
    }

    @Test
    void genericScannerParsesCounts() {
        def s = new MockScript()
        s.shBehavior = { args ->
            if (args.returnStdout) {
                return 'high=2\nmedium=1\nlow=4\n'
            }
            return null
        }
        def ctx = PipelineContext.builder().script(s).build()
        def scanner = new GenericCliScanner('echo', { args = ['test'] })
        def r = scanner.run(ctx)
        Assert.assertEquals(2, r.high)
        Assert.assertEquals(1, r.medium)
        Assert.assertEquals(4, r.low)
        Assert.assertEquals('FAILED', r.status)
    }

    @Test
    void genericScannerReturnsOkWhenNoFindings() {
        def s = new MockScript()
        s.shBehavior = { args -> args.returnStdout ? 'high=0\nmedium=0\nlow=0\n' : null }
        def ctx = PipelineContext.builder().script(s).build()
        def scanner = new GenericCliScanner('echo', { args = ['test'] })
        def r = scanner.run(ctx)
        Assert.assertEquals('OK', r.status)
    }

    @Test
    void collectorRunsAll() {
        def s = new MockScript()
        def ctx = PipelineContext.builder().script(s).build()
        def col = new ScannerCollector().withScript(s)
        col.sast('sast1') { c -> new ScanResult(scanner: 'sast1', status: 'OK', high: 0) }
        col.sca('sca1')  { c -> new ScanResult(scanner: 'sca1',  status: 'OK', high: 0) }
        def results = col.run(ctx)
        Assert.assertEquals(2, results.size())
        Assert.assertTrue(results.every { it.status == 'OK' })
    }

    @Test
    void collectorGateFailsOnHigh() {
        def s = new MockScript()
        def ctx = PipelineContext.builder().script(s).build()
        def col = new ScannerCollector().withScript(s).withFailOn(['high'])
        col.sast('sast-bad') { c -> new ScanResult(scanner: 'sast-bad', status: 'OK', high: 3) }
        col.sca('sca-ok')    { c -> new ScanResult(scanner: 'sca-ok',  status: 'OK', high: 0) }
        def results = col.run(ctx)
        try {
            col.assertPassed(results)
            Assert.fail("expected gate to fail")
        } catch (Exception ex) {
            Assert.assertTrue(ex.message.contains('sast-bad'))
        }
    }
}
