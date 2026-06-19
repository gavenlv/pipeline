# apex-ci-library 用户手册

> **包名**：`com.hsbc.treasury.apex.ci`
> **版本**：v1.0.0
> **目标读者**：使用本库编写 Jenkinsfile 的业务方研发 / DevOps
> **最后更新**：2026-06-19

---

## 目录

1. [快速上手](#1-快速上手)
2. [核心概念](#2-核心概念)
3. [全局 DSL：`apex{}` 详解](#3-全局-dslapex-详解)
4. [并发执行（Concurrency）](#4-并发执行concurrency)
5. [动态参数（Dynamic Parameters）](#5-动态参数dynamic-parameters)
6. [多语言构建](#6-多语言构建)
7. [容器镜像构建（Docker Build）](#7-容器镜像构建docker-build)
8. [Nexus 制品发布](#8-nexus-制品发布)
9. [安全扫描（异步）](#9-安全扫描异步)
10. [配置管理](#10-配置管理)
11. [通知与回调](#11-通知与回调)
12. [常见模式 Cookbook](#12-常见模式-cookbook)
13. [迁移旧 Jenkinsfile](#13-迁移旧-jenkinsfile)
14. [故障排查](#14-故障排查)

---

## 1. 快速上手

### 1.1 最小化 Jenkinsfile

```groovy
@Library('apex-ci-library@1.0.0') _

apex {
    appName = 'apex-treasury-svc'

    stages {
        stage('Build & Test') {
            java {
                jdk = 21
            }
        }
    }
}
```

> **零配置** 即可运行：自动检测 `pom.xml` → 选 `JavaBuilder` → 默认执行 `clean verify`。

### 1.2 引入固定版本

```groovy
@Library('apex-ci-library@1.0.0') _    // 推荐：固定版本
@Library('apex-ci-library@main') _     // 内部：跟随主干
@Library('apex-ci-library') _          // 兜底：跟随默认分支
```

### 1.3 检查库版本

```groovy
echo "apex-ci-library version: ${apexVersion()}"
```

---

## 2. 核心概念

| 概念 | 含义 | 数量上限 |
| --- | --- | --- |
| **Pipeline** | 一条完整的 CI 流水线 | 1 |
| **Stage** | 阶段，串行执行 | N |
| **Parallel** | 阶段内的并发分支 | N |
| **Step** | 阶段内的具体动作（build/scan/notify） | N |
| **Context** | 跨阶段共享的不可变变量容器 | 1 |

```
Pipeline
├── stage: Checkout          (sequential)
├── stage: Build & Test      (parallel: java / node / python)
├── stage: Security Scans    (parallel: sast / sca / container)  ← 异步启动
├── stage: Collect Scans     (await all async results)           ← 统一门禁
└── stage: Publish           (when: branch == 'main')
```

---

## 3. 全局 DSL：`apex{}` 详解

```groovy
apex {
    // === 必填 ===
    appName = 'apex-treasury-svc'

    // === 业务变量（任意 key/value，跨阶段共享）===
    vars = [
        team        : 'treasury',
        costCenter  : 'CC1234',
        dataClass   : 'CONFIDENTIAL'
    ]

    // === Agent ===
    agent { label 'docker && linux' }

    // === 环境变量 ===
    environment {
        DOCKER_BUILDKIT = '1'
        HTTP_PROXY      = 'http://proxy.hsbc:8080'
    }

    // === Pipeline options ===
    options {
        timeout(time: 30, unit: 'MINUTES')
        ansiColor('xterm')
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '30'))
    }

    // === 生命周期钩子 ===
    onInit   { ctx -> echo "Init: ${ctx.appName}" }
    onSetup  { ctx -> /* 准备步骤 */ }
    onSuccess{ ctx -> /* 成功回调 */ }
    onFailure{ ctx -> /* 失败回调 */ }
    onFinally{ ctx -> /* 总是执行 */ }

    // === 阶段 ===
    stages {
        stage('X') { ... }
    }
}
```

### 3.1 业务变量读取

```groovy
apex {
    vars = [team: 'treasury']

    stages {
        stage('Build') {
            java {
                goals = ["-Dproject.team=${apexContext.vars.team}"]
            }
        }
    }
}
```

---

## 4. 并发执行（Concurrency）

apex-ci-library 让你**最少量代码**地表达"这几件事同时做"。

### 4.1 `parallel {}` 块：阶段内并发

```groovy
stage('Build & Test') {
    parallel {
        java   { jdk = 21 }
        node   { nodeVersion = '20.x'; commands = ['install', 'test'] }
        python { pythonVersion = '3.12'; commands = ['pytest'] }
    }
}
```

> 三个分支**同时启动**，日志分页展示，资源允许时由 Jenkins 调度到不同 agent。

### 4.2 嵌套并发

```groovy
stage('CI') {
    parallel {
        // 第一组：编译
        build {
            java { jdk = 21 }
            node { nodeVersion = '20.x' }
        }
        // 第二组：扫描
        scan {
            sast     { tool = 'sonarqube' }
            sca      { tool = 'owasp' }
            container{ tool = 'trivy' }
        }
    }
}
```

### 4.3 并发粒度控制

```groovy
stage('Matrix') {
    matrix {
        axes {
            axis('JDK',  ['17', '21'])
            axis('OS',   ['linux', 'windows'])
        }
        excludes { /* 不合法组合 */ }
        stages { java {} }
    }
}
```

### 4.4 异步任务 + 阶段并发（推荐模式）

> 适用于"启动扫描后立即继续做其他事"。

```groovy
stage('Kickoff Scans') {
    steps {
        script {
            // 启动扫描，立即返回 AsyncResult（不等结果）
            apex.startScan {
                sast      { tool = 'sonarqube' }
                sca       { tool = 'owasp' }
                container { tool = 'trivy' }
            }
        }
    }
}

stage('Build & Test') {
    // 这一阶段可与扫描并行进行
    steps {
        java { jdk = 21 }
    }
}

stage('Collect & Gate') {
    // 独立阶段统一收口，便于门禁
    steps {
        script {
            def results = apex.collectScans(timeoutMinutes: 30)
            apex.gate(results, policy: 'high+')
        }
    }
}
```

### 4.5 并发失败策略

```groovy
parallel {
    failFast = true    // 任一失败立即取消其他分支
    java     { ... }
    node     { ... }
}

// 或对单条命令
java {
    goals = ['verify']
    onError = 'CONTINUE'         // 失败不中断
    retry   = { maxAttempts = 2 } // 自动重试
}
```

### 4.6 资源节流

```groovy
parallel {
    // 限制本并发组最多 2 个并发分支
    maxConcurrency = 2

    java   { ... }
    node   { ... }
    python { ... }
}
```

---

## 5. 动态参数（Dynamic Parameters）

**业务方能完全掌控构建参数**：任意加减 `-D`、`-P`、`-pl`、任意 CLI flag。

### 5.1 核心原则

- **所有参数都是集合**（`List<String>` / `Map<String,String>`），可以 `.add` / `.remove`。
- **DSL closure 内可任意修改**，不会破坏框架默认。
- **最终框架统一组装为 `sh(script: [...])` 数组**，沙箱安全。

### 5.2 Maven 动态参数

```groovy
java {
    jdk        = 21
    buildTool  = 'maven'

    // ====== 基础 goal（可整体覆盖） ======
    goals      = ['clean', 'package', '-DskipITs']

    // ====== 任意 -D 属性（Map，加多少都行） ======
    properties = [
        'maven.javadoc.skip'         : 'true',
        'checkstyle.skip'            : 'false',
        'sonar.exclusions'           : '**/generated/**',
        'project.build.sourceEncoding': 'UTF-8',
        'git.commit.id.abbrev'       : '7',
        'apex.build.timestamp'       : "${new Date().format('yyyyMMddHHmmss')}"
    ]

    // ====== Profile ======
    profiles   = ['release']

    // ====== 任意 CLI（数组式，自由加减） ======
    cliOptions = [
        '-pl',         'core-svc,api-svc',   // 只构建这些模块
        '-am',                               // 同时构建依赖
        '--batch-mode',
        '--update-snapshots',
        '-fae',                              // fail at end
        '-V',                                // version info
        '--debug'
    ]

    // ====== 条件性动态加减 ======
    if (env.BRANCH_NAME == 'main') {
        profiles   += ['release']
        cliOptions += ['-Dgpg.skip=false']
    } else {
        profiles   += ['snapshot']
        cliOptions += ['-Dgpg.skip=true']
    }

    // ====== 临时追加/移除 ======
    cliOptions.removeAll { it == '--debug' }    // 调试完删掉
    properties.remove('maven.javadoc.skip')    // 单独移除某项

    // ====== 并行构建 ======
    parallelBuild = true
    threadCount   = '4'

    // ====== 跳过测试 ======
    skipTests = env.SKIP_TESTS == 'true'
}
```

### 5.3 npm 动态参数

```groovy
node {
    packageManager = 'npm'
    nodeVersion    = '20.x'

    commands = [
        'install --frozen-lockfile',
        'run lint',
        'run test -- --coverage',
        'run build -- --mode=production'
    ]

    // 任意 npm config
    npmConfig = [
        'registry'      : 'https://nexus.hsbc/repository/npm-group/',
        'fetch-retries' : '5',
        'fetch-retry-mintimeout': '20000'
    ]

    // 任意 --flag（会拼到所有 npm 命令后）
    flags = ['--silent', '--no-audit']

    // 临时追加
    if (env.BRANCH_NAME == 'main') {
        commands << 'run e2e'
    }
}
```

### 5.4 Python 动态参数

```groovy
python {
    pythonVersion   = '3.12'
    packageManager  = 'poetry'

    commands = [
        'install --no-interaction',
        'pytest -q --maxfail=1',
        'ruff check .',
        'mypy src'
    ]

    // poetry 源
    repositories = [
        nexus: 'https://nexus.hsbc/repository/pypi-releases/'
    ]

    // 任意 extras
    extras = ['-E performance', '--with test']
}
```

### 5.5 Go 动态参数

```groovy
go {
    goVersion = '1.23'

    commands = [
        'mod download',
        'test ./... -race -coverprofile=coverage.out',
        'build -o bin/server ./cmd/server',
        'vet ./...'
    ]

    // 任意 ldflags
    ldflags = ['-s', '-w', '-X main.version=${ctx.commitSha}']
}
```

### 5.6 通用 `params {}` 块

任何 Builder 都支持无类型动态参数：

```groovy
java {
    params {
        flag('--batch-mode')
        flag('--update-snapshots')

        property('maven.test.failure.ignore', 'true')
        property('failIfNoTests', 'false')

        positional('clean')
        positional('verify')

        extra('region', 'hk')       // 业务自定义
    }
}
```

> `params` 块内容由对应 Builder 决定如何转译为实际命令行参数。

### 5.7 运行时参数化（Build with Parameters）

业务方也可在 Jenkins Job UI 上传参：

```groovy
properties([
    parameters([
        string(name: 'GOALS',        defaultValue: 'clean verify', description: 'Maven goals'),
        string(name: 'M2_PROFILES',   defaultValue: '',            description: 'Comma-separated profiles'),
        booleanParam(name: 'SKIP_TESTS', defaultValue: false,     description: 'Skip tests'),
        choice(name: 'JDK',          choices: ['17', '21'],       description: 'JDK version')
    ])
])

apex {
    stages {
        stage('Build') {
            java {
                jdk        = params.JDK
                goals      = params.GOALS.tokenize(' ')
                profiles   = params.M2_PROFILES.tokenize(',').findAll { it }
                skipTests  = params.SKIP_TESTS
            }
        }
    }
}
```

---

## 6. 多语言构建

### 6.1 自动检测

```groovy
stage('Auto Build') {
    steps {
        apex.detectLanguage()      // → 'java' | 'node' | 'python' | 'go' | 'dotnet' | 'shell'
        apex.build(lang) { ... }   // 选对应 Builder
    }
}
```

### 6.2 Java 完整示例

```groovy
java {
    jdk         = 21
    buildTool   = 'maven'             // maven | gradle
    mavenVersion = '3.9.9'

    goals      = ['clean', 'verify']
    profiles   = []
    properties = [:]
    cliOptions = []
    modules    = []                    // -pl xxx -am
    skipTests  = false
    parallelBuild = true
    threadCount   = '1C'

    // 失败处理
    retry    = { maxAttempts = 2; backoff = 'EXPONENTIAL' }
    onError  = 'FAIL'                  // FAIL | CONTINUE | IGNORE
}
```

### 6.3 Node 完整示例

```groovy
node {
    nodeVersion    = '20.x'
    packageManager = 'pnpm'           // npm | yarn | pnpm
    pnpmVersion    = '9.12.0'

    commands = [
        'install --frozen-lockfile',
        'test --coverage',
        'build'
    ]

    cache = '.pnpm-store'              // 缓存路径
}
```

### 6.4 Python 完整示例

```groovy
python {
    pythonVersion  = '3.12'
    packageManager = 'poetry'         // poetry | pip | uv

    commands = [
        'install --no-interaction',
        'pytest --maxfail=1',
        'ruff check .'
    ]

    venv = '.venv'                    // 虚拟环境目录
}
```

### 6.5 Go 完整示例

```groovy
go {
    goVersion = '1.23'

    commands = [
        'mod download',
        'test ./... -race',
        'build -o bin/server ./cmd/server'
    ]

    coverage {
        enabled = true
        threshold = 80                // 低于 80% 失败
    }
}
```

### 6.6 .NET 完整示例

```groovy
dotnet {
    sdkVersion = '8.0'
    solution   = 'MyApp.sln'

    commands = [
        'restore',
        'build --configuration Release',
        'test --no-build --logger "trx;LogFileName=test.trx"',
        'publish --output publish/'
    ]
}
```

---

## 7. 容器镜像构建（Docker Build）

### 7.1 最小示例

```groovy
stage('Containerize') {
    steps {
        containerBuild {
            tags = [ctx.commitSha, 'latest']
        }
    }
}
```

> 自动寻找 `Dockerfile`，构建出 `${appName}:${commitSha}`。

### 7.2 完整示例

```groovy
stage('Containerize') {
    steps {
        containerBuild {
            // 基本
            dockerfile = 'docker/Dockerfile'
            context    = '.'

            // 多架构（并发）
            platforms  = ['linux/amd64', 'linux/arm64']

            // --build-arg（任意加减）
            buildArgs = [
                'NODE_VERSION=20.18.0',
                'JAR_FILE=target/*.jar',
                'PROXY=http://proxy.hsbc:8080'
            ]

            // BuildKit Secret（不落镜像层）
            secrets = [
                'id=npmrc,src=.npmrc',
                'id=settings,src=settings.xml'
            ]

            // 镜像缓存
            cacheFrom = [
                "type=registry,ref=nexus.hsbc/apex/${ctx.appName}:buildcache"
            ]
            cacheTo = [
                "type=registry,ref=nexus.hsbc/apex/${ctx.appName}:buildcache,mode=max"
            ]

            // 标签
            tags = [
                ctx.commitSha,                 // sha
                ctx.semver ?: 'latest',       // semver
                env.BRANCH_NAME.replaceAll('/', '-')
            ]

            // 任意额外参数
            params {
                flag('--progress=plain')
                flag('--ssh=default')
                property('BUILDKIT_INLINE_CACHE', '1')
            }

            // 资源
            timeoutMinutes = 30
            noCache        = false

            // 钩子
            onSuccess { ctx -> echo "Built: ${ctx.imageRef}" }
        }
    }
}
```

### 7.3 推送到 Nexus

```groovy
stage('Push Image') {
    steps {
        containerPush {
            registry      = 'nexus.hsbc'
            repository    = "apex/${ctx.appName}"
            tags          = [ctx.commitSha, ctx.semver ?: 'latest']
            credentialsId = 'nexus-deployer'
        }
    }
}
```

### 7.4 多架构一次性 build+push

```groovy
containerBuild {
    platforms = ['linux/amd64', 'linux/arm64']
    push      = true                   // 推送与构建合并
    registry  = 'nexus.hsbc'
    repository = "apex/${ctx.appName}"
    tags       = [ctx.commitSha]
}
```

### 7.5 上下文变量镜像引用

构建后可用：

```groovy
apex {
    stages {
        stage('Build')  { containerBuild { tags = [ctx.commitSha] } }
        stage('Push')   { containerPush  { tags = [ctx.commitSha] } }
        stage('Deploy') {
            steps {
                sh "kubectl set image deployment/${ctx.appName} " +
                   "${ctx.appName}=${apexContext.imageRef}"
            }
        }
    }
}
```

> `apexContext.imageRef` 由 `containerBuild` 自动注入。

---

## 8. Nexus 制品发布

### 8.1 Maven 制品

#### 方式 A：mvn deploy（最简单）

```groovy
stage('Publish') {
    when { branch 'main' }
    steps {
        java {
            jdk = 21
            goals = ['clean', 'deploy']
            properties = [
                'altDeploymentRepository': 'nexus::default::https://nexus.hsbc/repository/maven-releases/',
                'gpg.skip'                : 'false'
            ]
            cliOptions = ['--settings', 'ci/settings-nexus.xml']
        }
    }
}
```

#### 方式 B：API 显式上传（推荐，可观测）

```groovy
stage('Publish') {
    when { branch 'main' }
    steps {
        script {
            apexNexus {
                baseUrl       = 'https://nexus.hsbc'
                repository    = 'maven-releases'
                credentialsId = 'nexus-deployer'
                format        = 'maven2'
            }.publishMaven(
                groupId:    'com.hsbc.treasury.apex',
                artifactId: ctx.appName,
                version:    ctx.semver,
                files:      ['target/*.jar', 'target/*.pom', 'target/*.war']
            )
        }
    }
}
```

### 8.2 npm 制品

```groovy
stage('Publish npm') {
    when { branch 'main' }
    steps {
        node {
            commands = [
                'ci',
                'build',
                'npm publish --registry=https://nexus.hsbc/repository/npm-hosted/'
            ]
        }
    }
}
```

或：

```groovy
apexNexus {
    baseUrl    = 'https://nexus.hsbc'
    repository = 'npm-hosted'
    format     = 'npm'
}.publishNpm(
    packageJson: 'package.json',
    tarball:    "dist/${ctx.appName}-${ctx.version}.tgz"
)
```

### 8.3 Python 制品（PyPI 代理）

```groovy
stage('Publish PyPI') {
    steps {
        python {
            commands = [
                'poetry config repositories.nexus https://nexus.hsbc/repository/pypi-releases/',
                'poetry publish -r nexus --username $NEXUS_USER --password $NEXUS_PASS'
            ]
        }
    }
}
```

### 8.4 Docker 镜像推送到 Nexus（详见第 7 节）

```groovy
containerPush {
    registry   = 'nexus.hsbc'
    repository = "apex/${ctx.appName}"
    tags       = [ctx.commitSha, ctx.semver ?: 'latest']
}
```

### 8.5 推送门禁（安全）

```groovy
nexusPush {
    gate {
        requireGreenBuild    = true
        requireSignedCommits = true
        requireScanPass      = true
        allowedBranches      = ['main', 'release/*']
    }
}
```

> 任一条件不满足 → 拒绝推送并 Slack 报警。

### 8.6 制品元数据

```groovy
script {
    def meta = apexNexus.lastArtifact()
    echo "URL:      ${meta.url}"
    echo "SHA-256:  ${meta.checksums.sha256}"
    echo "GPG sig:  ${meta.signatures.join(', ')}"
}
```

---

## 9. 安全扫描（异步）

### 9.1 启动一组扫描（不阻塞）

```groovy
stage('Kickoff Scans') {
    steps {
        script {
            apex.startScan {
                sast      { tool = 'sonarqube'; qualityGate = true }
                sca       { tool = 'owasp';      failOn = 'HIGH' }
                secrets   { tool = 'gitleaks' }
                container { tool = 'trivy';      failOn = 'CRITICAL' }
                iac       { tool = 'checkov';    enabled = fileExists('**/*.tf') }
            }
        }
    }
}
```

### 9.2 收集结果 + 门禁

```groovy
stage('Collect & Gate') {
    steps {
        script {
            def results = apex.collectScans(timeoutMinutes: 30)
            apex.gate(results, policy: [
                failOnCritical: true,
                failOnHigh:     true,
                allowList:      ['known-issue-001']
            ])
        }
    }
}
```

### 9.3 同步（阻塞）扫描

```groovy
stage('Security') {
    steps {
        apex.scan {
            sast { tool = 'sonarqube' }   // 框架自动 await
        }
    }
}
```

### 9.4 上报到 Jenkins

扫描结果自动生成：

- `target/scans/sast.html`（HTML 报告）
- `target/scans/sast.junit.xml`（JUnit）
- `target/scans/summary.html`（汇总）

---

## 10. 配置管理

### 10.1 全局配置（`apexConfig{}`）

```groovy
apexConfig {
    registry         = 'registry.hsbc/apex'
    defaultAgent     = 'docker && linux'
    defaultTimeout   = '30m'

    retry {
        maxAttempts = 3
        backoff     = 'EXPONENTIAL'
    }

    credentials {
        docker   = 'apex-docker-creds'
        nexus    = 'nexus-deployer'
        sonar    = 'apex-sonar-token'
        slack    = 'apex-slack-webhook'
    }

    slack {
        channel = '#apex-ci'
    }

    notify {
        onSuccess = false        // 默认成功不通知
        onFailure = true
    }
}
```

### 10.2 仓库级配置 `apex-ci.yaml`

把上面同样的配置写到仓库根目录的 `apex-ci.yaml`，可在不修改 Jenkinsfile 的情况下调整。

```yaml
# apex-ci.yaml
registry:       registry.hsbc/apex
defaultAgent:   docker && linux
defaultTimeout: 30m

retry:
  maxAttempts: 3
  backoff:     EXPONENTIAL

credentials:
  docker: apex-docker-creds
  nexus:  nexus-deployer

slack:
  channel: '#apex-ci'

language: java           # 可选覆盖自动检测
```

### 10.3 配置优先级

```
业务方 Jenkinsfile `apex { ... }`  >  apex-ci.yaml  >  apexConfig{}  >  全局默认
```

---

## 11. 通知与回调

### 11.1 Slack

```groovy
apex {
    onSuccess { ctx ->
        slackSend(
            channel: '#apex-ci',
            color:   'good',
            message: "✅ ${ctx.appName} ${ctx.commitSha} - ${ctx.duration}"
        )
    }
    onFailure { ctx ->
        slackSend(
            channel: '#apex-ci',
            color:   'danger',
            message: "❌ ${ctx.appName} ${ctx.commitSha} failed at ${ctx.failedStage}"
        )
    }
}
```

### 11.2 邮件

```groovy
apex {
    onFailure { ctx ->
        emailext(
            subject: "[APEX-CI] ${ctx.appName} failed",
            body:    "Build URL: ${ctx.buildUrl}",
            to:      "${apexContext.vars.team}@hsbc.com"
        )
    }
}
```

### 11.3 Teams

```groovy
apex {
    notifiers {
        teams {
            webhookUrl = 'https://outlook.office.com/webhook/...'
            onFailure  = true
        }
    }
}
```

---

## 12. 常见模式 Cookbook

### 12.1 Monorepo：按模块并行构建

```groovy
apex {
    vars = [modules: ['svc-a', 'svc-b', 'svc-c']]

    stages {
        stage('Per-Module Build') {
            matrix {
                axes { axis('MODULE', ctx.vars.modules) }
                stages {
                    stage('Build') {
                        java {
                            goals      = ['clean', 'verify']
                            cliOptions = ['-pl', ctx.MATRIX.MODULE, '-am']
                        }
                    }
                }
            }
        }
    }
}
```

### 12.2 多语言混编

```groovy
stage('Polyglot Build') {
    parallel {
        java   { goals = ['clean', 'package'] }
        node   { commands = ['install', 'test', 'build'] }
        python { commands = ['pytest', 'build'] }
        go     { commands = ['test', 'build'] }
    }
}
```

### 12.3 Build & Push & Deploy 流水线

```groovy
apex {
    stages {
        stage('Build')     { java { jdk = 21 } }
        stage('Containerize') {
            containerBuild {
                platforms = ['linux/amd64', 'linux/arm64']
                push      = true
                tags      = [ctx.commitSha, 'latest']
            }
        }
        stage('Publish') {
            when { branch 'main' }
            steps {
                apexNexus {
                    baseUrl = 'https://nexus.hsbc'
                    repository = 'maven-releases'
                }.publishMaven(
                    groupId: 'com.hsbc.treasury.apex',
                    artifactId: ctx.appName,
                    version: ctx.commitSha,
                    files: ['target/*.jar']
                )
            }
        }
        stage('Deploy') {
            when { branch 'main' }
            steps {
                sh "helm upgrade --install ${ctx.appName} ./chart " +
                   "--set image.tag=${ctx.commitSha}"
            }
        }
    }
}
```

### 12.4 PR 门禁流水线

```groovy
apex {
    when { changeRequest() }      // 仅 PR

    stages {
        stage('Build')  { java { jdk = 21 } }
        stage('Lint')   { node { commands = ['run lint'] } }
        stage('Tests')  { java { goals = ['test'] } }
        stage('Scans')  { apex.scan { sast {} sca {} } }   // 同步门禁
    }
}
```

### 12.5 灰度发布（多 region）

```groovy
stage('Deploy') {
    matrix {
        axes { axis('REGION', ['hk', 'sg', 'london']) }
        stages {
            stage('Deploy') {
                when { branch 'main' }
                steps {
                    sh "helm upgrade --install ${ctx.appName} ./chart " +
                       "--region ${ctx.MATRIX.REGION} " +
                       "--set image.tag=${ctx.commitSha}"
                }
            }
        }
    }
}
```

---

## 13. 迁移旧 Jenkinsfile

### 13.1 迁移前

```groovy
pipeline {
    agent { label 'docker' }
    stages {
        stage('Build') {
            steps {
                sh 'mvn -B -pl :core -am clean verify -P release'
            }
        }
    }
}
```

### 13.2 迁移后

```groovy
@Library('apex-ci-library@1.0.0') _

apex {
    appName = 'core'
    agent { label 'docker' }
    stages {
        stage('Build') {
            java {
                jdk = 21
                goals = ['clean', 'verify']
                profiles = ['release']
                cliOptions = ['-B', '-pl', ':core', '-am']
            }
        }
    }
}
```

### 13.3 收益

- 自动并发 / 重试 / 超时
- 统一日志聚合
- 跨仓库一致的安全门禁
- 后续添加扫描 / Docker / Nexus 一行接入

---

## 14. 故障排查

### 14.1 库版本不匹配

```
ERROR: apex-ci-library v2.x is required (current: 1.5.0)
```

→ 升级 `@Library('apex-ci-library@2.0.0') _` 或降低业务方配置。

### 14.2 Sandbox 拒绝执行

```
org.jenkinsci.plugins.scriptsecurity.sandbox.RejectedAccessException:
unclassified method java.lang.String replaceAll
```

→ 不要在闭包内做复杂字符串处理；用框架提供的方法或 `apex.unsafe { ... }` 包裹（需白名单）。

### 14.3 异步扫描永远等待

```groovy
// 错误：没有 timeout
def r = apex.collectScans()       // 永远等
def r = apex.collectScans(timeoutMinutes: 30)   // OK
```

### 14.4 Maven 找不到模块

```
[ERROR] Could not find artifact ...
```

```groovy
// 把 -am 加上（自动构建依赖）
cliOptions = ['-pl', 'svc-a', '-am']
```

### 14.5 Docker buildx 不存在

```
ERROR: docker buildx not found
```

→ 升级 Docker ≥ 19.03 并在 agent 上安装 buildx 插件。

### 14.6 Nexus 401 Unauthorized

- 检查 `credentialsId` 是否正确
- 检查 `nexus-deployer` 凭据是否过期
- 检查仓库是否要求 deployment 角色

### 14.7 日志位置

| 类别 | 路径 |
| --- | --- |
| 构建日志 | Jenkins Job UI → Console Output |
| 扫描报告 | `${WORKSPACE}/target/scans/` |
| 制品元数据 | `${WORKSPACE}/target/artifacts/` |
| 库调试 | `apex -Dapex.debug=true` |

---

## 附录 A：完整 Jenkinsfile 示例

```groovy
@Library('apex-ci-library@1.0.0') _

apex {
    appName = 'apex-treasury-svc'

    vars = [
        team       : 'treasury',
        costCenter : 'CC1234',
        dataClass  : 'CONFIDENTIAL'
    ]

    agent { label 'docker && linux && mvn-3.9' }

    options {
        timeout(time: 45, unit: 'MINUTES')
        ansiColor('xterm')
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '30'))
    }

    environment {
        DOCKER_BUILDKIT = '1'
        HTTP_PROXY      = 'http://proxy.hsbc:8080'
    }

    onInit { ctx -> echo "Pipeline started for ${ctx.appName}" }

    stages {

        stage('Checkout') {
            steps { checkout scm }
        }

        stage('Build & Test') {
            parallel {
                java {
                    jdk        = 21
                    buildTool  = 'maven'
                    goals      = ['clean', 'verify']
                    properties = [
                        'maven.javadoc.skip'  : 'true',
                        'checkstyle.skip'     : 'false',
                        'project.build.sourceEncoding': 'UTF-8'
                    ]
                    cliOptions = ['-B', '-fae']
                    if (env.BRANCH_NAME == 'main') {
                        profiles = ['release']
                    }
                }
                node {
                    nodeVersion    = '20.x'
                    packageManager = 'pnpm'
                    commands       = [
                        'install --frozen-lockfile',
                        'test --coverage',
                        'build'
                    ]
                }
                python {
                    pythonVersion  = '3.12'
                    packageManager = 'poetry'
                    commands       = [
                        'install --no-interaction',
                        'pytest --maxfail=1'
                    ]
                }
            }
        }

        stage('Security Scans (async)') {
            steps {
                script {
                    apex.startScan {
                        sast      { tool = 'sonarqube'; qualityGate = true }
                        sca       { tool = 'owasp';      failOn = 'HIGH' }
                        secrets   { tool = 'gitleaks' }
                        container { tool = 'trivy';      failOn = 'CRITICAL' }
                    }
                }
            }
        }

        stage('Containerize') {
            steps {
                containerBuild {
                    platforms = ['linux/amd64', 'linux/arm64']
                    buildArgs = ['NODE_VERSION=20.18.0']
                    secrets   = ['id=npmrc,src=.npmrc']
                    cacheFrom = ["type=registry,ref=nexus.hsbc/apex/${ctx.appName}:buildcache"]
                    tags      = [ctx.commitSha, ctx.semver ?: 'latest']
                }
            }
        }

        stage('Collect Scans & Gate') {
            steps {
                script {
                    def results = apex.collectScans(timeoutMinutes: 30)
                    apex.gate(results, policy: 'high+')
                }
            }
        }

        stage('Publish') {
            when { branch 'main' }
            parallel {
                nexusMaven {
                    repository    = 'maven-releases'
                    groupId       = 'com.hsbc.treasury.apex'
                    artifactId    = ctx.appName
                    version       = ctx.commitSha
                    files         = ['target/*.jar', 'target/*.pom']
                }
                containerPush {
                    registry   = 'nexus.hsbc'
                    repository = "apex/${ctx.appName}"
                    tags       = [ctx.commitSha, 'latest']
                }
            }
        }
    }

    onSuccess { ctx ->
        slackSend channel: '#apex-ci',
                  color:   'good',
                  message: "✅ ${ctx.appName} ${ctx.commitSha} - ${ctx.duration}"
    }
    onFailure { ctx ->
        slackSend channel: '#apex-ci',
                  color:   'danger',
                  message: "❌ ${ctx.appName} ${ctx.commitSha} failed at ${ctx.failedStage}"
    }
}
```

---

## 附录 B：API 一览

| 全局变量 | 用途 |
| --- | --- |
| `apex{}` | 主 DSL 入口 |
| `apexBuild(lang)` | 一步构建 |
| `apexScan{}` | 同步扫描 |
| `apex.startScan{}` | 异步启动扫描 |
| `apex.collectScans(...)` | 收集并 await |
| `apex.gate(results, policy)` | 应用门禁 |
| `apexNexus{}` | Nexus 客户端 |
| `apexConfig{}` | 全局配置 |
| `apexContext.xxx` | 读取上下文 |
| `apexVersion()` | 库版本 |

| 阶段内子 DSL | 用途 |
| --- | --- |
| `java{}` | Java 构建 |
| `node{}` | Node 构建 |
| `python{}` | Python 构建 |
| `go{}` | Go 构建 |
| `dotnet{}` | .NET 构建 |
| `containerBuild{}` | Docker 构建 |
| `containerPush{}` | 镜像推送 |
| `sast{}` / `sca{}` / `container{}` / `secrets{}` / `iac{}` | 安全扫描 |
| `parallel{}` / `matrix{}` | 并发 / 矩阵 |
| `when{}` | 条件执行 |
