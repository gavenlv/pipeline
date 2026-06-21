// =========================================================================
// apexScan — 并发扫描入口（轻量版）
//
// 用法：
//   stage('Security') {
//       def r = apexScan {
//           sast  { sh 'sonar-scanner ...' }
//           sca   { sh 'snyk test --json' }
//           container('app:1.0.0') { sh 'trivy image app:1.0.0' }
//           generic('license', { sh 'license-check' })
//       }
//       r.failOn = ['high','medium']
//       r.assertPassed()       // 抛出异常若任何门禁失败
//   }
//
// 实现要点：
// - 闭包内通过 Jenkins 原生 parallel 块执行，沙箱安全
// - 每个分支使用 catchError 隔离异常，转换为 ScanResult
// - 支持 timeout（每个 branch）、failOn 门禁、汇总到 ConsoleReporter
// =========================================================================
import com.hsbc.treasury.apex.ci.scanners.ScanRunner
import com.hsbc.treasury.apex.ci.scanners.ScanResult
import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.errors.ApexCIException

def call(Closure body) {
    Object script = this
    PipelineContext ctx = script.binding?.hasVariable('apexCtx') ? script.apexCtx :
        PipelineContext.builder().script(script).build()

    ScanRunner runner = new ScanRunner(script: script, ctx: ctx)
    body.delegate = runner
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body()
    return runner
}

return this
