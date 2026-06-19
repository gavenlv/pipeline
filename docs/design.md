# apex-ci-library 设计文档

> **项目名称**：`apex-ci-library`
> **包名（Package）**：`com.hsbc.treasury.apex.ci`
> **类型**：Jenkins Shared Library
> **版本**：v1.0.0 (Draft)
> **最后更新**：2026-06-19

---

## 1. 背景与目标

HSBC Treasury APEX 平台涉及大量多语言（Java / Node / Python / Go / .NET 等）微服务的持续集成。现有 CI 流水线存在以下问题：

- 各业务线重复编写类似的 Jenkinsfile，模板分散、版本不一。
- 安全扫描（SAST / SCA / Container / IaC / DAST）接入方式不统一，结果散落，难以聚合与门禁。
- 不同语言构建工具链（Maven / npm / Poetry / Go modules / dotnet）参数化方式各异。
- 全局变量、凭据、超时、重试等横切关注点缺乏统一抽象。

**apex-ci-library** 的目标：

1. **一份可复用的 CI 库**，作为 Jenkins Shared Library 在所有 APEX 流水线中统一接入。
2. **自由组合（Composable）**：通过 Fluent DSL 让业务方按"积木"方式拼装自己的 Pipeline。
3. **模块化（Modular）**：Builders、Scanners、Reporters、Notifiers 等均以模块形式独立开发、测试、发布。
4. **沙箱安全（Sandbox-safe）**：所有代码在 Jenkins Sandbox 内安全运行，避免 `evaluate` / 动态 Groovy。
5. **并发 + 异步**：扫描任务并行启动，并以 `Future` 风格聚合结果，不阻塞主流程。
6. **多语言构建**：统一抽象支持 Java / Node / Python / Go / .NET / Shell 等。

---

## 2. 设计原则

| 原则 | 描述 |
| --- | --- |
| **Convention over Configuration** | 零配置即可运行常见场景，复杂场景通过 `with{}` 块覆盖。 |
| **Fluent DSL** | 链式 API，提升 Jenkinsfile 可读性。 |
| **Pipeline as Code（强类型）** | 领域对象（`Pipeline` / `Stage` / `Step`）而非字符串模板。 |
| **Sandbox First** | 所有外部命令必须通过数组形式 `sh(script: [...], returnStdout: true)`，禁止字符串拼接执行。 |
| **Immutable Context** | `PipelineContext` 不可变，修改通过 `withConfig` 返回新上下文。 |
| **Module SPI** | 通过 `ServiceLoader`-style 注册机制，模块可插拔。 |
| **Testable in Isolation** | 核心逻辑（CPS-aware）单测覆盖率 ≥ 80%。 |

---

## 3. 仓库结构

