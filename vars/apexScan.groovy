// =========================================================================
// apexScan — 并发扫描入口
//
//   def collector = apexScan { scanners ->
//       scanners.sast { ctx -> new GenericCliScanner('sonar', { ... }).run(ctx) }
//       scanners.sca  { ctx -> new GenericCliScanner('snyk',  { ... }).run(ctx) }
//   }
//   collector.failOn = ['high', 'medium']
// =========================================================================
import com.hsbc.treasury.apex.ci.scanners.ScannerCollector
import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.core.Stage
import com.hsbc.treasury.apex.ci.core.LambdaStep
import com.hsbc.treasury.apex.ci.errors.ApexCIException
import com.hsbc.treasury.apex.ci.reporters.ConsoleReporter

def call(Closure body) {
    Object script = this
    PipelineContext ctx = script.binding?.hasVariable('apexCtx') ? script.apexCtx :
        PipelineContext.builder().script(script).build()
    ScannerCollector sc = new ScannerCollector()
    sc.withScript(script)
    body.delegate = sc
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body()
    script.stage('apexScan') {
        def results = sc.run(ctx)
        sc.assertPassed(results)
        def values = results.findAll { it.status == 'OK' }.collect { it.value }
        new ConsoleReporter().reportScan(ctx, values)
        script.currentBuild?.setDescription?.("scan:ok")
    }
    return sc
}

return this
