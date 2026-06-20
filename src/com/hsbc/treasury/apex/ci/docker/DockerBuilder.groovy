package com.hsbc.treasury.apex.ci.docker

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.utils.Sandbox
import com.hsbc.treasury.apex.ci.errors.ApexCIException

/**
 * Docker 镜像构建器。沙箱安全，使用 list-form 拼装 buildx 命令。
 * 内部走原生 script.sh(script: ...) 调用，不依赖任何自定义 stage 包装。
 */

class DockerBuilder implements Serializable {
    private static final long serialVersionUID = 1L

    /** 装配命令：docker buildx build ... */
    static List<String> assembleCommand(String imageRef, DockerBuildConfig cfg) {
        if (imageRef == null) throw new ApexCIException("imageRef required")
        if (cfg == null) throw new ApexCIException("DockerBuildConfig required")
        List<String> cmd = ['docker', 'buildx', 'build']
        cmd << '--file' << (cfg.dockerfile ?: 'Dockerfile')
        cmd << '--tag'  << imageRef
        for (String p : (cfg.platforms ?: ['linux/amd64'])) cmd << '--platform' << p
        for (String a : (cfg.buildArgs ?: []))              cmd << '--build-arg' << a
        for (String s : (cfg.secrets  ?: []))               cmd << '--secret'    << s
        for (String c : (cfg.cacheFrom ?: []))              cmd << '--cache-from' << c
        if (cfg.noCache)    cmd << '--no-cache'
        if (cfg.networkMode) cmd << '--network' << cfg.networkMode
        if (cfg.pushOnBuild) cmd << '--push'
        else                 cmd << '--load'
        cmd << (cfg.context ?: '.')
        if (cfg.params != null) {
            cfg.params.flags.each { cmd << it }
            cfg.params.props.each { k, v -> cmd << "--${k}=${v}".toString() }
            cfg.params.positionals.each { cmd << it }
        }
        return cmd
    }

    /** 仅构建 */
    String build(PipelineContext ctx, DockerBuildConfig cfg) {
        if (cfg == null) throw new ApexCIException("DockerBuildConfig required")
        if (cfg.imageRef == null) throw new ApexCIException("imageRef required in cfg")
        if (cfg.pushOnBuild) {
            // 推送模式需要仓库认证
            return buildAndPush(ctx, cfg, null)
        }
        List<String> cmd = assembleCommand(cfg.imageRef, cfg)
        ctx.script?.echo("==> [docker build] ${cmd.join(' ')}")
        Sandbox.runShell(ctx, cmd, "docker-build".toString())
        return cfg.imageRef
    }

    /** 仅推送（假设本地已 load） */
    String buildAndPush(PipelineContext ctx, DockerBuildConfig cfg, String credentialsId) {
        if (cfg == null) throw new ApexCIException("DockerBuildConfig required")
        if (cfg.imageRef == null) throw new ApexCIException("imageRef required in cfg")
        List<String> cmd = assembleCommand(cfg.imageRef, cfg)
        // 强制 push
        if (!cmd.contains('--push')) {
            int idx = cmd.indexOf('--load')
            if (idx >= 0) cmd[idx] = '--push'
            else cmd << '--push'
        }
        ctx.script?.echo("==> [docker buildx --push] ${cmd.join(' ')}")
        if (credentialsId && ctx.script?.respondsTo('withCredentials')) {
            // 简化：实际凭据注入交由 Jenkins Credentials Binding 处理
            // 用户在 stage 外层用 withCredentials([...]) 包裹
            ctx.script.echo("[docker] push will use credentials: ${credentialsId}".toString())
        }
        Sandbox.runShell(ctx, cmd, "docker-build-push".toString())
        return cfg.imageRef
    }
}
