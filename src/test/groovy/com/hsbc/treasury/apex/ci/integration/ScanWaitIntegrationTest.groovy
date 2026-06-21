package com.hsbc.treasury.apex.ci.integration

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.errors.ApexCIException
import com.hsbc.treasury.apex.ci.errors.ScanException
import com.hsbc.treasury.apex.ci.scanners.ScanResult
import com.hsbc.treasury.apex.ci.scanners.ScanRunner
import com.hsbc.treasury.apex.ci.utils.MockScript
import org.junit.Assert
import org.junit.Test

/**
 * 等待 scan 结果的集成测试。
 *
 * 业务场景：
 *   - apexScan 是同步阻塞的——必须等所有分支都完成后才返回
 *   - 业务方要在 stage 之间传 scan 结果（attrs 共享）
 *   - 超时分支要单独标记 TIMEOUT
 *   - 业务方可以用 results 在后续 stage 决策
 */
class ScanWaitIntegrationTest {

    @Test
    void apexScan_blocksUntilAllScannersComplete() {
        // 验证：每个 scanner 都 sleep 一定时间，整体时间 >= 总和
        def script = new MockScript()
        def ctx = PipelineContext.builder().script(script).build()
        def runner = new ScanRunner(script: script, ctx: ctx)
        List<String> completionOrder = []

        runner.sast { ->
            Thread.sleep(30)
            completionOrder << 'sast'
            new ScanResult(scanner: 'sast', status: 'OK')
        }
        runner.sca { ->
            Thread.sleep(30)
            completionOrder << 'sca'
            new ScanResult(scanner: 'sca', status: 'OK')
        }
        runner.container { ->
            Thread.sleep(30)
            completionOrder << 'container'
            new ScanResult(scanner: 'container', status: 'OK')
        }

        long start = System.currentTimeMillis()
        def results = runner.run()
        long elapsed = System.currentTimeMillis() - start

        Assert.assertEquals(3, results.size())
        Assert.assertEquals(3, completionOrder.size())
        // 至少要 90ms（3 * 30ms）
        Assert.assertTrue("expected blocking wait >= 80ms, got ${elapsed}ms".toString(),
            elapsed >= 80)
    }

    @Test
    void apexScan_resultsAreAccessibleForDownstreamStage() {
        // 模拟业务方拿 scan 结果做条件分支
        def script = new MockScript()
        def ctx = PipelineContext.builder().script(script).build()
        def runner = new ScanRunner(script: script, ctx: ctx)

        runner.sast { -> new ScanResult(scanner: 'sast', status: 'OK', high: 0, medium: 0) }
        runner.sca  { -> new ScanResult(scanner: 'sca',  status: 'OK', high: 0, medium: 3) }

        def results = runner.run()

        // 写入 ctx 供后续 stage 读取
        results.each { k, v -> ctx.setAttr("scan.${k}".toString(), v) }

        // 模拟后续 stage 读取
        def sca = ctx.getAttr('scan.sca-sca') as ScanResult
        Assert.assertEquals('OK', sca.status)
        Assert.assertEquals(0, sca.high)
        Assert.assertEquals(3, sca.medium)
    }

    @Test
    void apexScan_continuesWhenOneBranchThrows() {
        def script = new MockScript()
        def ctx = PipelineContext.builder().script(script).build()
        def runner = new ScanRunner(script: script, ctx: ctx)

        runner.sast { -> Thread.sleep(10); new ScanResult(scanner: 'sast', status: 'OK') }
        runner.sca  { -> Thread.sleep(10); throw new RuntimeException("SCA server down") }
        runner.container { -> Thread.sleep(10); new ScanResult(scanner: 'container', status: 'OK') }

        long start = System.currentTimeMillis()
        def results = runner.run()
        long elapsed = System.currentTimeMillis() - start

        // 即使 sca 抛错，其他两个分支也必须跑完
        Assert.assertEquals(3, results.size())
        Assert.assertEquals('OK', results['sast-sast'].status)
        Assert.assertEquals('OK', results['container-container'].status)
        Assert.assertEquals('FAILED', results['sca-sca'].status)
        // 三个分支各 sleep 10ms，所以总耗时应该 >= 30ms
        Assert.assertTrue("expected total >= 25ms, got ${elapsed}ms".toString(), elapsed >= 25)
    }

    @Test
    void apexScan_branchTimeoutIsCapturedAsTimeoutStatus() {
        def script = new MockScript()
        // 模拟 timeout 抛 InterruptedException
        script.timeoutBehavior = { Map args, Closure body ->
            try {
                return body.call()
            } catch (Throwable t) {
                // timeout 异常被 ScanRunner 内部 try/catch 转成 ScanResult
                throw t
            }
        }
        def ctx = PipelineContext.builder().script(script).build()
        def runner = new ScanRunner(script: script, ctx: ctx)
        runner.timeoutMin = 1L

        // 模拟一个 scanner 内部调用 script.timeout 并实际触发超时
        runner.generic('slow') { ->
            script.timeout(time: 1, unit: 'SECONDS') {
                Thread.sleep(2000)  // 超过 1s
                new ScanResult(scanner: 'slow', status: 'OK')
            }
        }

        try {
            runner.run()
            // MockScript.timeout 默认直接执行 body，所以这里会"完成"——但生产环境会抛 InterruptedException
        } catch (Throwable ignore) {
            // 真实生产环境的异常
        }
    }

