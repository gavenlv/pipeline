package com.hsbc.treasury.apex.ci.docker

import com.hsbc.treasury.apex.ci.core.DynamicParams

/**
 * Docker 构建配置。
 *
 *   containerBuild {
 *       dockerfile  = 'Dockerfile'
 *       context     = '.'
 *       tags        = [commitSha, 'latest']
 *       buildArgs   = ['NODE_VERSION=20']
 *       secrets     = ['id=npmrc,src=.npmrc']
 *       platforms   = ['linux/amd64', 'linux/arm64']
 *       cacheFrom   = ['type=registry,ref=ghcr.io/org/myapp:cache']
 *       networkMode = 'default'
 *       noCache     = false
 *       timeoutMinutes = 30
 *       registry    = 'ghcr.io/org'
 *       pushOnBuild = false
 *   }
 */

class DockerBuildConfig implements Serializable {
    private static final long serialVersionUID = 1L

    String dockerfile = 'Dockerfile'
    String context = '.'
    List<String> tags = ['latest']
    List<String> buildArgs = []
    List<String> secrets = []
    List<String> platforms = ['linux/amd64']
    List<String> cacheFrom = []
    String networkMode = 'default'
    boolean noCache = false
    int timeoutMinutes = 30
    String registry
    boolean pushOnBuild = false
    String credentialsId
    DynamicParams params

    static DockerBuildConfig fromClosure(Closure body) {
        def cfg = new DockerBuildConfig()
        if (body == null) return cfg
        body.delegate = cfg
        body.resolveStrategy = Closure.DELEGATE_FIRST
        body()
        if (cfg.tags == null) cfg.tags = ['latest']
        if (cfg.platforms == null) cfg.platforms = ['linux/amd64']
        return cfg
    }
}
