package com.hsbc.treasury.apex.ci.integration

import com.hsbc.treasury.apex.ci.builders.BuilderFactory
import com.hsbc.treasury.apex.ci.core.DynamicParams
import com.hsbc.treasury.apex.ci.core.JenkinsSleeper
import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.core.Retry
import com.hsbc.treasury.apex.ci.errors.ApexCIException
import com.hsbc.treasury.apex.ci.scanners.ScanResult
import com.hsbc.treasury.apex.ci.scanners.ScanRunner
import com.hsbc.treasury.apex.ci.utils.MockScript
import org.junit.Assert
import org.junit.Test

/**
 * 轻量级 DSL 集成测试：
 * 覆盖用户提出的"并行、等待扫描、外部服务不稳定"等典型场景。
 *
 * 这些测试不依赖 Jenkins runtime，纯粹在 MockScript 沙箱里跑，
 * 验证：
 *  1. apexScan 走原生 parallel 时的异常隔离
 *  2. apexScan 等待所有扫描分支完成后才返回
 *  3. apexRetry 在外部服务不稳定时能恢复
 *  4. apexBuild / apexDocker 等命令组装正确
 *  5. ctx.attrs 跨 stage 共享数据
 */
class LightweightDslTest {

    // ============================================================
    // 1. 并行场景：apexScan 走原生 parallel
    // ============================================================

    @Test
    void scanRunner_usesNativeParallelForMultipleBranches() {
        def script = new MockScript()
        def runner = new ScanRunner(script: script, ctx: PipelineContext.builder().script(script).build())
        runner.sast { -> new ScanResult(scanner: 'sast', status: 'OK', high: 0) }
        runner.sca  { -> new ScanResult(scanner: 'sca',  status: 'OK', high: 0) }
        runner.container { -> new ScanResult(scanner: 'container', status: 'OK', high: 0) }

        def results = runner.run()

        // 3 个分支 => 走 parallel，不走单分支 fallback
        Assert.assertEquals(1, script.parallels.size())
        Assert.assertEquals(3, script.parallels[0].blocks.size())
        Assert.assertEquals(3, results.size())
    }

    @Test
    void scanRunner_singleBranchDoesNotUseParallel() {
        def script = new MockScript()
        def runner = new ScanRunner(script: script, ctx: PipelineContext.builder().script(script).build())
        runner.sast { -> new ScanResult(scanner: 'sast', status: 'OK', high: 0) }

        def results = runner.run()

        Assert.assertTrue("expected single branch to skip parallel",
            script.parallels.isEmpty())
        Assert.assertEquals(1, results.size())
    }

    @Test
    void scanRunner_isolatesExceptionsAcrossBranches() {
        def script = new MockScript()
        def runner = new ScanRunner(script: script, ctx: PipelineContext.builder().script(script).build())

        // 一个 OK
        runner.sast { -> new ScanResult(scanner: 'sast', status: 'OK', high: 0) }
        // 一个抛错（模拟外部服务不可用）
        runner.sca { -> throw new RuntimeException("SCA server is down") }
        // 一个 OK
        runner.container { -> new ScanResult(scanner: 'container', status: 'OK', high: 0) }

        def results = runner.run()

        // OK 的两个分支不应被失败的分支影响
        Assert.assertEquals('OK',     results['sast-sast'].status)
        Assert.assertEquals('OK',     results['container-container'].status)
        // 失败的分支被隔离为 FAILED
        Assert.assertEquals('FAILED', results['sca-sca'].status)
        Assert.assertNotNull(results['sca-sca'].error)
        Assert.assertTrue(results['sca-sca'].summary.contains("SCA server is down"))
    }

    // ============================================================
    // 2. 等待扫描：apexScan 阻塞直到所有分支完成
    // ============================================================

