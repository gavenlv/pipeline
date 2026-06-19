package com.hsbc.treasury.apex.ci.docker

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.utils.Sandbox
import com.hsbc.treasury.apex.ci.errors.ApexCIException

/**
 * Docker 镜像构建器。沙箱安全，使用 list-form 拼装 buildx 命令。
 */

class DockerBuilder implements Serializable {
    private static final long serialVersionUID = 1L

    /** 装配命令：docker buildx build ... */
    static List<String> assembleCommand(String imageRef, DockerBuildConfig cfg) {
        if (imageRef == null) throw new ApexCIException("imageRef required")
        List<String> cmd = ['docker', 'buildx', 'build']
        cmd << '--file' << cfg.dockerfile
        cmd << '--tag'  << imageRef
        for (String p : cfg.platforms) cmd << '--platform' << p
        for (String a : cfg.buildArgs) cmd << '--build-arg' << a
        for (String s : cfg.secrets)   cmd << '--secret'    << s
        for (String c : cfg.cacheFrom) cmd << '--cache-from' << c
        if (cfg.noCache) cmd << '--no-cache'
        if (cfg.networkMode) cmd << '--network' << cfg.networkMode
        if (cfg.pushOnBuild) cmd << '--push'
        else cmd << '--load'
        cmd << cfg.context
        if (cfg.params != null) {
            cfg.params.flags.each { cmd << it }
            cfg.params.props.each { k, v -> cmd << "--${k}=${v}".toString() }
            cfg.params.positionals.each { cmd << it }
        }
        return cmd
    }

    String build(PipelineContext ctx, String imageRef, Closure body) {
        DockerBuildConfig cfg = DockerBuildConfig.fromClosure(body)
        List<String> cmd = assembleCommand(imageRef, cfg)
        ctx.script?.echo("==> [docker build] ${cmd.join(' ')}")
        Sandbox.runShell(ctx, cmd, "docker-build".toString())
        return imageRef
    }
}
