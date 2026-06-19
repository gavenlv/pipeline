// =========================================================================
// apexPublish — 制品发布到 Nexus
//
//   apexPublish('https://nexus.acme', 'maven-releases', 'maven2') { pub ->
//       pub.maven(['mvn', 'deploy', '--batch-mode'])
//   }
// =========================================================================
import com.hsbc.treasury.apex.ci.artifact.NexusClient
import com.hsbc.treasury.apex.ci.artifact.ArtifactPublisher
import com.hsbc.treasury.apex.ci.core.PipelineContext

def call(String baseUrl, String repository, String format, Closure body) {
    Object script = this
    PipelineContext ctx = script.binding?.hasVariable('apexCtx') ? script.apexCtx :
        PipelineContext.builder().script(script).build()

    NexusClient client = NexusClient.of(baseUrl, repository, format, null)
    ArtifactPublisher pub = new ArtifactPublisher(client)

    script.stage("publish-${format}") {
        body.delegate = pub
        body.resolveStrategy = Closure.DELEGATE_FIRST
        body(ctx)
    }
    return pub
}

return this
