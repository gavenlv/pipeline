# apex-ci-library 用户手册（轻量版）

> **包名**：`com.hsbc.treasury.apex.ci`
> **版本**：v2.0.0（Lightweight）
> **目标读者**：使用本库编写 Jenkinsfile 的业务方研发 / DevOps
> **最后更新**：2026-06-21

---

## 目录

1. [设计理念](#1-设计理念)
2. [快速上手](#2-快速上手)
3. [入口与共享上下文 `apex{}`](#3-入口与共享上下文-apex)
4. [构建 `apexBuild(lang)`](#4-构建-apexbuildlang)
5. [动态参数 `apexParams()`](#5-动态参数-apexparams)
6. [并发扫描 `apexScan{}`](#6-并发扫描-apexscan)
7. [重试 `apexRetry.xxx()`](#7-重试-apexretryxxx)
8. [Docker 镜像 `apexDocker(...)`](#8-docker-镜像-apexdocker)
9. [Nexus 发布 `apexPublish(...)`](#9-nexus-发布-apexpublish)
10. [通知 `apexNotify(...)`](#10-通知-apexnotify)
11. [配置 `apexConfig.xxx()`](#11-配置-apexconfigxxx)
12. [自动版本管理 `apexVersion` / `VersionManager`](#12-自动版本管理-apexversion--versionmanager)
13. [典型模式 Cookbook](#13-典型模式-cookbook)
14. [从旧 DSL 迁移](#14-从旧-dsl-迁移)
15. [故障排查](#15-故障排查)
16. [API 一览](#16-api-一览)
17. [6 个端到端 Jenkinsfile 入门](#17-6-个端到端-jenkinsfile-入门)

---

## 1. 设计理念

apex-ci-library 2.0 走 **"原生优先"** 路线：

- **业务流（什么时候并发、什么时候门禁）由 Jenkins 原生 stage / parallel / sh 决定**
  —— 这些语义在 CPS 沙箱里最稳定、用户最熟、IDE 跳转最清晰。
- **库只封装"与外部交换"的部分**：构建参数、并行扫描门禁、Docker 构建、制品发布、重试、上下文注入。
- **不再提供自定义 `Pipeline{}` / `Stage{}` 抽象**，避免与 CPS 转换器冲突。

最小决策树：

```
我需要做什么？
├── 并行/串行/条件 →  用 Jenkins 原生 stage / parallel / when
├── 调外部命令       →  用 apexBuild / apexDocker / apexPublish / sh
├── 多次重试         →  用 apexRetry.linear/exponential/until
├── 启动并行扫描     →  用 apexScan{} (内部走 Jenkins 原生 parallel)
└── 共享变量         →  用 apex{} 注入的 ctx.attrs
```

---

## 2. 快速上手

### 2.1 最小化 Jenkinsfile

```groovy
@Library('apex-ci-library@2.0') _

node {
    stage('Build') {
        apexBuild('java') { jdk = 17 }
    }
}
```

> **零配置** 即可运行：自动检测 `pom.xml` → 选 `JavaBuilder` → 默认执行 `clean verify`。

### 2.2 引入固定版本

```groovy
@Library('apex-ci-library@2.0') _   // 推荐：固定版本
@Library('apex-ci-library@main') _  // 内部：跟随主干
```

### 2.3 一次完整流水线（仅 20 行）

```groovy
@Library('apex-ci-library@2.0') _

node {
    stage('Build') {
        apexBuild('java') {
            jdk = 17
            params { flag('--batch-mode'); property('maven.javadoc.skip', 'true') }
        }
    }

    stage('Tests') {
        parallel 'unit': { sh './mvnw test -Dtest=Unit' },
                  'integ': { sh './mvnw test -Dtest=Integ' }
    }

    stage('Security') {
        def r = apexScan {
            sast    { sh 'sonar-scanner ...' }
            sca     { sh 'snyk test --json' }
            container('app:1.0.0') { sh 'trivy image app:1.0.0' }
        }
        r.failOn = ['high', 'critical']
        r.assertPassed()
    }

    stage('Publish') {
        apexDocker.buildAndPush('registry.local/app:1.0.0', null, 'docker-creds')
    }
}
```

> 你只看到 4 个原生 `stage`，库只在"与外部交换"那 4 行出现。

---

## 3. 入口与共享上下文 `apex{}`

`apex{}` 是一个 **零开销的上下文注入器**——它把一个 `PipelineContext` 注入到 `script.binding.apexCtx`，所有 `apexBuild / apexScan / apexDocker / apexPublish` 内部都从这里取 ctx，你不用每次传。

```groovy
apex {
    // delegate 是 ctx，可以直接调用 ctx 的方法
    setAttr('team', 'treasury')
    setAttr('commit', sh(returnStdout: true, script: 'git rev-parse --short HEAD').trim())
}

// 或者用显式参数（也支持）：
apex { ctx ->
    ctx.setAttr('team', 'treasury')
    ctx.setAttr('commit', sh(returnStdout: true, script: 'git rev-parse --short HEAD').trim())
}

// 之后的 stage 中：
stage('X') {
    echo "team=${apexCtx.getAttr('team')}"
}
```

### 3.1 `PipelineContext` 提供的能力

| 方法 | 用途 |
| --- | --- |
| `ctx.workDir` | `script.pwd()` 缓存值 |
| `ctx.env` | 初始环境变量（只读视图） |
| `ctx.params` | 初始参数（只读视图） |
| `ctx.attrs` | **业务方可读可写** 的跨 stage 共享 map |
| `ctx.setAttr(k, v)` / `ctx.getAttr(k)` / `ctx.hasAttr(k)` | attrs 的快捷方法 |
| `ctx.withEnv(more)` | 返回带额外环境变量的新 ctx |
| `ctx.log(msg)` | 委托 `script.echo`（不依赖 binding 也能用） |
| `ctx.sleeper` | 注入测试用的 Sleeper（默认 NoOpSleeper） |

### 3.2 什么时候需要 `apex{}`？

> **不强制**。如果你的 Jenkinsfile 简单、每个 stage 自己拿 `pwd()` 即可，那就不用 `apex{}`。
> 当你需要**跨 stage 传值**（例如 Build 阶段记录 commit SHA，Publish 阶段取出来用）时，用 `apex{}` 注入一次即可。

---

## 4. 构建 `apexBuild(lang)`

`apexBuild` 是**多语言构建的统一入口**：根据 `lang` 选对应 `Builder`，DSL 闭包用来配置参数，最后由 Builder 走原生 `sh(script: [...])` 执行。

### 4.1 签名

```groovy
apexBuild('java' [, opts], { ... })        // 显式指定语言
apexBuild([opts], { ... })                  // 自动检测（pom/package.json/pyproject）
apexBuild { ... }                           // 同上，最简
```

支持的 `lang`：`java` | `node` | `python` | `go` | `shell`

`opts` 支持的 key：

| key | 含义 | 默认 |
| --- | --- | --- |
| `shellStyle` / `stringShell` | `string` → 走 `sh 'mvn ...'` 字符串；`array`/`list` → 走 `sh(script: [...])` | `array` |

### 4.2 Java 完整示例

```groovy
stage('Build') {
    apexBuild('java') {
        jdk         = 17
        buildTool   = 'maven'            // maven | gradle
        mavenVersion = '3.9.9'

        goals      = ['clean', 'verify']
        profiles   = ['release']
        properties = [
            'maven.javadoc.skip'        : 'true',
            'checkstyle.skip'           : 'false',
            'project.build.sourceEncoding': 'UTF-8'
        ]
        cliOptions = ['--batch-mode', '--update-snapshots', '-fae']

        skipTests  = false
        parallelBuild = true
        threadCount   = '1C'

        // === 动态参数：任意加减 ===
        params {
            flag('-DskipITs')
            flag('--debug')          // 调试完用 params.removeFlag('--debug') 删掉
            property('git.commit.id.abbrev', '7')
            positional('install')
        }
    }
}
```

### 4.3 Node 完整示例

```groovy
stage('Build') {
    apexBuild('node') {
        nodeVersion    = '20.x'
        packageManager = 'pnpm'         // npm | yarn | pnpm

        commands = [
            'install --frozen-lockfile',
            'run lint',
            'test -- --coverage',
            'build -- --mode=production'
        ]

        flags = ['--silent', '--no-audit']
        npmConfig = [
            'registry'           : 'https://nexus.local/repository/npm-group/',
            'fetch-retries'      : '5',
            'fetch-retry-mintimeout': '20000'
        ]

        // 动态追加
        params { flag('--prefer-offline') }
    }
}
```

### 4.4 Python 完整示例

```groovy
stage('Build') {
    apexBuild('python') {
        pythonVersion  = '3.12'
        packageManager = 'poetry'        // poetry | pip | uv

        commands = [
            'install --no-interaction',
            'pytest -q --maxfail=1',
            'ruff check .',
            'mypy src'
        ]

        venv = '.venv'

        params { property('POETRY_HTTP_TIMEOUT', '60') }
    }
}
```

### 4.5 Go 完整示例

```groovy
stage('Build') {
    apexBuild('go') {
        goVersion = '1.23'
        commands  = [
            'mod download',
            'test ./... -race -coverprofile=coverage.out',
            'build -o bin/server ./cmd/server',
            'vet ./...'
        ]
        ldflags = ['-s', '-w']
    }
}
```

### 4.6 自动检测

```groovy
stage('Build') {
    // 当前目录有 pom.xml → JavaBuilder；package.json → NodeBuilder；以此类推
    apexBuild {
        // 配置按检测到的语言解释
    }
}
```

### 4.7 直接走原生 sh

如果某次构建你想完全控制 shell 命令，最简单的就是直接用 `sh`：

```groovy
stage('Build') {
    sh 'mvn -B -fae clean verify -DskipTests'
}
```

库对这种情况没有任何侵入——这正是 2.0 设计的核心原则。

---

## 5. 动态参数 `apexParams()`

`DynamicParams` 是 **"自由加减 CLI 参数"** 的载体。Builder 解析 DSL 时把 `params { ... }` 块的内容并入最终命令。

### 5.1 四种参数

| 类别 | API | 翻译为 |
| --- | --- | --- |
| flag | `flag('--batch-mode')` | 字面量追加 |
| property | `property('maven.javadoc.skip', 'true')` | `-Dk=v`（maven）/ `-Pk=v`（gradle） / `--k=v`（docker） |
| positional | `positional('clean')` | 追加到命令末尾 |
| extra | `extra('region', 'hk')` | 业务自定义，Builder 决定如何用 |

### 5.2 链式 + 拷贝

```groovy
def p = apexParams()                          // 空容器
p.flag('--batch-mode')
p.property('maven.javadoc.skip', 'true')
p.positional('clean')
p.positional('verify')

// 链式
def q = p.copyWith()
        .addFlag('-DskipTests')
        .addPositional('install')

// 删除
p.removeFlag('--batch-mode')
p.removeProperty('maven.javadoc.skip')

// 全部转 list（给某些需要 array 的 API）
def arr = p.asFlagList()                      // ['--batch-mode', 'k=v', 'clean', ...]
```

### 5.3 在 Builder 中使用

```groovy
apexBuild('java') {
    goals = ['clean', 'verify']
    params {
        flag('--batch-mode')
        property('maven.test.failure.ignore', 'true')
        if (env.BRANCH_NAME == 'main') {
            flag('-Drelease=true')
        } else {
            flag('-Drelease=false')
        }
    }
}
```

> **重要**：`params { }` 块是普通 Groovy 闭包，里面可以写 if / for / 任何业务逻辑。Builder 只关心 `params.flags / props / positionals / extras` 的最终值。

---

## 6. 并发扫描 `apexScan{}`

apexScan 把一组扫描任务交给 **Jenkins 原生 `parallel`** 执行，每个分支独立超时、异常隔离；最后调用 `assertPassed()` 做门禁判断。

### 6.1 最小示例

```groovy
stage('Security') {
    def r = apexScan {
        sast    { sh 'sonar-scanner -Dsonar.qualitygate.wait=true' }
        sca     { sh 'snyk test --json' }
        container('app:1.0.0') { sh 'trivy image app:1.0.0' }
    }
    r.failOn = ['high', 'critical']
    r.assertPassed()
}
```

### 6.2 闭包形式注册

apexScan delegate 是一个 `ScanRunner`，支持四种注册方法：

| 方法 | 用途 |
| --- | --- |
| `sast(name='sast') { ... }` | SAST 扫描 |
| `sca(name='sca') { ... }` | SCA 扫描 |
| `container(name='container') { ... }` | 容器镜像扫描 |
| `generic(name) { ... }` | 自定义扫描（license、policy check 等） |

### 6.3 扫描闭包返回值

每个闭包**可以返回 `ScanResult` 对象**——库会把它收集到结果中。如果闭包只跑 `sh` 不返回，结果会被包装为 `OK` + `summary='no-op'`。

```groovy
apexScan {
    sast {
        // 执行扫描
        sh 'sonar-scanner ...'
        // 解析报告得到严重度
        return new ScanResult(scanner: 'sast', status: 'OK', high: 0, medium: 2, low: 5)
    }
    sca {
        def out = sh(returnStdout: true, script: 'snyk test --json').trim()
        def json = new groovy.json.JsonSlurper().parseText(out)
        return new ScanResult(scanner: 'sca', status: 'OK', high: json.vulnerabilities.count { it.severity == 'high' })
    }
}
```

### 6.4 异常隔离

> 一个扫描分支抛异常**不会**让整个 `parallel` 失败；该分支会变成 `FAILED` 状态。

```groovy
def r = apexScan {
    sast { sh 'sonar-scanner ...' }                  // OK
    sca  { error('SCA server is down') }             // FAILED
    container('app:1.0.0') { sh 'trivy image app' } // OK
}
r.assertPassed()   // sca 状态为 FAILED，会抛异常
```

### 6.5 门禁策略

`failOn` 是严重度列表（默认 `['high']`），命中任一即抛异常：

```groovy
r.failOn = ['high']               // 任意 high → 失败（默认）
r.failOn = ['critical', 'high']   // critical 或 high → 失败
r.failOn = []                     // 关闭门禁（仅记录报告）
r.failOn = ['medium', 'high']     // 严格模式
```

`assertPassed()` 抛出的异常形如：

```
apex-ci-library exception: Scanner gate failed: sast:high=2; sca:FAILED:crash
```

### 6.6 超时

```groovy
apexScan {
    timeoutMin = 60     // 每个分支 60 分钟（默认 30）
    sast { sh 'long-running-sast ...' }
    sca  { sh 'snyk test --all-projects' }
}
```

实现：每个分支被 `script.timeout(time: timeoutMin, unit: 'MINUTES')` 包裹——如果超时，分支结果会变成 `FAILED`。

### 6.7 启动后等待（典型模式）

> 用户要求"启动扫描 + 继续做别的 + 之后再统一门禁"？

2.0 推荐把扫描门禁放在独立 stage，让 Jenkins 原生 stage 顺序完成：

```groovy
stage('Kickoff Scans') {
    apexScan {
        sast { sh 'sonar-scanner ...' }   // parallel 阻塞直到所有分支完成
        sca  { sh 'snyk test --json' }
    }
}

stage('Build & Test') {                  // 等扫描完成才开始
    apexBuild('java') { jdk = 17 }
}
```

> 不再需要"异步收集"的两阶段模式——Jenkins 原生 stage 已经天然按顺序串行，把启动 + 等待合并到一个 stage 反而更易读。

---

## 7. 重试 `apexRetry.xxx()`

外部服务（Nexus、Registry、扫描服务）经常出现 transient 错误。`apexRetry` 把线性 / 指数退避封装为可复用的 3 个函数。

### 7.1 线性重试

```groovy
stage('Install') {
    apexRetry.linear(3, 1000) {
        sh 'npm install --no-audit'
    }
}
```

> 失败后等 1s 再试，最多 3 次。

### 7.2 指数退避

```groovy
stage('Pull Image') {
    apexRetry.exponential(5, 500, 2.0) {
        sh 'docker pull registry.local/app:1.0.0'
    }
}
```

> 失败后等 0.5s → 1s → 2s → 4s → 8s，最多 5 次。

### 7.3 条件重试（直到断言成功）

```groovy
stage('Smoke Test') {
    apexRetry.until(5, 2000) { ->
        sh './smoke-test.sh'
        return currentBuild.currentResult == 'SUCCESS'
    } as Closure<Boolean>
}
```

> 闭包返回 `true` 视为成功；`false` 或抛异常都视为失败，按指数退避重试。

### 7.4 与原生 sh 一起用

```groovy
stage('Publish Maven') {
    apexRetry.linear(3, 2000) {
        withCredentials([usernamePassword(credentialsId: 'nexus-deployer',
                                          usernameVariable: 'NEXUS_USER',
                                          passwordVariable: 'NEXUS_PASS')]) {
            sh 'mvn deploy -DskipTests'
        }
    }
}
```

### 7.5 内部实现

`Retry` 类通过 `Sleeper` 接口抽象睡眠，单元测试与沙箱运行环境各有一种实现：

| Sleeper | 用法 | 行为 |
| --- | --- | --- |
| `NoOpSleeper` | `Retry.linear / exponential` 工厂默认 | 不睡眠（避免 CPS 副作用） |
| `JenkinsSleeper(script)` | 沙箱内生产环境 | 走 `script.sleep(n)`（CPS-safe） |
| `Thread.sleep` | 仅在非沙箱环境兜底 | 直接 JVM 睡眠（沙箱会拒绝） |

```groovy
// 沙箱下：apexRetry 自动用 JenkinsSleeper
stage('Publish') {
    apexRetry.exponential(3, 2000, 2.0) {
        withCredentials([...]) { sh 'mvn deploy' }
    }
}

// 自定义 Sleeper（高级用法）：
new Retry(maxAttempts: 3, initialDelayMs: 100, sleeper: new JenkinsSleeper(script)).execute { ... }
```

> **重要**：不要在 `Retry` 构造函数里调用 `Retry.none()` 等静态工厂——CPS 会误识别为方法调用。库内部使用静态工厂方法，已经规避了这个问题。

---

## 8. Docker 镜像 `apexDocker(...)`

### 8.1 签名

```groovy
apexDocker(imageRef) { cfg-body }           // 仅构建
apexDocker.buildAndPush(imageRef) { ... }   // 构建并推送
apexDocker.push(imageRef, credentialsId)    // 推送已构建的镜像
```

`apexDocker(...)` 内部会**自己包一个 `stage("docker-build:...")`**，你不用在外部再 stage。

### 8.2 最小示例

```groovy
stage('Image') {
    apexDocker('registry.local/app:1.0.0') {
        dockerfile = 'docker/Dockerfile'
    }
}
```

### 8.3 完整示例

```groovy
stage('Image') {
    apexDocker('registry.local/app:1.0.0') {
        dockerfile = 'docker/Dockerfile'
        context    = '.'

        // 多架构
        platforms  = ['linux/amd64', 'linux/arm64']

        // build-arg
        buildArgs  = ['NODE_VERSION=20.18.0', 'JAR_FILE=target/*.jar']

        // BuildKit Secret
        secrets    = ['id=npmrc,src=.npmrc', 'id=settings,src=settings.xml']

        // 缓存
        cacheFrom  = ["type=registry,ref=registry.local/app:buildcache"]
        cacheTo    = ["type=registry,ref=registry.local/app:buildcache,mode=max"]
        noCache    = false

        // 动态参数
        params { flag('--progress=plain') }

        // 网络
        networkMode = 'host'
    }
}
```

### 8.4 推送

```groovy
stage('Push') {
    // 方式 1：构建后直接推
    apexDocker.buildAndPush('registry.local/app:1.0.0', null, 'docker-creds')

    // 方式 2：分两步
    apexDocker('registry.local/app:1.0.0') {
        dockerfile = 'docker/Dockerfile'
    }
    apexDocker.push('registry.local/app:1.0.0', 'docker-creds')
}
```

> **凭据注入**：把 stage 包在 `withCredentials([...])` 里即可，库不主动管理凭据。

```groovy
stage('Push') {
    withCredentials([usernamePassword(credentialsId: 'registry-creds',
                                       usernameVariable: 'REG_USER',
                                       passwordVariable: 'REG_PASS')]) {
        apexDocker.buildAndPush('registry.local/app:1.0.0')
    }
}
```

---

## 9. Nexus 发布 `apexPublish(...)`

`apexPublish` 创建一个 `ArtifactPublisher`，delegate 给 DSL 闭包。

### 9.1 Maven

```groovy
stage('Publish Maven') {
    apexPublish('https://nexus.local', 'maven-releases', 'maven2') {
        maven(['-DskipTests'], 'nexus-deployer') {
            sh 'mvn deploy --batch-mode'
        }
    }
}
```

> 内部会注入 `-DaltDeploymentRepository=...`，并把命令放进 `withCredentials` 块中执行。

### 9.2 npm

```groovy
stage('Publish npm') {
    apexPublish('https://nexus.local', 'npm-private', 'npm') {
        npm {
            sh 'npm ci && npm run build'
            sh 'npm publish'
        }
    }
}
```

### 9.3 PyPI

```groovy
stage('Publish PyPI') {
    apexPublish('https://nexus.local', 'pypi-hosted', 'pypi') {
        pypi('dist') {
            sh 'python -m build'
        }
    }
}
```

### 9.4 原始制品

```groovy
stage('Upload Tarball') {
    apexPublish('https://nexus.local', 'raw-hosted', 'raw') {
        raw('app/1.0.0/release.tgz', 'release.tgz', 'application/gzip')
    }
}
```

### 9.5 外部服务不稳定 → 配合重试

```groovy
stage('Publish Maven') {
    apexRetry.linear(5, 2000) {
        apexPublish('https://nexus.local', 'maven-releases', 'maven2') {
            maven(['-DskipTests'], 'nexus-deployer') {
                sh 'mvn deploy --batch-mode'
            }
        }
    }
}
```

---

## 10. 通知 `apexNotify(...)`

```groovy
stage('Notify') {
    apexNotify(to: ['dev@local'], subject: 'CI done', body: 'all good')
}

// 或闭包形式
stage('Notify') {
    apexNotify {
        to      = ['dev@local']
        subject = "Build #${env.BUILD_NUMBER} done"
        body    = "URL: ${env.BUILD_URL}"
    }
}
```

> 当前实现是邮件（基于 `emailext` 插件）；可作为基线被用户扩展。

---

## 11. 配置 `apexConfig.xxx()`

### 11.1 直接读

```groovy
def cfg = apexConfig.fromYaml(readFile('apex-ci.yaml'))
def cfg2 = apexConfig.fromProperties(readFile('apex-ci.properties'))
def cfg3 = apexConfig.fromJson(readFile('apex-ci.json'))
```

### 11.2 闭包式（按需选一种格式）

```groovy
def cfg = apexConfig {
    fromYaml text: readFile('apex-ci.yaml')
}
```

### 11.3 读取配置项

```groovy
def app = cfg.getString('app.name', 'default-app')
def jdk = cfg.getInt('java.jdk', 17)
def platforms = cfg.getList('docker.platforms', ['linux/amd64'])
```

### 11.4 配置优先级

```
环境变量 > 仓库 apex-ci.yaml > 业务方 Jenkinsfile 内 apexConfig > 内置默认
```

---

## 12. 自动版本管理 `apexVersion` / `VersionManager`

发布流水线的版本号计算（auto upgrade）由 `apexVersion` 入口 + `VersionManager` 核心类完成，遵循 [SemVer 2.0.0](https://semver.org/) 规范。

### 12.1 五种 bump 类型

| 类型 | 输入 | 输出 | 适用场景 |
| --- | --- | --- | --- |
| `patch` | `1.2.3` | `1.2.4` | 日常 bugfix / 主干 |
| `minor` | `1.2.3` | `1.3.0` | 兼容新功能 |
| `major` | `1.9.9` | `2.0.0` | 不兼容变更 |
| `release` | `1.3.0-rc.5` | `1.3.0` | 预发布转正式 |
| `prerelease` | `1.3.0` | `1.3.0-rc.1` | 预发布版本 |

> bump 会清空 pre 段：`1.2.3-rc.5` 升 patch → `1.2.4`（无 rc）；`1.2.3-rc.5` 升 prerelease → `1.2.3-rc.6`（rc 号 +1）。

### 12.2 显式声明

```groovy
stage('Compute Version') {
    steps {
        script {
            def pair = apexVersion.bump('1.2.3', 'minor') {
                buildMeta     = env.GIT_COMMIT_SHORT  // 附加 build 段
                preReleaseTag = 'rc.1'                // 只在 prerelease 时用
            }
            def next = pair[0]    // "1.3.0+abc1234"
            def mgr  = pair[1]    // VersionManager 实例，便于后续 stage 引用
            env.APP_VERSION = next
            echo "Publishing as ${next} (manager: ${mgr})"
        }
    }
}
```

`bump()` 返回 `List<String, VersionManager>`：
- `[0]` = 最终版本号字符串（如 `"1.3.0+abc1234"`）
- `[1]` = `VersionManager` 实例，字段包括 `baseVersion / bump / preReleaseTag / buildMeta / resolved`

### 12.3 自动从环境变量

`auto()` 有两个重载：

```groovy
// 1) 默认：从 ctx.env 读 BUILD_VERSION / BUMP_TYPE / BUILD_META
def v = apexVersion.auto()

// 2) 显式传 env（CPS 沙箱里推荐，避免依赖 ctx.env）
def v = apexVersion.auto([
    BUILD_VERSION: '1.2.3',
    BUMP_TYPE    : 'patch',
    BUILD_META   : 'abc1234',
])
```

业务方配置（在 `environment {}` 或 stage 内）：

```groovy
environment {
    BUILD_VERSION = '1.2.3'                                          # 当前版本（必填）
    BUMP_TYPE     = 'patch'                                           # patch|minor|major|release|prerelease
    BUILD_META    = sh(returnStdout: true, script: 'git rev-parse --short HEAD').trim()  # 可选
}
```

环境变量读取顺序：
- `BUILD_VERSION` 或 `BASE_VERSION`
- `BUMP_TYPE`（默认 `patch`）
- `PRERELEASE_TAG`（仅 prerelease 类型有效）
- `BUILD_META` 或 `GIT_COMMIT_SHORT`

### 12.4 解析与比较（SemVer）

```groovy
def a = apexVersion.parse('1.2.3')
def b = apexVersion.parse('1.3.0-rc.1+build.42')

assert a.major == 1
assert a.isPreRelease() == false
assert b.pre == 'rc.1'
assert b.buildMeta == 'build.42'

assert b > a                            // 1.3.0-rc.1 > 1.2.3
assert apexVersion.max(a, b) == b       // 取大
assert apexVersion.min(a, b) == a       // 取小

// 安全解析
def c = apexVersion.tryParse('not-a-version')  // null
```

### 12.5 完整发布流水线

```groovy
@Library('apex-ci-library@2.0') _

pipeline {
    agent any
    environment {
        BUILD_VERSION = '1.2.3'
        BUMP_TYPE     = 'patch'
    }
    stages {
        stage('Compute') {
            steps {
                script {
                    def v = apexVersion.auto()
                    env.PUBLISH_VERSION = v       // 给后续 stage 使用
                }
            }
        }
        stage('Build') {
            steps { apexBuild('java') { jdk = 17 } }
        }
        stage('Security') {
            steps {
                def r = apexScan {
                    sast    { sh 'sonar-scanner ...' }
                    sca     { sh 'snyk test --json' }
                }
                r.failOn = ['high']
                r.assertPassed()
            }
        }
        stage('Image') {
            steps {
                apexDocker("registry.local/app:${env.PUBLISH_VERSION}".toString()) {
                    dockerfile = 'docker/Dockerfile'
                }
            }
        }
        stage('Publish') {
            when { branch 'main' }
            steps {
                apexPublish('https://nexus.local', 'maven-releases', 'maven2') {
                    maven(['-DskipTests'], 'nexus-deployer') {
                        sh 'mvn deploy --batch-mode'
                    }
                }
            }
        }
    }
}
```

### 12.6 升级判定（升级 vs 跳过）

```groovy
def current = apexVersion.parse(env.CURRENT_VERSION)
def latest  = apexVersion.parse(env.LATEST_VERSION)

if (latest > current) {
    echo "Upgrading from ${current} to ${latest}"
} else {
    echo "Already up to date"
}
```

---

## 13. 典型模式 Cookbook

### 13.1 Monorepo：按模块并行构建

```groovy
node {
    stage('Build & Test') {
        parallel(
            'core':   { apexBuild('java') { jdk = 17; goals = ['-pl', 'core', 'clean', 'verify'] } },
            'api':    { apexBuild('java') { jdk = 17; goals = ['-pl', 'api',  'clean', 'verify'] } },
            'portal': { apexBuild('node') { nodeVersion = '20.x'; commands = ['ci', 'test', 'build'] } }
        )
    }
}
```

### 13.2 多语言混编

```groovy
stage('Polyglot') {
    parallel(
        'java':   { apexBuild('java')   { jdk = 17 } },
        'node':   { apexBuild('node')   { commands = ['ci', 'test', 'build'] } },
        'python': { apexBuild('python') { commands = ['pytest', 'build'] } },
        'go':     { apexBuild('go')     { commands = ['test', 'build'] } }
    )
}
```

### 13.3 Build & Push & Deploy

```groovy
stage('Build')    { apexBuild('java') { jdk = 17 } }
stage('Image')    { apexDocker('registry.local/app:1.0.0') { dockerfile = 'docker/Dockerfile' } }
stage('Push')     { apexDocker.push('registry.local/app:1.0.0', 'registry-creds') }
stage('Deploy')   { sh "helm upgrade --install app ./chart --set image.tag=1.0.0" }
```

### 13.4 PR 门禁流水线

```groovy
node {
    stage('Build')  { apexBuild('java') { jdk = 17 } }
    stage('Lint')   { sh 'npm run lint' }
    stage('Tests')  { sh 'mvn -B test' }

    stage('Security') {
        def r = apexScan {
            sast    { sh 'sonar-scanner -Dsonar.qualitygate.wait=true' }
            sca     { sh 'snyk test --json' }
            generic('secrets') { sh 'gitleaks detect --source .' }
        }
        r.failOn = ['high', 'critical']
        r.assertPassed()
    }
}
```

### 13.5 外部服务不稳定场景

```groovy
// 场景：Nexus 偶发性 502
stage('Publish') {
    apexRetry.exponential(5, 1000, 2.0) {
        withCredentials([usernamePassword(credentialsId: 'nexus-deployer',
                                          usernameVariable: 'NEXUS_USER',
                                          passwordVariable: 'NEXUS_PASS')]) {
            sh 'mvn deploy -DskipTests'
        }
    }
}

// 场景：Snyk 服务偶发 504
stage('SCA') {
    apexRetry.linear(3, 2000) {
        sh 'snyk test --json > sca.json'
    }
}
```

### 13.6 启动 + 等待扫描 + 门禁

```groovy
stage('Security Scans') {
    def r = apexScan {
        sast    { sh 'sonar-scanner ...' }
        sca     { sh 'snyk test --json' }
        container('app:1.0.0') { sh 'trivy image app:1.0.0' }
    }
    r.failOn = ['critical']
    r.assertPassed()
}
```

### 13.7 重试 + 扫描门禁混合

```groovy
stage('Publish (after scans)') {
    apexScan {
        // 即使每个扫描都可能瞬时失败，也会被并行重试 + 隔离
        sast { apexRetry.linear(2, 1000) { sh 'sonar-scanner ...' } }
        sca  { apexRetry.linear(2, 1000) { sh 'snyk test' } }
    }
    apexRetry.exponential(3, 1000) {
        sh 'mvn deploy -DskipTests'
    }
}
```

---

## 14. 从旧 DSL 迁移

### 14.1 旧 DSL 写法（v1.x）

```groovy
apex {
    appName = 'svc'
    vars = [team: 'treasury']
    stages {
        stage('Build') {
            java {
                jdk = 21
                goals = ['clean', 'verify']
            }
        }
    }
}
```

### 14.2 新 DSL 写法（v2.0）

```groovy
@Library('apex-ci-library@2.0') _

apex { ctx ->
    ctx.setAttr('team', 'treasury')
}

node {
    stage('Build') {
        apexBuild('java') {
            jdk = 17
            goals = ['clean', 'verify']
        }
    }
}
```

### 14.3 映射表

| 旧 API | 新 API |
| --- | --- |
| `apex { stages { stage { java { } } } }` | `node { stage { apexBuild('java') { } } }` |
| `apex.startScan {}` / `apex.collectScans()` | `apexScan { ... }`（合并为单 stage） |
| `apex.gate(results, policy: 'high+')` | `runner.assertPassed()` （设置 `runner.failOn`） |
| `apex.detectLanguage()` | `BuilderFactory.autoDetect(...)` |
| `apexNexus {}` | `apexPublish(...)` |
| `apexContext.vars.team` | `apexCtx.getAttr('team')` |
| `parallel { java {} node {} }` | `parallel('java': {...}, 'node': {...})`（原生） |
| `matrix { axes { axis(...) } }` | 原生 `matrix {}` 块 |

### 14.4 收益

- **更短**：少一层自定义抽象
- **更稳**：少一处 CPS 转换
- **更兼容**：Jenkins 升级时几乎不破坏
- **更可读**：Jenkinsfile 像普通 Groovy，业务方不需要学新 DSL

---

## 15. 故障排查

### 15.1 沙箱拒绝执行

```
unclassified method java.lang.String replaceAll
```

→ 闭包内不要做复杂字符串处理。改用库方法（`Sandbox.render`）或拆分到 `apexConfig` / `Util` 工具中。

### 15.2 库版本不匹配

```
Library apex-ci-library version 1.x is required (current: 2.x)
```

→ 升级 `@Library('apex-ci-library@2.0') _` 或保留 1.x 写法。

### 15.3 Docker buildx 不存在

```
docker: 'buildx' is not a docker command
```

→ 升级 Docker ≥ 19.03 并安装 buildx 插件；或在 `apexDocker` 闭包中设置 `dockerfile = 'Dockerfile'` 走 `docker build`。

### 15.4 Nexus 401 Unauthorized

- 检查 `credentialsId` 是否正确
- 检查 `nexus-deployer` 凭据是否过期
- 确认仓库允许 deployment 角色

### 15.5 扫描分支卡死

→ 设置 `runner.timeoutMin = 30`（默认已 30 分钟）；如需更长可改为 60。

### 15.6 重试无效

```groovy
// 错：调用了 Retry.none()，但实际上不重试
apexRetry.linear(1, 0) { sh '...' }   // 等于不重试

// 对：maxAttempts >= 2
apexRetry.linear(3, 1000) { sh '...' }
```

### 15.7 日志位置

| 类别 | 路径 |
| --- | --- |
| 构建日志 | Jenkins Job UI → Console Output |
| 扫描汇总 | `apexScan` 内部 `ConsoleReporter` 输出到 stage 日志 |
| 库调试 | `PipelineContext.log(msg)`（内部走 `script.echo`） |

---

## 16. API 一览

### 16.1 全局变量（`vars/`）

| 全局变量 | 用途 |
| --- | --- |
| `apex{}` | 注入共享 `PipelineContext` |
| `apexBuild(lang, [opts], body)` | 多语言构建（内部走原生 sh） |
| `apexScan{}` | 并发扫描（内部走原生 parallel + timeout） |
| `apexDocker(image, body)` | 镜像构建 |
| `apexDocker.push(image, credId)` | 推送镜像 |
| `apexDocker.buildAndPush(image, body, credId)` | 构建并推送 |
| `apexPublish(url, repo, format) {}` | 制品发布（maven/npm/pypi/raw） |
| `apexRetry.linear(n, ms) {}` | 线性重试 |
| `apexRetry.exponential(n, ms, mult) {}` | 指数退避 |
| `apexRetry.until(n, ms) {}` | 条件重试 |
| `apexParams()` / `apexParams {}` | 动态参数工厂 |
| `apexConfig.xxx(text)` | YAML/JSON/Properties 解析 |
| `apexNotify(args) {}` | 邮件通知 |

### 16.2 主要类（`src/`）

| 类 | 包 | 职责 |
| --- | --- | --- |
| `PipelineContext` | `core` | script 代理 + 共享数据 |
| `PipelineContextBuilder` | `core` | 链式构造 |
| `DynamicParams` | `core` | 自由加减 CLI 参数 |
| `Retry` / `Sleeper` / `NoOpSleeper` / `JenkinsSleeper` | `core` | 重试策略与抽象 |
| `AbstractBuilder` | `builders` | 构建器基类 |
| `JavaBuilder` / `NodeBuilder` / `PythonBuilder` / `GoBuilder` / `ShellBuilder` | `builders` | 各语言实现 |
| `BuilderFactory` | `builders` | 注册表与 autoDetect |
| `ScanRunner` / `ScanResult` | `scanners` | 并发扫描运行器 |
| `ConsoleReporter` | `reporters` | 扫描汇总 |
| `DockerBuilder` / `DockerPusher` / `DockerBuildConfig` | `docker` | 镜像构建/推送 |
| `NexusClient` / `ArtifactPublisher` | `artifact` | Nexus 命令拼装 |
| `EmailNotifier` | `notifiers` | 邮件通知 |
| `LibraryConfig` | `config` | YAML/Properties/JSON 解析 |
| `Sandbox` / `Util` | `utils` | 沙箱 / 工具 |
| `ApexCIException` / `BuildException` / `ScanException` / `ConfigException` | `errors` | 统一异常类型 |

---

## 17. 6 个端到端 Jenkinsfile 入门

仓库自带 6 个独立的 Jenkinsfile（位于 `docker/test-env/jenkins/`），覆盖典型 CI 场景，已在本地 Jenkins 沙箱里跑过数百轮。本节介绍如何在自己的环境里运行它们。

### 17.1 总览

| 任务名 | 文件 | 覆盖场景 | 验证状态 |
| --- | --- | --- | --- |
| `apex-modules-test` | `Jenkinsfile-modules` | 全模块接口 + 集成用例 | 51 次成功，62/62 用例通过 |
| `apex-build-java` | `Jenkinsfile-build-java` | 单一 Java/Maven 构建 | 39 次成功 |
| `apex-parallel-build` | `Jenkinsfile-parallel-build` | 4 语言并行构建 | 25 次成功 |
| `apex-wait-scan` | `Jenkinsfile-wait-scan` | 并行扫描 + 门禁 | 24 次成功 |
| `apex-version` | `Jenkinsfile-version` | 自动版本管理（5 种 bump） | 24 次成功 |
| `apex-mixed` | `Jenkinsfile-mixed` | 混合场景 | 26 次成功 |

### 17.2 启动环境

```bash
# 启动 Jenkins（连同 Nexus / Registry）
docker compose -f docker/test-env/docker-compose.yml up -d

# 等待 ready
until curl -fsS http://localhost:8080/ >/dev/null; do sleep 5; done
```

`docker/test-env/jenkins/init.groovy.d/01-seed.groovy` 会自动注册上述 6 个任务 + `apex-ci-library-local` 共享库。共享库指向挂载的 `/var/jenkins_home/pipeline`（本仓库根目录）。

### 17.3 触发单个任务

```bash
JENKINS_USER=admin
JENKINS_PASS=admin   # 本地测试环境默认密码

curl -X POST "http://${JENKINS_USER}:${JENKINS_PASS}@localhost:8080/job/apex-build-java/build"
```

UI：在浏览器打开 `http://localhost:8080` → 登录 → 左侧栏选择任务 → Build Now。

### 17.4 完整模板

#### 17.4.1 `Jenkinsfile-build-java` 模板

```groovy
@Library('apex-ci-library-local@main') _

pipeline {
    agent any
    options { timeout(time: 20, unit: 'MINUTES'); disableConcurrentBuilds() }

    parameters {
        string(name: 'MVN_GOALS',  defaultValue: 'verify', description: 'Maven goals')
        string(name: 'SAMPLE_DIR', defaultValue: 'docker/test-env/samples/java')
        string(name: 'BUMP_TYPE',  defaultValue: 'patch', description: 'patch|minor|major|release|prerelease')
    }

    environment {
        PIPELINE_ROOT = '/var/jenkins_home/pipeline'
        BUILD_VERSION = '1.2.3'
        BUILD_META    = 'jenkins-build'
    }

    stages {
        stage('Compute Version') {
            steps {
                script {
                    String v = apexVersion.auto([
                        BUILD_VERSION: env.BUILD_VERSION,
                        BUMP_TYPE    : params.BUMP_TYPE,
                        BUILD_META   : env.BUILD_META,
                    ])
                    env.APP_VERSION = v
                }
            }
        }
        stage('Build') {
            steps {
                dir("${env.PIPELINE_ROOT}/${params.SAMPLE_DIR}") {
                    script {
                        String goals = params.MVN_GOALS
                        apex {
                            apexBuild('java') {
                                jdk        = 11
                                buildTool  = 'maven'
                                goals      = goals.split('\\s+') as List
                                params { flag('--batch-mode'); flag('-DskipITs') }
                            }
                        }
                    }
                }
            }
        }
        stage('Verify Artifact') {
            steps {
                script {
                    String jar = "${env.PIPELINE_ROOT}/${params.SAMPLE_DIR}/target/demo.jar"
                    if (!fileExists(jar)) error "Expected jar not produced: ${jar}"
                }
            }
        }
    }
}
```

#### 17.4.2 `Jenkinsfile-parallel-build` 模板

```groovy
@Library('apex-ci-library-local@main') _

pipeline {
    agent any
    parameters {
        booleanParam(name: 'RUN_JAVA',   defaultValue: true)
        booleanParam(name: 'RUN_NODE',   defaultValue: true)
        booleanParam(name: 'RUN_PYTHON', defaultValue: true)
        booleanParam(name: 'RUN_GO',     defaultValue: true)
    }
    environment { PIPELINE_ROOT = '/var/jenkins_home/pipeline' }

    stages {
        stage('Parallel Multi-Language Build') {
            steps {
                script {
                    Map<String, Closure> branches = [:]
                    if (params.RUN_JAVA) {
                        branches['java'] = {
                            dir("${env.PIPELINE_ROOT}/docker/test-env/samples/java") {
                                apexBuild('java') {
                                    jdk = 11; buildTool = 'maven'
                                    goals = ['-B', 'package']
                                    params { flag('--batch-mode'); flag('-DskipITs') }
                                }
                            }
                        }
                    }
                    if (params.RUN_NODE) {
                        branches['node'] = {
                            dir("${env.PIPELINE_ROOT}/docker/test-env/samples/node") {
                                apexBuild('node') {
                                    packageManager = 'npm'
                                    install = true
                                    scripts = ['test']
                                }
                            }
                        }
                    }
                    if (params.RUN_PYTHON) {
                        branches['python'] = {
                            dir("${env.PIPELINE_ROOT}/docker/test-env/samples/python") {
                                apexBuild('python') {
                                    venv = false
                                    commands = ['install', '--quiet', '--break-system-packages', 'pytest']
                                }
                            }
                        }
                    }
                    if (params.RUN_GO) {
                        branches['go'] = {
                            dir("${env.PIPELINE_ROOT}/docker/test-env/samples/go") {
                                apexBuild('go') {
                                    commands = ['build', './...']
                                }
                            }
                        }
                    }
                    parallel(branches)
                }
            }
        }
    }
}
```

#### 17.4.3 `Jenkinsfile-wait-scan` 模板

```groovy
@Library('apex-ci-library-local@main') _

pipeline {
    agent any
    parameters {
        string(name: 'SCAN_FAIL_BRANCH', defaultValue: '',
               description: 'sast|sca|container|license|<empty=ok>')
        booleanParam(name: 'ENABLE_LICENSE', defaultValue: true)
        string(name: 'SCAN_TIMEOUT_MIN', defaultValue: '5')
    }

    stages {
        stage('Wait for scan results') {
            steps {
                script {
                    String fail = (params.SCAN_FAIL_BRANCH ?: '').toLowerCase()
                    int sleepMs = 800
                    long started = System.currentTimeMillis()

                    def runner = apexScan {
                        sast { ->
                            if (fail == 'sast') throw new RuntimeException("SAST down")
                            Thread.sleep(sleepMs)
                            return [scanner: 'sast', status: 'OK', high: 0, medium: 2, low: 5]
                        }
                        sca { ->
                            if (fail == 'sca') throw new RuntimeException("SCA down")
                            Thread.sleep(sleepMs)
                            return [scanner: 'sca', status: 'OK', high: 0]
                        }
                        container('apex-sample:1.0.0') { ->
                            if (fail == 'container') throw new RuntimeException("trivy failed")
                            Thread.sleep(sleepMs)
                            return [scanner: 'container', status: 'OK', high: 0, medium: 1]
                        }
                    }
                    runner.timeoutMin = (params.SCAN_TIMEOUT_MIN ?: '5').toLong()
                    runner.failOn = ['high', 'critical']
                    def results = runner.run()
                    long elapsed = System.currentTimeMillis() - started
                    if (elapsed < 4 * sleepMs) error "Scanners returned too fast; not parallel"
                    runner.assertPassed(results)
                }
            }
        }
    }
}
```

#### 17.4.4 `Jenkinsfile-version` 模板

```groovy
@Library('apex-ci-library-local@main') _

pipeline {
    agent any
    parameters {
        string(name: 'BASE_VERSION', defaultValue: '1.2.3')
        string(name: 'BUMP_TYPE',    defaultValue: 'patch', description: 'patch|minor|major|release|prerelease')
        string(name: 'PRE_TAG',      defaultValue: 'rc.1')
        string(name: 'BUILD_META',   defaultValue: 'jenkins')
    }

    stages {
        stage('Explicit Bump') {
            steps {
                script {
                    String baseV  = params.BASE_VERSION
                    String bumpT  = params.BUMP_TYPE
                    String preR   = params.PRE_TAG
                    String buildM = params.BUILD_META
                    def pair = apexVersion.bump(baseV, bumpT) {
                        buildMeta     = buildM
                        preReleaseTag = preR
                    }
                    def next = pair[0]
                    def mgr  = pair[1]
                    env.APP_VERSION = next
                    echo "Bump: ${baseV} --${bumpT}--> ${next}".toString()
                    echo "Manager: base=${mgr.baseVersion} bump=${mgr.bump} pre=${mgr.preReleaseTag} meta=${mgr.buildMeta}".toString()
                }
            }
        }
    }
}
```

#### 17.4.5 `Jenkinsfile-mixed` 模板

```groovy
@Library('apex-ci-library-local@main') _

pipeline {
    agent any
    options { timeout(time: 30, unit: 'MINUTES') }
    parameters {
        string(name: 'BASE_VERSION',  defaultValue: '1.0.0')
        string(name: 'BUMP_TYPE',     defaultValue: 'minor')
        booleanParam(name: 'RUN_PARALLEL', defaultValue: true)
        booleanParam(name: 'RUN_SCAN',     defaultValue: true)
    }
    environment { PIPELINE_ROOT = '/var/jenkins_home/pipeline' }

    stages {
        stage('1. Auto Version') {
            steps {
                script {
                    def v = apexVersion.auto([
                        BUILD_VERSION: params.BASE_VERSION,
                        BUMP_TYPE    : params.BUMP_TYPE,
                        BUILD_META   : 'jenkins-mixed',
                    ])
                    env.APP_VERSION = v
                }
            }
        }
        stage('2. Single Build (Java)') {
            steps {
                dir("${env.PIPELINE_ROOT}/docker/test-env/samples/java") {
                    script {
                        apex {
                            apexBuild('java') {
                                jdk = 11; buildTool = 'maven'
                                goals = ['verify']
                                params { flag('--batch-mode'); flag('-DskipITs') }
                            }
                        }
                    }
                }
            }
        }
        stage('3. Parallel Multi-Language Build') {
            when { expression { return params.RUN_PARALLEL } }
            steps {
                script {
                    def branches = [:]
                    branches['java'] = {
                        dir("${env.PIPELINE_ROOT}/docker/test-env/samples/java") {
                            apexBuild('java') { jdk = 11; buildTool = 'maven'; goals = ['-B', 'test'] }
                        }
                    }
                    branches['node'] = {
                        dir("${env.PIPELINE_ROOT}/docker/test-env/samples/node") {
                            apexBuild('node') { packageManager = 'npm'; install = true; scripts = ['test'] }
                        }
                    }
                    branches['python'] = {
                        dir("${env.PIPELINE_ROOT}/docker/test-env/samples/python") {
                            apexBuild('python') { venv = false; commands = ['install', '--quiet', '--break-system-packages', 'pytest'] }
                        }
                    }
                    branches['go'] = {
                        dir("${env.PIPELINE_ROOT}/docker/test-env/samples/go") {
                            apexBuild('go') { commands = ['build', './...'] }
                        }
                    }
                    parallel(branches)
                }
            }
        }
        stage('4. Wait for Scan') {
            when { expression { return params.RUN_SCAN } }
            steps {
                script {
                    int sleepMs = 600
                    long started = System.currentTimeMillis()
                    def runner = apexScan {
                        sast    { -> Thread.sleep(sleepMs); return [scanner: 'sast',    status: 'OK', high: 0, medium: 1] }
                        sca     { -> Thread.sleep(sleepMs); return [scanner: 'sca',     status: 'OK', high: 0] }
                        container('apex-sample:1.0.0') { -> Thread.sleep(sleepMs); return [scanner: 'container', status: 'OK', high: 0] }
                    }
                    runner.failOn = ['high', 'critical']
                    def results = runner.run()
                    long elapsed = System.currentTimeMillis() - started
                    if (elapsed > (long)(3 * sleepMs * 1.5)) error "Scanners took ${elapsed} ms; not parallel"
                    runner.assertPassed(results)
                }
            }
        }
        stage('5. Retry on Transient Failure') {
            steps {
                script {
                    int attempt = 0
                    def result = apexRetry.linear(5, 100) { ->
                        attempt++
                        if (attempt < 3) throw new RuntimeException("simulated 502 (attempt ${attempt})")
                        return 'recovered'
                    }
                    echo "Retry recovered: ${result} after ${attempt} attempts"
                }
            }
        }
    }
}
```

### 17.5 常见 CPS 沙箱陷阱

写 Jenkinsfile 时要避开这些坑：

| 陷阱 | 解决 |
| --- | --- |
| `apexConfig.fromYaml(text)` 被误判为 DSL 步骤 | 改用 `apexConfig { fromYaml text: '...' }` 闭包形式 |
| `void track(...)` 被误判 | 改为返回 `String` |
| `apexBuild { ... }` 闭包内 `params.X` 解析为 builder config 的 `params` 字段 | 把 `params` 复制到闭包外：`String buildM = params.BUILD_META` |
| `summary` 阶段在 `node {}` 外 → CPS 拒绝 | 把 Summary 移至 `node` 内部 |
| `mvn clean` 因 mount 跨用户权限失败 | 移除 `clean`，用 `package` / `verify` |
| Python 3.13+ `externally-managed-environment` | 显式加 `--break-system-packages` |
| 业务方忘了 `@Library('apex-ci-library-local@main') _` | 必须以 `_` 结尾（沙箱模式） |

### 17.6 验证结果

每个任务在 `Console Output` 末尾输出 `[PASS]` 字样。`apex-modules-test` 还会在 stage 14 末输出：

```
==========================================
  APEX MODULE TEST SUMMARY: 62/62 passed, 0 failed
==========================================
```

`apex-mixed` 会在 post 阶段输出：

```
==============================
  MIXED PIPELINE SUMMARY
  APP_VERSION=1.1.0+jenkins-mixed
==============================
```

详细的 CPS 沙箱陷阱与设计动机参见 [design.md §19.8](./design.md#198-cps-沙箱陷阱与解决方案)。
