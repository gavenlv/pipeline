// =========================================================================
// apexDocker — Docker 镜像构建与推送（轻量版）
//
// 用法：
//   stage('Build Image') {
//       apexDocker('ghcr.io/acme/app:1.0.0') {
//           dockerfile = 'docker/Dockerfile'
//           buildArgs  = ['NODE_VERSION=20']
//           platforms  = ['linux/amd64', 'linux/arm64']
//           noCache    = false
//           secrets    = ['GITHUB_TOKEN=gh_pat_xxx']
//       }
//   }
//
//   stage('Push') {
//       apexDocker.push('ghcr.io/acme/app:1.0.0', 'ghcr-creds')
//   }
// =========================================================================
import com.hsbc.treasury.apex.ci.docker.DockerBuilder
import com.hsbc.treasury.apex.ci.docker.DockerPusher
import com.hsbc.treasury.apex.ci.docker.DockerBuildConfig
import com.hsbc.treasury.apex.ci.core.PipelineContext

/** 构建镜像：使用原生 sh 调用 docker buildx */
def call(String imageRef, Closure body) {
    Object script = this
    PipelineContext ctx = script.binding?.hasVariable('apexCtx') ? script.apexCtx :
        PipelineContext.builder().script(script).build()
    script.stage("docker-build:${imageRef}".toString()) {
        DockerBuildConfig cfg = body ? DockerBuildConfig.fromClosure(body) :
            new DockerBuildConfig(imageRef: imageRef)
        cfg.imageRef = imageRef
        new DockerBuilder().build(ctx, cfg)
    }
}

/** 推送镜像：使用原生 sh 调用 docker push / buildx --push */
def push(String imageRef, String credentialsId = null) {
    Object script = this
    PipelineContext ctx = script.binding?.hasVariable('apexCtx') ? script.apexCtx :
        PipelineContext.builder().script(script).build()
    script.stage("docker-push:${imageRef}".toString()) {
        new DockerPusher().push(ctx, imageRef, credentialsId)
    }
}

/** 一步构建并推送 */
def buildAndPush(String imageRef, Closure body = null, String credentialsId = null) {
    Object script = this
    PipelineContext ctx = script.binding?.hasVariable('apexCtx') ? script.apexCtx :
        PipelineContext.builder().script(script).build()
    script.stage("docker-bp:${imageRef}".toString()) {
        DockerBuildConfig cfg = body ? DockerBuildConfig.fromClosure(body) :
            new DockerBuildConfig(imageRef: imageRef)
        cfg.imageRef = imageRef
        new DockerBuilder().buildAndPush(ctx, cfg, credentialsId)
    }
}

return this