```
apex-ci-library/
├── Jenkinsfile                              # 库的"自检"流水线（PR/Tag 触发）
├── settings.gradle                          # Jenkins Shared Library 不使用，仅占位
├── README.md
├── CHANGELOG.md
│
├── src/                                     # Groovy 主源码（package: com.hsbc.treasury.apex.ci.*）
│   └── com/hsbc/treasury/apex/ci/
│       ├── ApexCIRoot.groovy                # 库根入口（脚本可直接 import）
│       ├── Pipeline.groovy                  # 流水线编排器
│       ├── Stage.groovy                     # 阶段抽象
│       ├── Step.groovy                      # 步骤抽象
│       ├── PipelineContext.groovy           # 跨阶段共享上下文（不可变）
│       │
│       ├── config/
│       │   ├── GlobalConfig.groovy          # 全局配置（环境、镜像、代理）
│       │   ├── BuildConfig.groovy           # 构建相关配置
│       │   ├── ScanConfig.groovy            # 安全扫描配置
│       │   └── PipelineConfig.groovy        # 顶层 Pipeline 配置
│       │
│       ├── core/
│       │   ├── ParallelExecutor.groovy      # 并发执行器
│       │   ├── AsyncResult.groovy           # 异步结果句柄（Future 抽象）
│       │   ├── ResultAggregator.groovy      # 多源结果聚合
│       │   ├── Retry.groovy                 # 重试策略
│       │   └── Timeout.groovy               # 超时控制
│       │
│       ├── builders/                        # 多语言构建器
│       │   ├── Builder.groovy               # 构建器接口
│       │   ├── BuilderRegistry.groovy       # 构建器注册中心（SPI）
│       │   ├── JavaBuilder.groovy           # Maven / Gradle
│       │   ├── NodeBuilder.groovy           # npm / yarn / pnpm
│       │   ├── PythonBuilder.groovy         # poetry / pip / uv
│       │   ├── GoBuilder.groovy             # go modules
│       │   ├── DotnetBuilder.groovy         # dotnet CLI
│       │   └── ShellBuilder.groovy          # 兜底（Make / 自定义脚本）
│       │
│       ├── docker/                          # 容器镜像构建与推送
│       │   ├── DockerBuilder.groovy         # docker build（多阶段 / BuildKit）
│       │   ├── DockerPusher.groovy          # 推送到 Nexus / Harbor / ECR
│       │   ├── ImageTagger.groovy           # 标签策略（sha / semver / latest）
│       │   ├── DockerRegistry.groovy        # 注册中心 SPI
│       │   └── DockerfileTemplate.groovy    # Dockerfile 模板渲染
│       │
│       ├── artifact/                        # 制品管理（NEXUS / Artifactory）
│       │   ├── NexusClient.groovy           # Nexus 3 REST 客户端
│       │   ├── ArtifactPublisher.groovy     # 制品上传
│       │   ├── MavenArtifact.groovy         # jar/war/pom 上传
│       │   ├── NpmArtifact.groovy           # npm tarball / scoped pkg
│       │   ├── PyPiArtifact.groovy          # wheel / sdist
│       │   ├── DockerArtifact.groovy        # 容器镜像清单
│       │   └── ArtifactMetadata.groovy      # 版本 / 校验和 / 签名
│       │
│       ├── scanners/                        # 安全扫描（异步）
│       │   ├── Scanner.groovy               # 扫描器接口
│       │   ├── ScannerRegistry.groovy
│       │   ├── SastScanner.groovy           # SonarQube / Semgrep
│       │   ├── ScaScanner.groovy            # OWASP Dependency-Check / Snyk
│       │   ├── ContainerScanner.groovy      # Trivy / Grype
│       │   ├── IacScanner.groovy            # Checkov / tfsec
│       │   ├── SecretsScanner.groovy        # gitleaks / trufflehog
│       │   ├── LicenseScanner.groovy        # 许可证合规
│       │   └── ScanResultCollector.groovy   # 异步结果采集器
│       │
│       ├── reporters/
│       │   ├── Reporter.groovy
│       │   ├── HtmlReporter.groovy
│       │   ├── JunitReporter.groovy
│       │   └── SummaryReporter.groovy
│       │
│       ├── notifiers/
│       │   ├── Notifier.groovy
│       │   ├── SlackNotifier.groovy
│       │   ├── EmailNotifier.groovy
│       │   └── TeamsNotifier.groovy
│       │
│       ├── utils/
│       │   ├── Logger.groovy                # 统一日志（带 emoji-free 文本）
│       │   ├── FileUtils.groovy
│       │   ├── HttpClient.groovy
│       │   ├── ToolResolver.groovy          # 工具链解析
│       │   ├── Sandbox.groovy               # 沙箱安全工具
│       │   └── Env.groovy                   # 环境变量与凭据访问
│       │
│       └── errors/
│           ├── ApexCIException.groovy
│           ├── BuildException.groovy
│           ├── ScanException.groovy
│           └── ConfigException.groovy
│
├── vars/                                    # 全局变量 / DSL（暴露给 Jenkinsfile）
│   ├── apex.groovy                          # 顶层入口：apex { ... }
│   ├── apexPipeline.groovy                  # 创建裸 Pipeline 对象
│   ├── apexBuild.groovy                     # 快速构建：apexBuild(java, ...)
│   ├── apexScan.groovy                      # 快速扫描：apexScan { sast(); sca() }
│   ├── apexConfig.groovy                    # 全局配置
│   ├── apexContext.groovy                   # 跨 Pipeline 共享上下文读取
│   └── apexVersion.groovy                   # 库版本查询
│
├── resources/                               # 静态资源
│   ├── templates/                           # Pipeline 模板
│   │   ├── java-maven.tpl
│   │   ├── node-npm.tpl
│   │   ├── python-poetry.tpl
│   │   ├── go-mod.tpl
│   │   └── dotnet.tpl
│   ├── policies/
│   │   ├── sast-rules.json
│   │   ├── sca-policy.json
│   │   └── license-allowlist.json
│   ├── tools/
│   │   └── versions.json                    # 工具版本矩阵
│   └── scripts/                             # 经审计的 shell 脚本
│       ├── detect-language.sh
│       └── install-tool.sh
│
├── test/                                    # 单元 + 集成测试
│   ├── com/hsbc/treasury/apex/ci/
│   │   ├── PipelineTest.groovy
│   │   ├── StageTest.groovy
│   │   ├── core/
│   │   │   ├── ParallelExecutorTest.groovy
│   │   │   ├── AsyncResultTest.groovy
│   │   │   └── ResultAggregatorTest.groovy
│   │   ├── builders/
│   │   │   ├── JavaBuilderTest.groovy
│   │   │   ├── NodeBuilderTest.groovy
│   │   │   └── PythonBuilderTest.groovy
│   │   ├── scanners/
│   │   │   └── ScanResultCollectorTest.groovy
│   │   └── utils/
│   │       └── SandboxTest.groovy
│   └── jenkins/
│       └── pipelineSmokeTest.groovy         # 真实 Jenkins 上的 Smoke 测试
│
├── docs/
│   ├── design.md                            # 本文档
│   ├── architecture.md
│   ├── user-guide.md                        # 使用手册
│   ├── developer-guide.md                   # 二次开发指南
│   ├── api-reference.md                     # API 自动生成
│   ├── migration-guide.md                   # 旧 Jenkinsfile 迁移指南
│   └── adr/                                 # 架构决策记录
│       ├── 0001-package-naming.md
│       ├── 0002-async-scan-model.md
│       └── 0003-sandbox-safety.md
│
└── .github/
    └── workflows/
        ├── ci.yml                           # 库的 CI：lint + 单测 + Jenkins Pipeline
        └── release.yml                      # Tag 触发：发布新版本
```

---

## 4. 核心架构

### 4.1 分层模型

```
┌──────────────────────────────────────────────────────────────┐
│  Layer 0 : Jenkinsfile 业务方                                │
│            apex { ... }                                      │
├──────────────────────────────────────────────────────────────┤
│  Layer 1 : vars/  (全局 DSL)                                 │
│            apex.groovy / apexBuild.groovy / apexScan.groovy │
├──────────────────────────────────────────────────────────────┤
│  Layer 2 : Pipeline / Stage / Step  (组合模型)               │
│            Pipeline.groovy / Stage.groovy / Step.groovy      │
├──────────────────────────────────────────────────────────────┤
│  Layer 3 : Modules  (构建器 / 扫描器 / 上报器 / 通知器)       │
│            builders/ scanners/ reporters/ notifiers/         │
├──────────────────────────────────────────────────────────────┤
│  Layer 4 : Core  (并发 / 异步 / 重试 / 超时 / 上下文)        │
│            core/  config/  utils/                           │
├──────────────────────────────────────────────────────────────┤
│  Layer 5 : Jenkins Steps (sh / sh(script:[])/ readJSON 等)  │
└──────────────────────────────────────────────────────────────┘
```

### 4.2 组合模型

**Pipeline = Context + Stages[]**
**Stage = Name + Steps[] + Optional parallel/agent**
**Step = Action (Build / Scan / Report / Notify)**

