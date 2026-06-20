// =========================================================================
// apexBuild — 多语言构建入口（轻量版）
//
//   stage('Build') {
//       apexBuild('java') {
//           jdk = 17
//           buildTool = 'maven'           // maven | gradle
//           goals = ['clean', 'package']
//           params {                     // DynamicParams：动态参数
//               flag('--batch-mode')
//               property('maven.javadoc.skip', 'true')
//               positional('install')
//           }
//       }
//   }
//
//   apexBuild('node') { packageManager = 'npm'; scripts = ['install','test'] }
//   apexBuild('python') { venv = false; requirements = ['six'] }
//   apexBuild('go') { main = './cmd/app'; targets = ['build','test'] }
//   apexBuild() { /* 等价于 autoDetect */ }
//   apexBuild('java', shellStyle: 'string') { ... }   // 走原生 sh 字符串
// =========================================================================
import com.hsbc.treasury.apex.ci.builders.BuilderFactory
import com.hsbc.treasury.apex.ci.core.PipelineContext

def call(String language, Map opts, Closure body) {
    Object script = this
    PipelineContext ctx = script.binding?.hasVariable('apexCtx') ? script.apexCtx :
        PipelineContext.builder().script(script).build()
    BuilderFactory.of(language).execute(ctx, body, opts)
    return null
}

def call(String language, Closure body) {
    call(language, [:], body)
}

def call(Map opts, Closure body) {
    Object script = this
    PipelineContext ctx = script.binding?.hasVariable('apexCtx') ? script.apexCtx :
        PipelineContext.builder().script(script).build()
    String lang = BuilderFactory.autoDetect(new File(ctx.workDir ?: '.'))
    BuilderFactory.of(lang).execute(ctx, body, opts)
    return null
}

def call(Closure body) {
    call([:], body)
}

return this