    @Test
    void scanRunner_blocksUntilAllBranchesComplete() {
        def script = new MockScript()
        // 模拟每个分支 sleep，记录完成时间
        List<Long> completionTimes = []
        long start = System.currentTimeMillis()
        script.timeoutBehavior = { Map args, Closure body -> body.call() }

        def runner = new ScanRunner(script: script, ctx: PipelineContext.builder().script(script).build())
        runner.sast { ->
            Thread.sleep(20)
            completionTimes << (System.currentTimeMillis() - start)
            new ScanResult(scanner: 'sast', status: 'OK')
        }
        runner.sca { ->
            Thread.sleep(20)
            completionTimes << (System.currentTimeMillis() - start)
            new ScanResult(scanner: 'sca', status: 'OK')
        }
        runner.container { ->
            Thread.sleep(20)
            completionTimes << (System.currentTimeMillis() - start)
            new ScanResult(scanner: 'container', status: 'OK')
        }

        // 顺序执行模拟下：总时长应 ~= 3 * 20ms
        def results = runner.run()
        long elapsed = System.currentTimeMillis() - start

        // 在 MockScript.parallel 里是顺序执行（不是真并行），所以这里测的是"全部完成后才返回"
        Assert.assertEquals(3, results.size())
        Assert.assertTrue("should wait for all branches, elapsed=${elapsed}ms",
            elapsed >= 50)
    }

    @Test
    void scanRunner_assertPassedRunsGateAfterAllScansDone() {
        def script = new MockScript()
        def runner = new ScanRunner(script: script, ctx: PipelineContext.builder().script(script).build())
        runner.sast { -> new ScanResult(scanner: 'sast', status: 'OK', high: 0) }
        runner.sca  { -> new ScanResult(scanner: 'sca',  status: 'OK', high: 2) }

        runner.failOn = ['high']
        try {
            runner.assertPassed()
            Assert.fail("expected gate to fail")
        } catch (ApexCIException ex) {
            // 在所有扫描完成之后才执行门禁
            Assert.assertTrue(ex.message.contains("sca:high=2"))
        }
    }

    @Test
    void scanRunner_assertPassedPassesWhenAllClean() {
        def script = new MockScript()
        def runner = new ScanRunner(script: script, ctx: PipelineContext.builder().script(script).build())
        runner.sast { -> new ScanResult(scanner: 'sast', status: 'OK', high: 0, medium: 3) }
        runner.sca  { -> new ScanResult(scanner: 'sca',  status: 'OK', high: 0, medium: 0) }

        runner.failOn = ['high']
        // 不应抛异常
        runner.assertPassed()
    }

    @Test
    void scanRunner_emptyFailOnSkipsGate() {
        def script = new MockScript()
        def runner = new ScanRunner(script: script, ctx: PipelineContext.builder().script(script).build())
        runner.sast { -> new ScanResult(scanner: 'sast', status: 'OK', high: 99) }

        runner.failOn = []
        // 关闭门禁：即使 high=99 也不抛
        runner.assertPassed()
    }

    // ============================================================
    // 3. 外部服务不稳定：Retry 恢复
    // ============================================================

    @Test
    void retry_recoversFromTransientExternalServiceError() {
        // 模拟 Nexus 偶发 502：第 1、2 次失败，第 3 次成功
        int attempt = 0
        def out = Retry.linear(5, 5L).execute { ->
            attempt++
            if (attempt < 3) throw new RuntimeException("502 Bad Gateway (attempt ${attempt})".toString())
            return 'ok'
        }
        Assert.assertEquals('ok', out)
        Assert.assertEquals(3, attempt)
    }

    @Test
    void retry_givesUpAfterMaxAttempts() {
        int attempt = 0
        try {
            Retry.linear(3, 5L).execute { ->
                attempt++
                throw new RuntimeException("connection refused (attempt ${attempt})".toString())
            }
            Assert.fail("expected exception after max attempts")
        } catch (ApexCIException ex) {
            Assert.assertTrue(ex.message.contains('exhausted'))
            Assert.assertEquals(3, attempt)
        }
    }