```groovy
// 类图（简化）
Pipeline {
    PipelineContext context
    List<Stage> stages
}

Stage {
    String name
    StageType type        // SEQUENTIAL | PARALLEL | DYNAMIC
    List<Step> steps
}

Step {
    String name
    Closure action        // CPS-safe
    Map<String, Object> options
}
```

### 4.3 不可变上下文（PipelineContext）

`PipelineContext` 是跨 Stage 共享的"全局变量"容器，采用 **不可变对象 + 拷贝-改-写（copy-on-write）** 模式：

```groovy
@Immutable
class PipelineContext {
    String appName
    String branch
    String commitSha
    GlobalConfig global
    BuildConfig build
    ScanConfig scan
    Map<String, Object> userVars           // 业务自定义变量

    PipelineContext withConfig(String key, Object value) {
        // 返回新对象（immutable）
    }
}
```

业务方在 `Jenkinsfile` 中可写入 / 读取：

```groovy
apex {
    vars = [team: 'treasury', costCenter: 'CC1234']

    stages {
        stage('Build') {
            java { jdk = 21 }
        }
        stage('Security Scans') {
            parallel {
                sast { tool = 'sonarqube' }
                sca   { tool = 'owasp' }
                container { tool = 'trivy' }
            }
        }
    }
}
```

> **设计要点**：使用 `withConfig` 而不是 setter，保证 Sandbox 内不会出现"外部修改导致流水线状态错乱"的问题。

---

## 5. 全局变量（`vars/`）与 DSL 设计

### 5.1 顶层入口 `apex.groovy`

```groovy
// vars/apex.groovy
import com.hsbc.treasury.apex.ci.Pipeline
import com.hsbc.treasury.apex.ci.PipelineContext
import com.hsbc.treasury.apex.ci.config.GlobalConfig

def call(Closure body) {
    def ctx = PipelineContext.fromEnv(env)
    def pipeline = new Pipeline(ctx)
    body.delegate = pipeline
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body()
    pipeline.execute(script)
}
```

### 5.2 快速入口

| 全局变量 | 用途 | 示例 |
| --- | --- | --- |
| `apex` | 主 DSL | `apex { ... }` |
| `apexBuild` | 一步构建 | `apexBuild('java-maven')` |
| `apexScan` | 一步扫描 | `apexScan { sast(); sca() }` |
| `apexConfig` | 设置全局默认配置 | `apexConfig { registry = '...' }` |
| `apexContext` | 读取当前上下文 | `apexContext.branch` |
| `apexVersion` | 库版本信息 | `apexVersion()` |

### 5.3 DSL 风格（最终期望的 Jenkinsfile）

```groovy
@Library('apex-ci-library@main') _

apex {
    appName = 'apex-treasury-svc'

    vars = [
        team        : 'treasury',
        costCenter  : 'CC1234',
        dataClass   : 'CONFIDENTIAL'
    ]

    agent { label 'docker && linux' }

    environment {
        DOCKER_BUILDKIT = '1'
    }

    options {
        timeout(time: 30, unit: 'MINUTES')
        ansiColor('xterm')
        timestamps()
    }

    onInit  { ctx -> /* hook */ }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build & Test') {
            parallel {
                java {
                    jdk         = 21
                    buildTool   = 'maven'   // maven | gradle
                    goals       = ['clean verify']
                    skipTests   = false
                    onSuccess   { ctx -> echo "Java build OK: ${ctx.artifactId}" }
                }
                node {
                    nodeVersion = '20.x'
                    packageManager = 'pnpm'
                    commands = ['install --frozen-lockfile', 'test', 'build']
                }
                python {
                    pythonVersion = '3.12'
                    packageManager = 'poetry'
                    commands       = ['install', 'pytest']
                }
            }
        }

        stage('Security Scans') {
            parallel {
                sast     { tool = 'sonarqube'; qualityGate = true }
                sca      { tool = 'owasp';     failOn = 'HIGH' }
                secrets  { tool = 'gitleaks' }
                container{ tool = 'trivy';     failOn = 'CRITICAL' }
                iac      { tool = 'checkov';   enabled = fileExists('**/terraform/**') }
            }
            // 关键：scan() 立即返回 AsyncResult，不阻塞后续 stage
        }

        stage('Publish') {
            when { branch 'main' }
            steps {
                containerBuild {
                    image = "registry.hsbc/apex/${ctx.appName}"
                    tags  = [ctx.commitSha, 'latest']
                }
                containerPush { }
            }
        }
    }

    onSuccess { ctx -> slackSend(channel: '#apex-ci', message: "✅ ${ctx.appName} OK") }
    onFailure { ctx -> slackSend(channel: '#apex-ci', message: "❌ ${ctx.appName} failed") }
}
```

---

## 6. 模块化与 SPI

### 6.1 构建器（Builder）

```groovy
// builders/Builder.groovy
package com.hsbc.treasury.apex.ci.builders

interface Builder {
    String getLanguage()
    boolean detect(File projectDir)        // 静态能力描述
    void build(PipelineContext ctx, Closure config)   // 真正执行
}
```

注册机制（**避免**使用 `ServiceLoader`，因为 Jenkins Sandbox 不支持 ClassLoader 反射）：

```groovy
// builders/BuilderRegistry.groovy
class BuilderRegistry {
    private static final Map<String, Class> REGISTRY = [:]

    static {
        REGISTRY['java']   = JavaBuilder
        REGISTRY['node']   = NodeBuilder
        REGISTRY['python'] = PythonBuilder
        REGISTRY['go']     = GoBuilder
        REGISTRY['dotnet'] = DotnetBuilder
        REGISTRY['shell']  = ShellBuilder
    }

    static Builder resolve(String language) {
        def cls = REGISTRY[language]
        if (!cls) throw new ConfigException("Unknown builder: ${language}")
        return cls.getDeclaredConstructor().newInstance()
    }

    /** 自动检测（按优先级） */
    static String autoDetect(File projectDir) {
        if (new File(projectDir, 'pom.xml').exists()) return 'java'
        if (new File(projectDir, 'build.gradle').exists()) return 'java'
        if (new File(projectDir, 'package.json').exists()) return 'node'
        if (new File(projectDir, 'pyproject.toml').exists()) return 'python'
        if (new File(projectDir, 'go.mod').exists()) return 'go'
        if (new File(projectDir, '*.csproj').exists()) return 'dotnet'
        return 'shell'
    }
}
```

