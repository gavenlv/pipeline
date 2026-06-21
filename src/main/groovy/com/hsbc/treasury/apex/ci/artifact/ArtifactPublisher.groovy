package com.hsbc.treasury.apex.ci.artifact

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.utils.Sandbox
import com.hsbc.treasury.apex.ci.errors.ApexCIException

/**
 * 制品发布器。统一管理 Maven / npm / PyPI / Raw / Docker 制品。
 *
 * 内部所有方法走 Sandbox.runShell 调原生 script.sh(script: ...)。
 * DSL 入口（maven/npm/pypi/raw/custom）供 apexPublish 闭包调用。
 */
class ArtifactPublisher implements Serializable {
    private static final long serialVersionUID = 1L

    final NexusClient client
    /** 关联的 PipelineContext（apexPublish 入口注入） */
    transient PipelineContext ctx

    ArtifactPublisher(NexusClient client) {
        if (client == null) throw new ApexCIException("NexusClient required")
        this.client = client
    }

    /** Maven：注入 -DaltDeploymentRepository，调用 sandbox shell */
    void publishMaven(PipelineContext ctx, List<String> mvnArgs) {
        if (mvnArgs == null || mvnArgs.isEmpty()) throw new ApexCIException("mvnArgs required")
        List<String> cmd = mvnArgs.collect { it.toString() }
        cmd << "-DaltDeploymentRepository=nexus::default::${client.mavenDistributionUrl()}".toString()
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

    // === DSL 闭包方法（apexPublish delegate 入口） ===

    /** Maven 发布（可选 withCredentials 包装） */
    void maven(List<String> mvnArgs = [], String credentialsId = null, Closure body = null) {
        Object script = ctx?.script
        Closure work = { ->
            publishMaven(ctx, mvnArgs)
            if (body != null) body()
        }
        if (script != null && credentialsId && script.respondsTo('withCredentials')) {
            script.withCredentials([script.usernamePassword(credentialsId: credentialsId,
                                                             usernameVariable: 'NEXUS_USER',
                                                             passwordVariable: 'NEXUS_PASS')]) {
                work.call()
            }
        } else {
            work.call()
        }
    }

    void npm(Closure body = null) {
        publishNpm(ctx)
        if (body != null) body()
    }

    void pypi(String distDir = 'dist', Closure body = null) {
        publishPyPi(ctx, distDir)
        if (body != null) body()
    }

    void raw(String remotePath, String localFile, String contentType = 'application/octet-stream') {
        publishRaw(ctx, remotePath, localFile, contentType)
    }

    void custom(List<String> cmd) {
        Sandbox.runShell(ctx, cmd, "custom-shell".toString())
    }
}