    @Test
    void retry_exponentialBackoffActuallyWaits() {
        int attempt = 0
        long start = System.currentTimeMillis()
        try {
            // 注：Sleeper.sleep 接受 int 秒。1000ms = 1s, 2000ms = 2s → 至少 3 秒
            new Retry(maxAttempts: 3, initialDelayMs: 1000, backoffMultiplier: 2.0,
                      sleeper: new JenkinsSleeper(null)).execute { ->
                attempt++
                throw new RuntimeException("transient")
            }
        } catch (ApexCIException ignore) { }
        long elapsed = System.currentTimeMillis() - start

        // 1s + 2s = 至少 3 秒等待（实际会更长因 thread sleep 不精确）
        Assert.assertTrue("expected exponential backoff >= 2s, got ${elapsed}ms".toString(),
            elapsed >= 2000)
        Assert.assertEquals(3, attempt)
    }

    @Test
    void retry_continuesToRetryOnNonFatalErrors() {
        // 模拟 Nexus 第一次返回 401（凭据问题）后业务方调整
        // 但 Retry 不知道这是 fatal，应重试（让外层重试机制捕获）
        int attempt = 0
        def out = Retry.linear(2, 5L).execute { ->
            attempt++
            if (attempt == 1) throw new RuntimeException("401 Unauthorized")
            return 'ok'
        }
        Assert.assertEquals('ok', out)
        Assert.assertEquals(2, attempt)
    }

    // ============================================================
    // 4. 命令组装：Builder 拼装外部命令
    // ============================================================

    @Test
    void javaBuilder_assemblesMavenCommandArray() {
        def script = new MockScript()
        def ctx = PipelineContext.builder().script(script).build()
        def builder = BuilderFactory.of('java')

        // 模拟：execute 内部走 sh(script: ...) 数组形式
        Closure body = {
            jdk = 17
            buildTool = 'maven'
            goals = ['clean', 'verify']
            cliOptions = ['--batch-mode']
            properties = ['maven.javadoc.skip': 'true']
            params {
                flag('--debug')
                property('git.commit.id.abbrev', '7')
            }
        }
        builder.execute(ctx, body, [:])

        // 应当至少 1 次 sh 调用
        Assert.assertTrue("expected sh to be called", script.shCalls.size() >= 1)
        // 验证 sh 被以某种形式调用，包含关键参数
        def last = script.shCalls[-1]
        def rendered = (last.script ?: '').toString()
        Assert.assertTrue("expected rendered script to contain key params, got: ${rendered}".toString(),
            rendered.contains('clean'))
        Assert.assertTrue(rendered.contains('verify'))
        Assert.assertTrue(rendered.contains('--batch-mode'))
        Assert.assertTrue(rendered.contains('maven.javadoc.skip=true'))
        Assert.assertTrue(rendered.contains('--debug'))
        Assert.assertTrue(rendered.contains('git.commit.id.abbrev=7'))
    }

    @Test
    void dynamicParams_freeAdditionAndRemoval() {
        // 模拟业务方在脚本里反复加减
        def p = new DynamicParams()
        p.flag('--batch-mode')
        p.flag('-DskipTests')
        p.property('maven.javadoc.skip', 'true')

        Assert.assertEquals(2, p.flags.size())
        Assert.assertEquals(1, p.props.size())

        // 减一个 flag
        p.removeFlag('--batch-mode')
        Assert.assertEquals(1, p.flags.size())
        Assert.assertFalse(p.flags.contains('--batch-mode'))

        // 链式 + 加 positional
        p.addPositional('clean').addPositional('verify')
        Assert.assertEquals(['clean', 'verify'], p.positionals)
    }

    // ============================================================
    // 5. 跨 stage 共享 ctx
    // ============================================================