### 6.2 扫描器（Scanner）

```groovy
// scanners/Scanner.groovy
package com.hsbc.treasury.apex.ci.scanners

interface Scanner {
    String getType()                       // sast | sca | container | iac | secrets | license
    String getTool()                       // 具体工具：sonarqube, owasp, trivy...
    AsyncResult start(PipelineContext ctx) // 启动扫描，返回异步句柄
    ScanResult fetch(AsyncResult handle)   // 拉取结果
}
```

### 6.3 上报器 / 通知器

类似 SPI，注册到 `ReporterRegistry` / `NotifierRegistry`。

---

## 7. 并发与异步执行模型

### 7.1 并发（Parallel）

Jenkins 本身的 `parallel {}` 即"并发"，但容易出现：

- 节点资源争用
- 上下文污染
- 日志错位

**封装策略**：

```groovy
// core/ParallelExecutor.groovy
class ParallelExecutor {
    /**
     * 在 Jenkins pipeline 内并发执行多个 Step。
     * @param steps Map<String, Closure>  name -> step body
     * @param failFast 是否一个失败立即取消其他
     */
    static Map<String, Object> execute(
        def script,
        Map<String, Closure> steps,
        boolean failFast = true
    ) {
        def result = [:]
        def errorBag = []

        script.parallel steps.collectEntries { name, body ->
            [(name): {
                try {
                    result[name] = body.call()
                } catch (err) {
                    errorBag << [name: name, error: err]
                    if (failFast) throw err
                }
            }]
        }

        if (errorBag) {
            throw new ApexCIException("Parallel steps failed: ${errorBag.name.join(',')}")
        }
        return result
    }
}
```

**业务层**：

```groovy
stage('Build') {
    parallel {
        java   { ... }
        node   { ... }
        python { ... }
    }
}
```

### 7.2 异步结果（Async / Future）

针对**安全扫描**这类"启动后需要等待但又不希望阻塞主流程"的场景：

```
┌────────────┐   start(ctx)    ┌─────────────┐
│ Scan Step  │ ──────────────▶ │ 扫描服务    │
└────────────┘                 │  (异步)     │
     │  AsyncResult            └──────┬──────┘
     ▼                                │
┌────────────┐   get()/await()        │
│ Collector  │ ◀──────────────────────┘
└────────────┘
     │  ScanResult (合并 + 门禁判断)
     ▼
   Stage 'Collect Scan Results' (在最后阶段统一处理)
```

```groovy
// core/AsyncResult.groovy
package com.hsbc.treasury.apex.ci.core

class AsyncResult<T> implements Serializable {
    private static final long serialVersionUID = 1L

    String id
    String type                  // sast/sca/...
    String tool
    long startTime
    String state = 'PENDING'     // PENDING | RUNNING | COMPLETED | FAILED | TIMEOUT
    T payload

    /** 阻塞直到完成（Jenkins CPS 中通过 `timeout`+`waitUntil` 实现） */
    T await(int timeoutSeconds = 1800) {
        // ...
    }

    /** 非阻塞拉取 */
    Optional<T> tryGet() {
        // ...
    }
}
```

```groovy
// scanners/ScanResultCollector.groovy
class ScanResultCollector {
    List<AsyncResult> handles = []

    void register(AsyncResult h) { handles << h }

    List<ScanResult> collectAll(int timeoutSeconds = 1800) {
        def results = []
        handles.each { h ->
            results << h.await(timeoutSeconds)
        }
        return aggregate(results)
    }

    private List<ScanResult> aggregate(List<ScanResult> results) {
        // 合并各扫描器结果
        // 应用门禁策略
        // 返回聚合报告
    }
}
```

**使用**：

```groovy
stage('Security Scans') {
    script {
        def collector = apex.scan {
            sast     { tool = 'sonarqube' }
            sca      { tool = 'owasp';     failOn = 'HIGH' }
            secrets  { tool = 'gitleaks' }
            container{ tool = 'trivy' }
        }
        // 立即继续，不阻塞
    }
}

stage('Collect & Gate') {
    steps {
        script {
            def results = apex.collector.collectAll(timeout: 1800)
            apex.gate(results)   // 根据 failOn / qualityGate 决定成败
        }
    }
}
```

> **关键**：扫描的"启动"和"结果收集"被解耦到两个 Stage，**不会因为某个慢扫描拖累构建主链**。

### 7.3 CPS-safe 的等待模式

```groovy
T await(int timeoutSeconds) {
    def deadline = System.currentTimeMillis() + timeoutSeconds * 1000
    while (state == 'PENDING' || state == 'RUNNING') {
        if (System.currentTimeMillis() > deadline) {
            state = 'TIMEOUT'
            throw new ApexCIException("Async ${id} timeout")
        }
        // 必须 sleep 才能让 Jenkins 序列化推进
        script.sleep(5)   // 注意是 script.sleep，避免阻塞 CPS 引擎
        refresh()
    }
    return payload
}
```

---

## 8. 多语言构建抽象

### 8.1 公共接口

```groovy
abstract class AbstractBuilder implements Builder {
    protected Object script
    protected PipelineContext ctx

    void withScript(Object script) { this.script = script }
    void withContext(PipelineContext ctx) { this.ctx = ctx }

    abstract String getLanguage()
    abstract boolean detect(File projectDir)
    abstract void build(PipelineContext ctx, Closure config)
}
```

### 8.2 各语言支持矩阵

