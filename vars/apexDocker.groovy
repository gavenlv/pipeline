// =========================================================================
// apexDocker — Docker 镜像构建与推送
//
//   apexDocker {
//       build('ghcr.io/acme/app:1.0.0') { tags = ['1.0.0','latest']; buildArgs = ['NODE=20'] }
//       push('ghcr.io/acme/app:1.0.0', 'ghcr-creds')
//   }
// =========================================================================
import com.hsbc.treasury.apex.ci.docker.DockerBuilder
import com.hsbc.treasury.apex.ci.docker.DockerPusher
import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.core.Stage
import com.hsbc.treasury.apex.ci.errors.ApexCIException

def call(Closure body) {
    Object script = this
    PipelineContext ctx = script.binding?.hasVariable('apexCtx') ? script.apexCtx :
        PipelineContext.builder().script(script).build()

    def list = []
    body.delegate = [
        build: { String ref, Closure cfg ->
            list << ['op': 'build', 'ref': ref, 'cfg': cfg]
        },
        push: { String ref, String creds = null ->
            list << ['op': 'push', 'ref': ref, 'creds': creds]
        }
    ]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body()

    list.each { Map op ->
        script.stage("docker-${op.op}") {
            if (op.op == 'build') {
                new DockerBuilder().build(ctx, op.ref as String, op.cfg as Closure)
            } else if (op.op == 'push') {
                new DockerPusher().push(ctx, op.ref as String, op.creds as String)
            } else {
                throw new ApexCIException("Unknown docker op: ${op.op}".toString())
            }
        }
    }
    return list
}

return this
