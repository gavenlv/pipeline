package com.hsbc.treasury.apex.ci.scanners

import com.hsbc.treasury.apex.ci.utils.MockScript
import org.junit.Test
import org.junit.Assert

class ScanRunnerTest {

    @Test
    void registersScanners() {
        def script = new MockScript()
        def runner = new ScanRunner(script: script, ctx: null)
        runner.sast  { println 'sast' }
        runner.sca   { println 'sca' }
        runner.container { println 'container' }
        Assert.assertEquals(3, runner.scannerCount)
    }

    @Test
    void failOnDefaultsToHigh() {
        def runner = new ScanRunner(script: null, ctx: null)
        Assert.assertEquals(['high'], runner.failOn)
    }

    @Test
    void assertPassedSucceedsWhenClean() {
        def runner = new ScanRunner(script: null, ctx: null)
        def results = [
            'sast': new ScanResult(scanner: 'sast', status: 'OK', high: 0, medium: 0, low: 0),
            'sca':  new ScanResult(scanner: 'sca',  status: 'OK', high: 0, medium: 0, low: 0)
        ]
        runner.failOn = ['high']
        runner.assertPassed(results)  // 不抛异常
    }

    @Test
    void assertPassedFailsOnGateViolation() {
        def runner = new ScanRunner(script: null, ctx: null)
        def results = [
            'sast': new ScanResult(scanner: 'sast', status: 'OK', high: 2, medium: 0, low: 0)
        ]
        runner.failOn = ['high']
        try {
            runner.assertPassed(results)
            Assert.fail("expected exception")
        } catch (Exception e) {
            Assert.assertTrue(e.message.contains('sast:high=2'))
        }
    }

    @Test
    void assertPassedFailsOnScanException() {
        def runner = new ScanRunner(script: null, ctx: null)
        // failOn 设置为 ['high']，但结果 FAILED 应该总是失败
        runner.failOn = ['high']
        def results = [
            'sca': new ScanResult(scanner: 'sca', status: 'FAILED', summary: 'crash')
        ]
        try {
            runner.assertPassed(results)
            Assert.fail("expected exception")
        } catch (Exception e) {
            Assert.assertTrue(e.message.contains('FAILED'))
        }
    }

    @Test
    void runExecutesBranchesViaParallel() {
        def script = new MockScript()
        def runner = new ScanRunner(script: script, ctx: null)
        runner.sast  { -> new ScanResult(scanner: 'sast', status: 'OK',  high: 0) }
        runner.sca   { -> new ScanResult(scanner: 'sca',  status: 'OK',  high: 0) }
        def results = runner.run()
        Assert.assertTrue(script.parallels.size() >= 1)
        Assert.assertEquals(2, results.size())
        Assert.assertEquals('OK', results['sast-sast']?.status)
        Assert.assertEquals('OK', results['sca-sca']?.status)
    }

    @Test
    void runOnSingleBranchDoesNotUseParallel() {
        def script = new MockScript()
        def runner = new ScanRunner(script: script, ctx: null)
        runner.sast { -> new ScanResult(scanner: 'sast', status: 'OK') }
        def results = runner.run()
        Assert.assertTrue(script.parallels.isEmpty())
        Assert.assertEquals(1, results.size())
    }

    @Test
    void runIsolatesExceptions() {
        def script = new MockScript()
        def runner = new ScanRunner(script: script, ctx: null)
        runner.generic('ok') { -> new ScanResult(scanner: 'ok', status: 'OK') }
        runner.generic('boom') { -> throw new RuntimeException("nope") }
        def results = runner.run()
        Assert.assertEquals('OK',     results['generic-ok']?.status)
        Assert.assertEquals('FAILED', results['generic-boom']?.status)
    }

    @Test
    void emptyScannerListDoesNotFail() {
        def script = new MockScript()
        def runner = new ScanRunner(script: script, ctx: null)
        Assert.assertEquals(0, runner.scannerCount)
        runner.assertPassed([:])  // 不抛
    }
}