| 语言 | 工具链 | 检测文件 | 默认行为 |
| --- | --- | --- | --- |
| **Java** | Maven / Gradle | `pom.xml` / `build.gradle` | `clean verify` + 单测 + Jacoco |
| **Node** | npm / yarn / pnpm | `package.json` | `install --frozen-lockfile` + `test` + `build` |
| **Python** | poetry / pip / uv | `pyproject.toml` / `requirements.txt` | `install` + `pytest` + `ruff` |
| **Go** | go modules | `go.mod` | `go mod download` + `go test ./...` + `go build` |
| **.NET** | dotnet CLI | `*.csproj` / `*.sln` | `restore` + `test` + `publish` |
| **Shell** | make / 自定义 | 任意 | 兜底 |

### 8.3 示例：JavaBuilder

```groovy
class JavaBuilder extends AbstractBuilder {

    @Override
    String getLanguage() { 'java' }

    @Override
    boolean detect(File projectDir) {
        return new File(projectDir, 'pom.xml').exists() ||
               new File(projectDir, 'build.gradle').exists() ||
               new File(projectDir, 'build.gradle.kts').exists()
    }

    @Override
    void build(PipelineContext ctx, Closure config) {
        def cfg = new JavaBuildConfig().with(config)
        tool(name: "jdk-${cfg.jdk}", type: 'jdk')

        // sandbox-safe：参数全部展开为 list
        def mvnGoals = cfg.goals ?: ['clean', 'verify']

        if (new File('pom.xml').exists()) {
            withMaven(maven: cfg.mavenVersion) {
                sh(script: ['mvn'] + mvnGoals + ['-B', '-e'],
                   returnStatus: true)
            }
        } else if (new File('build.gradle').exists() ||
                   new File('build.gradle.kts').exists()) {
            sh(script: ['./gradlew'] + mvnGoals + ['--no-daemon', '-B'],
               returnStatus: true)
        }
    }
}
```

### 8.4 自动检测流水线

```groovy
stage('Auto Build') {
    steps {
        script {
            def lang = apex.detectLanguage()
            apex.build(lang) { /* 可选覆盖 */ }
        }
    }
}
```

> 业务方**零配置**即可运行：检测 → 选择 Builder → 执行默认 goal。

### 8.5 动态参数（Dynamic Build Parameters）

构建参数必须支持业务方**自由加减**，例如 Maven 编译时灵活加 `-pl xxx -am`、动态加 `-Dxxx`、动态切换 profile。

```groovy
// builders/JavaBuilder.groovy
class JavaBuildConfig {
    // —— 基础 ——
    String jdk              = '17'
    String buildTool        = 'maven'        // maven | gradle
    String mavenVersion     = '3.9.9'
    String gradleVersion    = '8.10'

    // —— 动态目标 ——
    List<String> goals      = ['clean', 'verify']
    List<String> profiles   = []              // -P
    List<String> properties = [:]             // 任意 -D 键值对

    // —— 动态参数（自由加减） ——
    List<String> cliOptions = []              // 任何额外 CLI 参数
    List<String> modules    = []              // -pl xxx -am
    boolean skipTests       = false
    boolean parallelBuild   = true            // -T 1C
    String threadCount      = '1C'

    /** 业务方用 closure 自由修改 */
    static JavaBuildConfig fromClosure(Closure body) {
        def cfg = new JavaBuildConfig()
        body.delegate = cfg
        body.resolveStrategy = Closure.DELEGATE_FIRST
        body()
        return cfg
    }
}
```

**DSL 用法（核心：Maven 任意参数动态加减）**：

```groovy
java {
    jdk = 21
    buildTool = 'maven'

    // 1) 直接覆盖 goal 列表
    goals = ['clean', 'package', '-DskipITs']

    // 2) 任意 -D 属性（加多少都行）
    properties = [
        'maven.javadoc.skip'      : 'true',
        'checkstyle.skip'         : 'false',
        'sonar.exclusions'        : '**/generated/**',
        'project.build.sourceEncoding': 'UTF-8'
    ]

    // 3) 自由追加 CLI（不会和框架冲突）
    cliOptions = [
        '-pl', 'core-svc,api-svc',
        '-am',
        '--batch-mode',
        '--update-snapshots',
        '-fae'                    // fail at end
    ]

    // 4) 条件性追加（业务方最常用）
    if (env.BRANCH_NAME == 'main') {
        profiles += ['release']
        cliOptions += ['-Dgpg.skip=false']
    } else {
        cliOptions += ['-Dgpg.skip=true']
    }

    // 5) 并行构建
    parallelBuild = true
    threadCount   = '4'

    // 6) 跳过某些模块
    modules = ['!legacy-module', '!examples/**']
}
```

**框架侧如何消费**（拼装为 sandbox-safe 数组）：

```groovy
List<String> assembleCommand(JavaBuildConfig cfg) {
    def cmd = ['mvn']
    cmd += cfg.goals                       // goals 整体
    if (cfg.skipTests)  cmd += ['-DskipTests']
    if (cfg.parallelBuild) cmd += ["-T${cfg.threadCount}"]
    if (cfg.profiles)   cmd += ['-P'] + cfg.profiles.flatten()
    cfg.properties.each { k, v -> cmd += ["-D${k}=${v}"] }
    if (cfg.cliOptions) cmd += cfg.cliOptions
    return cmd
}

// 使用
def cmd = assembleCommand(cfg)
sh(script: cmd, returnStatus: true, label: 'maven-build')
```

> **设计要点**：所有参数都是**集合**（`List` / `Map`），业务方在 `closure` 中按需 `.add` / `.remove`，不会影响默认行为。框架侧组装时统一展开为 `sh(script: [...])` 数组，保证 sandbox 安全。

### 8.6 通用动态参数模型 `DynamicParams`

为了让所有 Builder / Scanner 都复用"灵活加减参数"的能力，引入统一的 `DynamicParams`：

```groovy
// core/DynamicParams.groovy
package com.hsbc.treasury.apex.ci.core

class DynamicParams implements Serializable {
    private static final long serialVersionUID = 1L

    List<String> flags      = []          // --batch-mode  /  --update-snapshots
    Map<String,String> props = [:]        // -Dk=v
    List<String> positionals = []         // 位置参数
    Map<String,Object> extras = [:]        // 框架无关扩展

    void flag(String f)        { flags << f }
    void property(String k, String v) { props[k] = v }
    void positional(String p)  { positionals << p }
    void extra(String k, Object v) { extras[k] = v }
    void removeFlag(String f)  { flags.removeAll { it == f } }
    void removeProperty(String k) { props.remove(k) }
}
```

