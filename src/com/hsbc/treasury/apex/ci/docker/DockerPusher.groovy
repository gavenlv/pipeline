package com.hsbc.treasury.apex.ci.docker

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.utils.Sandbox
import com.hsbc.treasury.apex.ci.errors.ApexCIException

/**
 * Docker 镜像推送。沙箱安全：login、tag、push 都用 list-form 命令。
 */

class DockerPusher implements Serializable {
    private static final long serialVersionUID = 1L

    /** 拉取远端凭据：script.withCredentials([...]) {} */
    static String fetchPassword(PipelineContext ctx, String credentialsId) {
        if (ctx == null || ctx.script == null) {
            throw new ApexCIException("PipelineContext.script required")
        }
        if (credentialsId == null) return null
        if (!ctx.script.respondsTo(ctx.script, 'withCredentials')) return null
        def result = null
        ctx.script.withCredentials([[$class: 'UsernamePasswordMultiBinding',
                                     credentialsId: credentialsId,
                                     usernameVariable: 'DOCKER_USER',
                                     passwordVariable: 'DOCKER_PASS']]) {
            result = ctx.script.DOCKER_PASS?.toString()
        }
        return result
    }

    /** 拼接 docker tag 命令 */
    static List<String> tagCommand(String source, String target) {
        return ['docker', 'tag', source, target]
    }

    /** 拼接 docker push 命令 */
    static List<String> pushCommand(String imageRef) {
        return ['docker', 'push', imageRef]
    }

    /** 拼接 docker login 命令（密码走 stdin） */
    static List<String> loginCommand(String registry) {
        return ['docker', 'login', '--password-stdin', registry ?: '']
    }

    String tag(PipelineContext ctx, String source, String target) {
        Sandbox.runShell(ctx, tagCommand(source, target), "docker-tag".toString())
        return target
    }

    String push(PipelineContext ctx, String imageRef, String credentialsId = null) {
        if (credentialsId) {
            String pwd = fetchPassword(ctx, credentialsId)
            if (pwd != null && ctx.script?.respondsTo(ctx.script, 'sh')) {
                String reg = imageRef.contains('/') ? imageRef.substring(0, imageRef.indexOf('/')) : ''
                ctx.script.sh(script: "printf %s '${pwd.replace("'", "'\\''")}' | docker login --password-stdin ${reg}".toString())
            }
        }
        Sandbox.runShell(ctx, pushCommand(imageRef), "docker-push".toString())
        return imageRef
    }
}