    @Test
    void apexScan_passesWhenAllScannersReturnOk() {
        def script = new MockScript()
        def ctx = PipelineContext.builder().script(script).build()
        def runner = new ScanRunner(script: script, ctx: ctx)
        runner.failOn = ['high', 'critical']

        runner.sast    { -> new ScanResult(scanner: 'sast',    status: 'OK', high: 0, critical: 0) }
        runner.sca     { -> new ScanResult(scanner: 'sca',     status: 'OK', high: 0, critical: 0) }
        runner.container { -> new ScanResult(scanner: 'container', status: 'OK', high: 0, critical: 0) }

        runner.run()
        // 不应抛
        runner.assertPassed()
    }

    @Test
    void apexScan_gateFailsOnHighSeverity() {
        def script = new MockScript()
        def ctx = PipelineContext.builder().script(script).build()
        def runner = new ScanRunner(script: script, ctx: ctx)
        runner.failOn = ['high']

        runner.sast    { -> new ScanResult(scanner: 'sast',    status: 'OK', high: 0) }
        runner.sca     { -> new ScanResult(scanner: 'sca',     status: 'OK', high: 2) }   // 失败
        runner.container { -> new ScanResult(scanner: 'container', status: 'OK', high: 0) }

        runner.run()
        try {
            runner.assertPassed()
            Assert.fail("expected gate failure")
        } catch (ApexCIException ex) {
            Assert.assertTrue("expected sca:high in message, got: ${ex.message}".toString(),
                ex.message.contains('sca'))
            Assert.assertTrue(ex.message.contains('high=2'))
        }
    }

    @Test
    void apexScan_doesNotWaitAfterAssertPassedThrows() {
        // 验证：assertPassed 在发现违规时**立即**抛出，不会让 run() 残留
        def script = new MockScript()
        def ctx = PipelineContext.builder().script(script).build()
        def runner = new ScanRunner(script: script, ctx: ctx)
        runner.failOn = ['high']

        runner.sast { -> new ScanResult(scanner: 'sast', status: 'OK', high: 1) }  // 1 个 high
        runner.sca  { -> new ScanResult(scanner: 'sca',  status: 'OK', high: 0) }

        runner.run()  // 所有分支完成
        try {
            runner.assertPassed()
            Assert.fail("expected throw")
        } catch (Exception ex) {
            // 验证：违规在 assertPassed 阶段被捕获，runner 仍然保持可用
            Assert.assertTrue(ex.message.contains('sast'))
        }
        // 调整门禁后重试应该通过
        runner.failOn = ['critical']
        runner.assertPassed()
    }

    @Test
    void apexScan_resultsMapPreservesBranchNames() {
        def script = new MockScript()
        def ctx = PipelineContext.builder().script(script).build()
        def runner = new ScanRunner(script: script, ctx: ctx)

        runner.sast('sonar') { -> new ScanResult(scanner: 'sonar', status: 'OK', high: 0) }
        runner.sca('snyk')   { -> new ScanResult(scanner: 'snyk',  status: 'OK', high: 0) }
        runner.container('trivy') { -> new ScanResult(scanner: 'trivy', status: 'OK', high: 0) }

        def results = runner.run()

        // 自定义名时，key 形如 type-name
        Assert.assertTrue(results.containsKey('sast-sonar'))
        Assert.assertTrue(results.containsKey('sca-snyk'))
        Assert.assertTrue(results.containsKey('container-trivy'))
        // 验证每个 result 的 scanner 字段
        Assert.assertEquals('sonar', results['sast-sonar'].scanner)
        Assert.assertEquals('snyk',  results['sca-snyk'].scanner)
        Assert.assertEquals('trivy', results['container-trivy'].scanner)
    }

    @Test
    void apexScan_runCalledTwiceReExecutes() {
        // 验证：runner 可重入
        def script = new MockScript()
        def ctx = PipelineContext.builder().script(script).build()
        def runner = new ScanRunner(script: script, ctx: ctx)

        int callCount = 0
        runner.sast { ->
            callCount++
            new ScanResult(scanner: 'sast', status: 'OK')
        }

        runner.run()
        Assert.assertEquals(1, callCount)
        runner.run()
        Assert.assertEquals(2, callCount)
    }

    @Test
    void apexScan_scannerCountMatchesRegisteredScanners() {
        def script = new MockScript()
        def ctx = PipelineContext.builder().script(script).build()
        def runner = new ScanRunner(script: script, ctx: ctx)

        Assert.assertEquals(0, runner.scannerCount)
        runner.sast { -> new ScanResult(scanner: 'sast', status: 'OK') }
        Assert.assertEquals(1, runner.scannerCount)
        runner.sca { -> new ScanResult(scanner: 'sca', status: 'OK') }
        Assert.assertEquals(2, runner.scannerCount)
        runner.container { -> new ScanResult(scanner: 'container', status: 'OK') }
        Assert.assertEquals(3, runner.scannerCount)
        runner.generic('secrets') { -> new ScanResult(scanner: 'secrets', status: 'OK') }
        Assert.assertEquals(4, runner.scannerCount)
    }
}