每个 Builder 内部都有 `params = new DynamicParams()`，DSL 里既支持字段访问也支持链式调用。

---

## 9. 容器镜像构建（Docker Build）

### 9.1 核心抽象

```groovy
// docker/DockerBuilder.groovy
package com.hsbc.treasury.apex.ci.docker

class DockerBuildConfig {
    String dockerfile       = 'Dockerfile'
    String context          = '.'
    List<String> buildArgs  = []           // --build-arg KEY=VAL
    List<String> secrets    = []           // --secret id=src,src=foo  (BuildKit)
    List<String> platforms  = ['linux/amd64']
    List<String> cacheFrom  = []           // --cache-from type=registry,ref=...
    String networkMode      = 'default'
    boolean noCache         = false
    int    timeoutMinutes   = 30
    DynamicParams params                  // 任何额外 --xxx
}
```

### 9.2 DSL 完整示例

```groovy
stage('Containerize') {
    steps {
        containerBuild {
            // 1) 基础
            dockerfile = 'docker/Dockerfile'
            context    = '.'

            // 2) 多架构（并发构建）
            platforms  = ['linux/amd64', 'linux/arm64']

            // 3) 任意 --build-arg（自由加减）
            buildArgs = [
                'NODE_VERSION=20.18.0',
                'JAR_FILE=target/*.jar',
                'PROXY=http://proxy.hsbc:8080'
            ]

            // 4) BuildKit Secret（不留在镜像层里）
            secrets = [
                'id=npmrc,src=.npmrc',
                'id=settings,src=settings.xml'
            ]

            // 5) 缓存
            cacheFrom = [
                "type=registry,ref=nexus.hsbc/apex/${ctx.appName}:buildcache"
            ]

            // 6) 标签（框架自动算 tag）
            tags = [
                ctx.commitSha,                 // sha
                ctx.semver ?: 'latest',       // semver
                env.BRANCH_NAME.replaceAll('/', '-')
            ]

            // 7) 任意额外参数（最终拼到 sh 数组里）
            params {
                flag('--progress=plain')
                flag('--ssh=default')
                property('BUILDKIT_INLINE_CACHE', '1')
            }

            // 8) 资源限制
            timeoutMinutes = 30
        }
    }
}
```

### 9.3 DockerBuilder 内部实现（沙箱安全）

```groovy
class DockerBuilder {
    void build(PipelineContext ctx, DockerBuildConfig cfg) {
        // 1) 启用 BuildKit
        script.withEnv([
            'DOCKER_BUILDKIT=1',
            'BUILDKIT_PROGRESS=plain'
        ]) {
            // 2) 多架构并发：调用 buildx
            def platforms = cfg.platforms.join(',')
            def tagArgs   = cfg.tags.collectMany { t -> ['--tag', "${cfg.imageName}:${t}"] }
            def buildArgArgs = cfg.buildArgs.collectMany { v -> ['--build-arg', v] }
            def secretArgs   = cfg.secrets.collectMany  { v -> ['--secret', v] }
            def cacheArgs    = cfg.cacheFrom.collectMany { v -> ['--cache-from', v] }

            def cmd = ['docker', 'buildx', 'build'] +
                      ['--platform', platforms] +
                      ['--push=false'] +         // 推送独立阶段
                      tagArgs +
                      buildArgArgs +
                      secretArgs +
                      cacheArgs +
                      (cfg.noCache ? ['--no-cache'] : []) +
                      ['-f', cfg.dockerfile] +
                      (cfg.params ? cfg.params.flags : []) +
                      cfg.context

            // 3) sandbox-safe 调用
            script.sh(script: cmd, returnStatus: true, label: 'docker-buildx')
        }
    }
}
```

### 9.4 缓存策略

| 策略 | 适用 | 配置 |
| --- | --- | --- |
| **Registry cache** | 跨 Pipeline 共享 | `--cache-from type=registry,ref=...` |
| **Inline cache** | 单次构建 | `--build-arg BUILDKIT_INLINE_CACHE=1` |
| **Local cache** | 自托管 Runner | `--cache-to type=local,dest=...` |
| **S3 cache** | 混合云 | `--cache-to type=s3,...` |

---

## 10. Nexus 制品发布

### 10.1 Nexus 客户端

```groovy
// artifact/NexusClient.groovy
package com.hsbc.treasury.apex.ci.artifact

class NexusClient implements Serializable {
    private static final long serialVersionUID = 1L

    String baseUrl          // https://nexus.hsbc
    String repository       // maven-releases / npm-hosted / pypi-releases / docker
    String credentialsId    // 'nexus-creds'
    String format           // maven2 | npm | pypi | docker | raw

    // —— HTTP 封装（沙箱安全：只走 httpRequest 步骤，不动态构造） ——
    Map<String,Object> get(String path) { ... }
    Map<String,Object> put(String path, byte[] body, String contentType) { ... }
    Map<String,Object> post(String path, Map json) { ... }

    // —— 制品查询 ——
    boolean exists(String group, String name, String version) { ... }
    Map<String,Object> getMetadata(String group, String name, String version) { ... }
}
```

### 10.2 DSL 入口 `apexNexus {}`

```groovy
// vars/apexNexus.groovy
def call(Closure body) {
    def cfg = new NexusConfig()
    body.delegate = cfg
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body()
    return new NexusClient(script, cfg)
}
```

### 10.3 Maven 制品发布

