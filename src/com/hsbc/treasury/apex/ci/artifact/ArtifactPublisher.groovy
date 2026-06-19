package com.hsbc.treasury.apex.ci.artifact

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.utils.Sandbox
import com.hsbc.treasury.apex.ci.errors.ApexCIException

/**
 * 制品发布器。统一管理 Maven / npm / PyPI / Raw / Docker 制品。
 */

class ArtifactPublisher implements Serializable {
    private static final long serialVersionUID = 1L

    private final NexusClient client

    ArtifactPublisher(NexusClient client) {
        if (client == null) throw new ApexCIException("NexusClient required")
        this.client = client
    }

    void publishMaven(PipelineContext ctx, List<String> mvnArgs) {
        if (mvnArgs == null || mvnArgs.isEmpty()) throw new ApexCIException("mvnArgs required")
        // 注入 -DaltDeploymentRepository
        List<String> cmd = mvnArgs.collect { it.toString() }
        cmd << '-DaltDeploymentRepository=' + (client.credentialsId ?
                "nexus::default::${client.mavenDistributionUrl()}".toString() :
                "nexus::default::${client.mavenDistributionUrl()}".toString())
        Sandbox.runShell(ctx, cmd, "nexus-maven-publish".toString())
    }

    void publishNpm(PipelineContext ctx) {
        Sandbox.runShell(ctx, client.buildNpmPublish(), "nexus-npm-publish".toString())
    }

    void publishPyPi(PipelineContext ctx, String distDir) {
        Sandbox.runShell(ctx, client.buildTwineUpload(distDir), "nexus-pypi-publish".toString())
    }

    void publishRaw(PipelineContext ctx, String remotePath, String localFile, String contentType = 'application/octet-stream') {
        Sandbox.runShell(ctx, client.buildPut(remotePath, localFile, contentType), "nexus-raw-publish".toString())
    }
}