    @Test
    void ctx_attrsSurviveAcrossStages() {
        def script = new MockScript()
        def ctx = PipelineContext.builder().script(script).build()

        // "Build" 阶段写入
        ctx.setAttr('build.commit', 'abc1234')
        ctx.setAttr('build.timestamp', 1717900000000L)

        // 模拟"另一个 stage"重新读
        Assert.assertEquals('abc1234', ctx.getAttr('build.commit'))
        Assert.assertEquals(1717900000000L, ctx.getAttr('build.timestamp'))
        Assert.assertTrue(ctx.hasAttr('build.commit'))
        Assert.assertNull(ctx.getAttr('build.branch'))
        Assert.assertEquals('main', ctx.getAttr('build.branch', 'main'))
    }

    // ============================================================
    // 6. ScanRunner + ConsoleReporter 集成
    // ============================================================

    @Test
    void scanRunner_emitsReporterOutput() {
        def script = new MockScript()
        def ctx = PipelineContext.builder().script(script).build()
        def runner = new ScanRunner(script: script, ctx: ctx)
        runner.sast { -> new ScanResult(scanner: 'sast', status: 'OK', high: 0) }
        runner.sca  { -> new ScanResult(scanner: 'sca',  status: 'OK', high: 0) }

        runner.run()

        // ConsoleReporter 通过 ctx.log / script.echo 输出
        // 验证 script.echo 至少被调用过
        Assert.assertTrue("ConsoleReporter should emit summary lines",
            script.echos.size() > 0)
        // 应能看到 sast / sca 的状态
        def joined = script.echos.join('\n')
        Assert.assertTrue("reporter should mention sca result, got: ${joined}".toString(),
            joined.contains('sca'))
    }

    // ============================================================
    // 7. 异常路径：Builder 参数缺失应立即抛
    // ============================================================

    @Test
    void javaBuilder_missingBuildToolFailsFast() {
        def script = new MockScript()
        def ctx = PipelineContext.builder().script(script).build()
        def builder = BuilderFactory.of('java')
        try {
            builder.execute(ctx, {
                jdk = 17
                // 故意覆盖为不支持的工具
                buildTool = 'ant'
                goals = ['clean']
            } as Closure)
            Assert.fail("expected build error for unsupported tool")
        } catch (com.hsbc.treasury.apex.ci.errors.BuildException ex) {
            Assert.assertTrue(ex.message.contains('buildTool') || ex.message.contains('ant'))
        }
    }

    @Test
    void javaBuilder_emptyGoalsFailsFast() {
        def script = new MockScript()
        def ctx = PipelineContext.builder().script(script).build()
        def builder = BuilderFactory.of('java')
        try {
            builder.execute(ctx, {
                buildTool = 'maven'
                goals = []
            } as Closure)
            Assert.fail("expected build error")
        } catch (com.hsbc.treasury.apex.ci.errors.BuildException ex) {
            Assert.assertTrue(ex.message.contains('goals'))
        }
    }

    // ============================================================
    // 8. 错误隔离：单个扫描失败不应让其它 OK 扫描门禁失败
    // ============================================================

    @Test
    void scanRunner_othersPassEvenWhenOneFails() {
        def script = new MockScript()
        def runner = new ScanRunner(script: script, ctx: PipelineContext.builder().script(script).build())
        runner.sast { -> new ScanResult(scanner: 'sast', status: 'OK', high: 0) }
        runner.sca  { -> throw new RuntimeException("SCA down") }
        runner.container { -> new ScanResult(scanner: 'container', status: 'OK', high: 0) }

        runner.failOn = ['high']  // 默认门禁
        try {
            runner.assertPassed()
            Assert.fail("expected gate to fail because SCA is FAILED")
        } catch (ApexCIException ex) {
            // 应只提到 sca FAILED，不应提到 sast/container
            Assert.assertTrue("expected sca in error, got: ${ex.message}".toString(),
                ex.message.contains('sca'))
            Assert.assertTrue("expected FAILED status, got: ${ex.message}".toString(),
                ex.message.contains('FAILED'))
        }
    }
}