```groovy
stage('Publish to Nexus') {
    when { branch 'main' }
    steps {
        // —— 方式 A：直接 mvn deploy（最简单） ——
        java {
            jdk = 21
            goals = ['clean', 'deploy']
            properties = [
                'altDeploymentRepository': 'nexus::default::https://nexus.hsbc/repository/maven-releases/',
                'gpg.skip'                : 'false'
            ]
            cliOptions = [
                '--settings', 'ci/settings-nexus.xml'
            ]
        }

        // —— 方式 B：API 显式上传（推荐，可观测） ——
        apexNexus {
            baseUrl      = 'https://nexus.hsbc'
            repository   = 'maven-releases'
            credentialsId = 'nexus-deployer'
            format       = 'maven2'
        }.publishMaven(group: 'com.hsbc.treasury.apex',
                       artifactId: ctx.appName,
                       version: ctx.semver,
                       files: ['target/*.jar', 'target/*.pom', 'target/*.war'])
    }
}
```

### 10.4 npm 制品发布

```groovy
stage('Publish npm') {
    when { branch 'main' }
    steps {
        node {
            commands = ['ci', 'build', 'npm publish --registry=https://nexus.hsbc/repository/npm-hosted/']
        }
        // 或显式
        apexNexus {
            baseUrl    = 'https://nexus.hsbc'
            repository = 'npm-hosted'
            format     = 'npm'
        }.publishNpm(packageJson: 'package.json',
                     tarball: "dist/${ctx.appName}-${ctx.version}.tgz")
    }
}
```

### 10.5 Python 制品发布（PyPI 代理）

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

### 10.6 Docker 镜像推送到 Nexus

```groovy
stage('Push Image') {
    steps {
        containerPush {
            registry   = 'nexus.hsbc'
            repository = "apex/${ctx.appName}"
            tags       = [ctx.commitSha, ctx.semver ?: 'latest']
            // 复用 stage 9 中已 build 的镜像
        }
    }
}
```

**内部实现**：

```groovy
// docker/DockerPusher.groovy
class DockerPusher {
    void push(PipelineContext ctx, DockerPushConfig cfg) {
        // 1) 登录（凭据来自 Jenkins Credentials）
        script.withCredentials([
            script.usernamePassword(
                credentialsId: cfg.credentialsId,
                usernameVariable: 'NEXUS_USER',
                passwordVariable: 'NEXUS_PASS'
            )
        ]) {
            script.sh(script: [
                'docker', 'login', cfg.registry,
                '-u', script.env.NEXUS_USER,
                '-p', script.env.NEXUS_PASS
            ], returnStatus: true, label: 'docker-login')
        }

        // 2) 多 tag 推送
        def images = cfg.tags.collect { t -> "${cfg.registry}/${cfg.repository}:${t}" }
        // 3) 重打 tag（单架构）或 buildx --push（多架构）
        if (cfg.platforms.size() > 1) {
            // buildx push 已经合并到 build
        } else {
            def localTag = "${cfg.repository}:${cfg.tags[0]}"
            script.sh(script: ['docker', 'tag', localTag, images[0]], returnStatus: true)
            images.each { img ->
                script.sh(script: ['docker', 'push', img], returnStatus: true, label: "push-${img}")
            }
        }
    }
}
```

### 10.7 制品元数据

每次发布都会写入 `ArtifactMetadata`：

```groovy
class ArtifactMetadata {
    String name
    String version
    String commitSha
    String branch
    long   timestamp
    String url                  // Nexus UI
    Map<String,String> checksums // sha256
    String[] signatures         // .asc
}
```

并通过 `reporters/` 输出到 Jenkins Build Artifacts + 推送至 APEX 元数据库。

### 10.8 推送安全门禁

```groovy
nexusPush {
    gate {
        requireGreenBuild    = true           // 必须全绿
        requireSignedCommits = true           // GPG / SSH
        requireScanPass      = true           // SAST/SCA 必须无 High+
        allowedBranches      = ['main', 'release/*']
    }
}
```

> 若任一条件不满足，框架**主动拒绝推送并报警**，避免错把脏制品推上 Nexus。

---



## 11. 沙箱安全（Sandbox-safe）

Jenkins 推荐在 **Sandbox** 模式运行 Shared Library。本库遵循以下规则：

### 11.1 禁止

| 行为 | 替代方案 |
| --- | --- |
| `evaluate('something')` | 使用 `@NonCPS` 函数 + Closure 组合 |
| `new GroovyShell().evaluate(...)` | 不支持 |
| 字符串拼接 `sh "echo $x"` | 必须 `sh(script: ['echo', x])` |
| 动态 import / Class.forName | 静态 import；通过注册中心 |
| 反射 `obj.@field` | 显式 getter |
| 启动子线程（`Thread.start`） | 使用 `parallel` |
| 文件系统任意读写 | 受限工具：`utils/FileUtils` |

### 11.2 沙箱工具（`utils/Sandbox.groovy`）

```groovy
class Sandbox {
    /** 安全执行 shell 命令 */
    static int runShell(Object script, List<String> cmd, String label = null) {
        if (cmd == null || cmd.isEmpty()) {
            throw new ApexCIException("Empty command")
        }
        // 校验命令白名单（可配置）
        if (!Whitelist.isAllowed(cmd[0])) {
            throw new ApexCIException("Command not whitelisted: ${cmd[0]}")
        }
        return script.sh(script: cmd, label: label ?: cmd[0], returnStatus: true)
    }

    /** 安全读取文件 */
    static String readFile(Object script, String path, int maxBytes = 10 * 1024 * 1024) {
        if (!Whitelist.isPathAllowed(path)) {
            throw new ApexCIException("Path not allowed: ${path}")
        }
        return script.readFile(path)
    }
}
```

### 11.3 审计与 Lint

- **CI 强制 Lint**：`npm-groovy-lint` 规则集 + 自定义规则（禁止 `evaluate`、禁止裸 `sh "`、禁止反射）。
- **Pipeline Sandbox Replay**：每个 PR 在真实 Jenkins 上以 **Sandbox 模式** 跑一次 `pipelineSmokeTest`。

---

## 12. 配置管理

