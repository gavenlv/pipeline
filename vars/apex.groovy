// =========================================================================
// apex — apex-ci-library 主入口（轻量版）
//
// 理念：尽量复用 Jenkins 原生 stage / parallel / sh，本库只注入上下文和
// 提供"与外部交换"的工具（构建、扫描、Docker、发布、通知、重试、配置）。
//
// 用法示例：
//   apex {                              // 可选：建立共享 PipelineContext
//       node('linux') {                 // 原生 node { } 块
//           stage('Build') {
//               apexBuild('java') {
//                   jdk = 17
//                   goals = ['clean', 'package']
//                   params { flag('--batch-mode'); property('maven.javadoc.skip', 'true') }
//               }
//           }
//           stage('Tests') {
//               parallel 'unit': { sh './mvnw test -Dtest=Unit' },
//                         'integ': { sh './mvnw test -Dtest=Integ' }
//           }
//           stage('Security') {
//               def r = apexScan {                      // parallel scanner
//                   sast    { sh 'sonar-scanner ...' }
//                   sca     { sh 'snyk test --json' }
//                   container('app:1.0.0') { sh 'trivy image app:1.0.0' }
//               }
//               r.assertPassed()
//           }
//       }
//   }
// =========================================================================
import com.hsbc.treasury.apex.ci.core.PipelineContext

def call(Closure body) {
    Object script = this
    // 把共享上下文注入到 script 绑定，apexBuild / apexScan / apexPublish 等
    // 都可以读取，避免每次重复传 ctx。
    PipelineContext ctx = (script.binding?.hasVariable('apexCtx') ? script.apexCtx :
        PipelineContext.builder().script(script).build())
    script.binding?.setVariable('apexCtx', ctx)
    body.delegate = ctx
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body()
    return ctx
}

return this
