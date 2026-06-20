// =========================================================================
// apexPublish — 制品发布到 Nexus（轻量版）
//
// 用法：
//   stage('Publish') {
//       apexPublish('https://nexus.acme', 'maven-releases', 'maven2') {
//           // 内置命令模板（maven2 / npm / pypi / raw）
//           maven(['-Dmaven.javadoc.skip=true'], 'deployer-creds') {
//               // 在 withCredentials 块中执行
//               sh 'mvn deploy --batch-mode -DskipTests'
//           }
//           // 或自定义命令
//           custom(['curl', '-T', 'pkg.tar.gz', 'https://.../repo/'])
//       }
//   }
// =========================================================================
import com.hsbc.treasury.apex.ci.artifact.NexusClient
import com.hsbc.treasury.apex.ci.artifact.ArtifactPublisher
import com.hsbc.treasury.apex.ci.core.PipelineContext

def call(String baseUrl, String repository, String format, Closure body = null) {
    Object script = this
    PipelineContext ctx = script.binding?.hasVariable('apexCtx') ? script.apexCtx :
        PipelineContext.builder().script(script).build()
    NexusClient client = NexusClient.of(baseUrl, repository, format, null)
    ArtifactPublisher pub = new ArtifactPublisher(client)
    pub.ctx = ctx
    script.stage("publish-${format}".toString()) {
        if (body != null) {
            body.delegate = pub
            body.resolveStrategy = Closure.DELEGATE_FIRST
            body(ctx)
        }
    }
    return pub
}

return this