### 12.1 全局配置（`apexConfig{}`）

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
        docker = 'apex-docker-creds'
        sonar  = 'apex-sonar-token'
    }
    slack {
        channel = '#apex-ci'
    }
}
```

### 12.2 优先级（高 → 低）

1. 业务方 Jenkinsfile 中 `apex { ... }` 内显式配置
2. 库内置的 `apexConfig{}`
3. Jenkins 全局属性（Manage Jenkins → System Properties）
4. 库默认值

### 12.3 配置来源

- **代码**：`apexConfig{}` 块
- **环境变量**：`APEX_REGISTRY` 等
- **Jenkins 文件**：`apex-ci.yaml`（Pipeline 级配置文件，提交到业务仓）

---

## 13. 测试策略

### 13.1 单元测试

- 框架：**Spock**（`spock-core`）或 **Jenkins PipelineUnit**。
- 覆盖目标：核心模型（`Pipeline` / `Stage` / `Step` / `AsyncResult` / 各 Builder 配置解析逻辑）。
- Mock `sh` / `bat` / `readJSON` 等 CPS 步骤。

### 13.2 集成测试

- **`PipelineUnit`**：在 JVM 内模拟整条 Pipeline，验证 DSL 展开后的 step 序列。
- **Jenkinsfile Smoke Test**：在真实 Jenkins Sandbox 内跑 `apex { java { ... } }` 的最小用例。

### 13.3 端到端测试

- 库内提供 `test/jenkins/pipelineSmokeTest.groovy`，作为库的 `Jenkinsfile` 默认 stage。
- 验证：构建一个 `hello-java` / `hello-node` / `hello-python` 示例仓库，跑完整流程。

### 13.4 静态检查

- `npm-groovy-lint`（规则：`/config/groovylintrc.json`）
- 自定义规则：
  - `no-eval`
  - `no-raw-sh`
  - `no-reflection`
  - `require-immutable-context`

---

## 14. 发布与版本

### 14.1 版本策略（SemVer）

- `MAJOR`：破坏性 API 变更
- `MINOR`：向后兼容的新功能 / 新 Builder / 新 Scanner
- `PATCH`：Bug 修复

### 14.2 兼容性矩阵

| 库版本 | Jenkins LTS | JDK | Pipeline Plugin |
| --- | --- | --- | --- |
| 1.0.x | 2.426.x+ | 11 / 17 / 21 | workflow-aggregator ≥ 596 |

### 14.3 Tag 流程

1. PR → `main`：CI 全绿后自动合并
2. 维护者打 tag：`vX.Y.Z`（含 `CHANGELOG.md` 同步）
3. `.github/workflows/release.yml` 发布 GitHub Release + Jenkins Update Center 元数据

### 14.4 业务方引用

```groovy
@Library('apex-ci-library@1.0.0') _    // 固定版本（推荐）
@Library('apex-ci-library@main') _     // 主干（仅开发）
```

---

## 15. 安全与合规

- 库**自身**仓库中**禁止**提交任何凭据。
- 所有 Scanner 默认在**只读模式**下运行。
- 容器构建阶段强制 `DOCKER_BUILDKIT=1` + 多阶段构建。
- 上传产物到 APEX 内部 Artifactory，**禁止**推到公网。
- OIDC / Vault 凭据：通过 Jenkins Credentials Plugin 注入。
- 审计日志：所有 `sh` 命令通过 `Logger.groovy` 记录到 `build.log`。

---

## 16. 可观测性

- **构建指标**：构建时长、测试通过率、扫描漏洞数、镜像大小 → 推送 Prometheus Pushgateway
- **链路追踪**：每个 Step 携带 `traceId`，由 OTel SDK 写入
- **日志**：JSON 格式（`build.log.json`），便于 ELK 消费
- **报告**：通过 `reporters/` 模块生成 HTML / JUnit XML / Allure 报告

---

## 17. 实施路线图

| 阶段 | 内容 | 交付 |
| --- | --- | --- |
| **P0 - 骨架** | 仓库结构 + Pipeline/Stage/Step 模型 + `apex.groovy` + Lint | v0.1.0 |
| **P1 - 核心构建** | `JavaBuilder` + `NodeBuilder` + 动态参数 `DynamicParams` | v0.3.0 |
| **P2 - Docker & Nexus** | `DockerBuilder` (BuildKit/multi-arch) + `NexusClient` + `ArtifactPublisher` | v0.5.0 |
| **P3 - 扫描** | `SastScanner` + `ScaScanner` + `AsyncResult` 异步模型 | v0.6.0 |
| **P4 - 多语言** | `PythonBuilder` + `GoBuilder` + `DotnetBuilder` | v0.7.0 |
| **P5 - 高级** | 自动检测 + 模板系统 + 报告聚合 | v0.9.0 |
| **P6 - GA** | 文档 + 培训 + 试点项目迁移 | v1.0.0 |

---

## 18. 风险与缓解

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| Sandbox 限制导致部分功能无法实现 | 阻塞 P2 | 提供 `apexCI.unsafe` 关闭开关；优先用纯 Groovy |
| 多语言构建参数差异大 | Builder 维护成本高 | Builder 注册 + 单元测试覆盖；优先支持 Top 5 语言 |
| 异步扫描结果丢失 | 门禁失效 | 强约束：所有 AsyncResult 必须有 timeout；CI 阶段兜底回收 |
| 库版本碎片化 | 业务方升级困难 | 强制 SemVer + LTS 分支（`1.x`、`2.x` 长期维护） |
| Jenkins 升级破坏 API | 流水线停摆 | 兼容性矩阵自动化测试（`docs/compatibility-matrix.md`） |

---

## 19. 参考

- Jenkins Shared Libraries 官方文档：<https://www.jenkins.io/doc/book/pipeline/shared-libraries/>
- Pipeline Development Tools：<https://www.jenkins.io/doc/book/pipeline/development/>
- PipelineUnit：<https://github.com/jenkinsci/pipeline-unit>
- 内部 APEX 平台文档：`confluence://apex/ci/standards`

---

**审批 / 评审**

| 角色 | 姓名 | 日期 |
| --- | --- | --- |
| 架构 Owner | | |
| 安全 Owner | | |
| 平台 Owner | | |
