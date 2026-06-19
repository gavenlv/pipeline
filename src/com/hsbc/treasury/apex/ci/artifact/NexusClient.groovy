package com.hsbc.treasury.apex.ci.artifact

import com.hsbc.treasury.apex.ci.errors.ApexCIException

/**
 * Nexus 仓库客户端（构造命令）。
 * 真实 HTTP I/O 由 script.sh 调用 curl 执行，库只负责拼装安全命令。
 *
 * 常见格式：
 *   - maven2   : /repository/maven-releases/com/acme/...
 *   - npm      : /repository/npm-hosted/acme/-/acme-1.0.0.tgz
 *   - pypi     : /repository/pypi-releases/packages/...
 *   - raw      : /repository/raw-hosted/path/to/file
 *   - docker   : /v2/... （通过 docker push 处理）
 */

class NexusClient implements Serializable {
    private static final long serialVersionUID = 1L

    String baseUrl
    String repository
    String credentialsId
    String format = 'maven2'           // maven2 | npm | pypi | raw | docker

    static NexusClient of(String baseUrl, String repository, String format, String credentialsId = null) {
        if (baseUrl == null) throw new ApexCIException("baseUrl required")
        if (repository == null) throw new ApexCIException("repository required")
        return new NexusClient(baseUrl: baseUrl, repository: repository, format: format, credentialsId: credentialsId)
    }

    /** 拼接 curl GET 命令：用于探测或下载 */
    List<String> buildGet(String path) {
        if (path == null) throw new ApexCIException("path required")
        return ['curl', '--silent', '--show-error', '--fail',
                '--user', credentialsId ? '"\$NEXUS_USER:\$NEXUS_PASS"' : '',
                "${baseUrl}/repository/${repository}/${path}".toString()]
    }

    /** 拼接 curl PUT 命令：上传二进制（path 与 file 都是安全的） */
    List<String> buildPut(String path, String file, String contentType = 'application/octet-stream') {
        if (path == null || file == null) throw new ApexCIException("path and file required")
        return ['curl', '--silent', '--show-error', '--fail',
                '--user', credentialsId ? '"\$NEXUS_USER:\$NEXUS_PASS"' : '',
                '--upload-file', file,
                '--header', "Content-Type: ${contentType}".toString(),
                "${baseUrl}/repository/${repository}/${path}".toString()]
    }

    /** 拼接 npm publish 命令（需用 .npmrc） */
    List<String> buildNpmPublish() {
        return ['npm', 'publish', '--registry', "${baseUrl}/repository/${repository}/".toString()]
    }

    /** 拼接 twine upload 命令（Python） */
    List<String> buildTwineUpload(String distDir) {
        return ['twine', 'upload',
                '--repository-url', "${baseUrl}/repository/${repository}/".toString(),
                "${distDir}/*".toString()]
    }

    /** 拼接 mvn deploy 命令片段（供 mvn 调用使用） */
    String mavenDistributionUrl() {
        return "${baseUrl}/repository/${repository}/".toString()
    }
}
