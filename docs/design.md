# apex-ci-library 设计文档（v2.0 轻量版）

> **项目名称**：`apex-ci-library`
> **包名（Package）**：`com.hsbc.treasury.apex.ci`
> **类型**：Jenkins Shared Library
> **当前版本**：v2.0.0（**Lightweight / 轻量版**）— 2026-06-20
> **历史版本**：v1.0.0 Draft（2026-06-19）— 已被取代，详见文末 §18

> ## 版本说明
>
> 本文档描述 **v2.0 轻量版** 的架构与设计决策。
> 详细使用请阅读 [user-guide.md](./user-guide.md) 与 [developer-guide.md](./developer-guide.md)。
> v1.0 → v2.0 的演进动机与变更见 §18（v1.0 历史参考）。

---

## 目录

1. [背景与目标](#1-背景与目标)
2. [设计原则（v2.0）](#2-设计原则v20)
3. [仓库结构](#3-仓库结构)
4. [核心架构](#4-核心架构)
5. [全局变量（`vars/`）与 DSL 设计](#5-全局变量vars-与-dsl-设计)
6. [模块化与扩展点](#6-模块化与扩展点)
7. [并发与扫描门禁](#7-并发与扫描门禁)
8. [动态参数](#8-动态参数)
9. [重试策略](#9-重试策略)
10. [沙箱安全](#10-沙箱安全)
11. [Docker 与 Nexus](#11-docker-与-nexus)
12. [测试策略](#12-测试策略)
13. [配置与通知](#13-配置与通知)
14. [错误模型](#14-错误模型)
15. [发布与兼容性](#15-发布与兼容性)
16. [文档组织](#16-文档组织)
17. [附录：典型 Pipeline 示例](#17-附录典型-pipeline-示例)
18. [v1.0 历史参考与差异对比](#18-v10-历史参考与差异对比)

---

## 1. 背景与目标

HSBC Treasury APEX 平台涉及大量多语言（Java / Node / Python / Go / Shell 等）微服务的持续集成。现有 CI 流水线存在以下问题：

- 各业务线重复编写类似的 Jenkinsfile，模板分散、版本不一。
- 安全扫描（SAST / SCA / Container / IaC / DAST）接入方式不统一，结果散落，难以聚合与门禁。
- 不同语言构建工具链（Maven / npm / Poetry / Go modules）参数化方式各异。
- 全局变量、凭据、超时、重试等横切关注点缺乏统一抽象。
- 现有第三方库对沙箱、CPS 转换的兼容参差不齐，业务方易踩坑。

**apex-ci-library v2.0** 的目标：

1. **一份可复用的 CI 库**，作为 Jenkins Shared Library 在所有 APEX 流水线中统一接入。
2. **轻量 DSL**：复用 Jenkins 原生 `stage` / `parallel` / `sh` / `node`，**不**发明新流程抽象。
3. **封装外部交换**：仅在"与外部系统交互"处封装（构建、扫描、Docker、发布、通知、配置、重试）。
4. **模块化**：Builders、Scanners、Reporters、Notifiers 独立开发、测试、发布。
5. **沙箱安全**：所有 `sh` 用数组形式，无反射、无动态 import、无自建线程。
6. **并发扫描 + 门禁**：走 Jenkins 原生 `parallel`，异常隔离，配置化门禁。
7. **可测**：核心类用 `MockScript` 直接单测，无须 PipelineUnit。

---

## 2. 设计原则（v2.0）

| 原则 | 描述 |
| --- | --- |
| **Native First** | 流程编排（stage / parallel / matrix / when）一律走 Jenkins 原生 DSL，业务方看文档即可。 |
| **External Adapter** | 库只封装"与外部交换"的步骤：Maven / npm / Trivy / Docker / Nexus / 邮件。 |
| **Lightweight Context** | `PipelineContext` 仅为可序列化的轻量容器，attrs 可写。 |
| **Sandbox First** | 所有 `sh` 走 `sh(script: [...], label: ...)` 数组形式；禁止字符串拼接。 |
| **Composable Closures** | DSL 用闭包 + `DELEGATE_FIRST` 拼接；`apexScan { sast{} sca{} }` 风格。 |
| **No Re-invented Threading** | 所有并发走 `script.parallel(branches)`；不引入 `Executors` / `Thread`。 |
| **Testable in Isolation** | 核心类 CPS-aware 但能 mock；`MockScript` 替代 PipelineUnit。 |
| **Fail-Fast Validation** | 命令拼装、参数解析、必填字段都在调用前完成校验。 |
| **Serializable by Default** | 所有公开类 `implements Serializable` 并显式 `serialVersionUID`（CPS 要求）。 |

---

## 3. 仓库结构

```
apex-ci-library/
├── Jenkinsfile                              # 库自检（原生 pipeline 块）
├── README.md                                # 入口：快速开始 + 文档导航
├── build.sh / build.bat                     # groovyc + JUnit 编译与运行
├── LICENSE                                  # MIT
│
├── vars/                                    # 全局 DSL 入口（轻量）
│   ├── apex.groovy                          # 注入共享 PipelineContext
│   ├── apexBuild.groovy                     # 多语言构建（java/node/python/go/shell）
│   ├── apexScan.groovy                      # 并发扫描（Jenkins 原生 parallel）
│   ├── apexDocker.groovy                    # 镜像构建与推送
│   ├── apexPublish.groovy                   # Nexus 制品发布
│   ├── apexRetry.groovy                     # 线性/指数退避/条件重试
│   ├── apexParams.groovy                    # DynamicParams 工厂
│   ├── apexConfig.groovy                    # YAML/Properties/JSON 解析
│   └── apexNotify.groovy                    # 邮件通知
│
├── src/com/hsbc/treasury/apex/ci/           # 主源码
│   ├── core/                                # 基础设施
│   │   ├── PipelineContext.groovy           # 轻量共享上下文
│   │   ├── PipelineContextBuilder.groovy     # 链式构造器
│   │   ├── Sleeper.groovy                   # 抽象睡眠（测试可注入 mock）
│   │   ├── JenkinsSleeper.groovy            # 走 script.sleep 的实现
│   │   ├── NoOpSleeper.groovy               # 不睡眠（单测）
│   │   ├── Retry.groovy                     # 重试策略
│   │   └── DynamicParams.groovy             # 动态 CLI 参数
│   │
│   ├── builders/                            # 多语言构建
│   │   ├── AbstractBuilder.groovy           # 抽象基类
│   │   ├── JavaBuilder.groovy               # Maven / Gradle
│   │   ├── NodeBuilder.groovy               # npm / yarn / pnpm
│   │   ├── PythonBuilder.groovy             # poetry / pip / uv
│   │   ├── GoBuilder.groovy                 # go modules
│   │   ├── ShellBuilder.groovy              # 兜底
│   │   └── BuilderFactory.groovy            # 注册表 + autoDetect
│   │
│   ├── scanners/                            # 扫描
│   │   ├── ScanRunner.groovy                # 走 Jenkins 原生 parallel
│   │   └── ScanResult.groovy                # 结果数据
│   │
│   ├── docker/                              # Docker 镜像
│   │   ├── DockerBuilder.groovy             # buildx 构建
│   │   ├── DockerPusher.groovy              # push 到任意 registry
│   │   └── DockerBuildConfig.groovy         # 镜像配置
│   │
│   ├── artifact/                            # 制品
│   │   ├── NexusClient.groovy               # Nexus 命令拼装
│   │   └── ArtifactPublisher.groovy         # maven/npm/pypi/raw 发布
│   │
│   ├── reporters/                           # 上报
│   │   └── ConsoleReporter.groovy           # 扫描汇总
│   │
│   ├── notifiers/                           # 通知
│   │   └── EmailNotifier.groovy             # 邮件
│   │
│   ├── config/                              # 配置
│   │   └── LibraryConfig.groovy             # YAML/Properties/JSON 解析
│   │
│   ├── utils/                               # 工具
│   │   ├── Sandbox.groovy                   # 命令白名单
│   │   ├── Util.groovy                      # 杂项
│   │   └── ProjectDetector.groovy           # 语言自动检测
│   │
│   └── errors/                              # 错误模型
│       ├── ApexCIException.groovy           # 基类
│       ├── BuildException.groovy
│       ├── ScanException.groovy
│       └── ConfigException.groovy
│
├── test/com/hsbc/treasury/apex/ci/          # 单元 + 集成测试
│   ├── integration/
│   │   └── LightweightDslTest.groovy        # 16 用例：覆盖并行/扫描/重试/门禁
│   ├── core/                                # PipelineContext/Retry/DynamicParams
│   ├── builders/                            # Java/Node/Python/Shell/BuilderFactory
│   ├── scanners/                            # ScanRunner
│   ├── docker/                              # DockerBuilder/Pusher
│   ├── artifact/                            # NexusClient/ArtifactPublisher
│   ├── config/                              # LibraryConfig
│   ├── utils/                               # Sandbox/Util
│   └── MockScript.groovy                    # 模拟 Jenkins script 代理
│
├── docker/test-env/                         # 集成测试环境
│   ├── docker-compose.yml                   # Nexus3 + Registry + Jenkins
│   ├── jenkins/                             # Dockerfile + JCasC + init scripts
│   ├── Jenkinsfile-modules                  # 端到端验证 Pipeline
│   └── test-it.sh                           # 一键测试
│
└── docs/                                    # 文档
    ├── design.md                            # 本文档
    ├── user-guide.md                        # 用户手册
    └── developer-guide.md                   # 二次开发指南
```

> **轻量化原则**：
> - 不使用 Gradle / Maven，直接 `groovyc` + `JUnitCore`。
> - `src/` 直接作为发布产物被 Jenkins 加载。
> - `vars/` 全部用 `def call(...)` 定义全局变量，**不**用 `@Field` / 自定义类。

---

## 4. 核心架构

### 4.1 分层模型

```
┌──────────────────────────────────────────────────────────────┐
│  Layer 0 : Jenkinsfile 业务方                                │
│            node { stage { ... } }                            │
├──────────────────────────────────────────────────────────────┤
│  Layer 1 : vars/  (全局 DSL 入口)                            │
│            apex / apexBuild / apexScan / apexDocker / ...   │
├──────────────────────────────────────────────────────────────┤
│  Layer 2 : External Adapters (封装外部交换)                  │
│            builders/ scanners/ docker/ artifact/             │
│            reporters/ notifiers/                             │
├──────────────────────────────────────────────────────────────┤
│  Layer 3 : Core (上下文 / 重试 / 动态参数)                   │
│            core/  config/  utils/  errors/                   │
├──────────────────────────────────────────────────────────────┤
│  Layer 4 : Jenkins 原生 Steps                                │
│            sh / parallel / timeout / stage / withCredentials │
└──────────────────────────────────────────────────────────────┘
```

> **关键差异（v1.0 → v2.0）**：
> - v1.0 多了 Layer 2.5：`Pipeline` / `Stage` / `Step` 自定义抽象层。
> - v2.0 **完全省略**这一层——业务方直接用 Jenkins 原生 stage / parallel / sh。

### 4.2 数据流

```
Jenkinsfile
   │
   ▼
apex { ctx = ... }                  ← 注入 PipelineContext 到 script.binding.apexCtx
   │
   ├─→ stage('Build') { apexBuild('java') { ... } }
   │       │  ① ApexCtxBuilder.executor(language).execute(ctx, body)
   │       │  ② JavaBuilder.parseConfig(body) → JavaBuildConfig
   │       │  ③ Sandbox.runShell(ctx, ['mvn', ...], label)
   │       ▼
   │     sh(script: ['mvn', 'clean', 'verify'], label: 'maven-build')
   │
   ├─→ stage('Security') { def r = apexScan { sast {} sca {} }; r.assertPassed() }
   │       │  ① ScanRunner 收集 entries
   │       │  ② script.parallel(branches)，每分支包 try/catch + timeout
   │       │  ③ ConsoleReporter 输出汇总
   │       │  ④ r.assertPassed() 检查 failOn 门禁
   │       ▼
   │     并发 3+ 个 sh，超时 30min（可配），异常隔离
   │
   └─→ stage('Publish') { apexDocker.buildAndPush(...) }
           │  ① DockerBuilder.build(ctx, cfg)
           │  ② sh(script: ['docker', 'buildx', 'build', ...], label)
           │  ③ DockerPusher.push(ctx, ref, creds)
           ▼
         原生 docker buildx + docker push
```

### 4.3 轻量上下文（PipelineContext）

`PipelineContext` 是跨闭包共享数据的容器：

```groovy
class PipelineContext implements Serializable {
    private static final long serialVersionUID = 1L

    final Object script                  // Jenkins script 代理（必备）
    final String workDir                 // script.pwd() 缓存
    final Map<String, String> env        // 不可变视图
    final Map<String, Object> params     // 不可变视图
    final Map<String, Object> attrs      // ConcurrentHashMap（业务方可读可写）
    final Sleeper sleeper                // 默认 NoOpSleeper
    final String nodeLabel
    final long startedAt

    void setAttr(String k, Object v)    { attrs.put(k, v) }
    Object getAttr(String k)             { return attrs.get(k) }
    Object getAttr(String k, Object d)   { return attrs.getOrDefault(k, d) }
    boolean hasAttr(String k)            { return attrs.containsKey(k) }

    PipelineContext withEnv(Map<String, String> more) { /* 合并 env */ }
    void log(String message)             { script?.echo(message) }
}
```

**与 v1.0 的差异**：

| 维度 | v1.0 | v2.0 |
| --- | --- | --- |
| 可变性 | 不可变（`@Immutable`） | 可变 attrs + withEnv 派生 |
| 全局配置 | 强类型 `GlobalConfig` / `BuildConfig` / `ScanConfig` | 移除，业务方用 `apexConfig {}` 按需取 |
| 注入方式 | `apex.groovy` 创建完整对象 | `apex.groovy` 仅注入到 `script.binding` |
| 字段数 | 30+ | 8（核心） |

### 4.4 设计动机

为什么 v2.0 要"轻量"？

1. **CPS 兼容性**：v1.0 的 `Pipeline` / `Stage` / `Step` 三层抽象与 Jenkins CPS 转换器冲突，闭包变量捕获容易出 `NotSerializableException`。
2. **学习成本**：业务方需要学一套"apex DSL"才能写 Jenkinsfile，Jenkins 升级时易破坏。
3. **线程模型冲突**：v1.0 的 `ParallelExecutor` / `AsyncResult` 模拟 Future，但 Jenkins 已有原生 `parallel`，重复造轮子。
4. **测试难**：v1.0 的强类型对象在 PipelineUnit 下难以 mock。
5. **调试难**：v1.0 业务方报障时，需要追踪三层自定义抽象，Jenkins 日志与自定义日志混杂。

v2.0 的轻量版避免了上述所有问题——业务方看的就是 Jenkins 原生 DSL，库只在"封装"处出现。

---

## 5. 全局变量（`vars/`）与 DSL 设计

### 5.1 顶层入口 `apex.groovy`

```groovy
import com.hsbc.treasury.apex.ci.core.PipelineContext

def call(Closure body) {
    Object script = this
    PipelineContext ctx = (script.binding?.hasVariable('apexCtx') ? script.apexCtx :
        PipelineContext.builder().script(script).build())
    script.binding?.setVariable('apexCtx', ctx)
    body.delegate = ctx
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body()
    return ctx
}
```

**职责**：仅注入共享 `PipelineContext`，**不**做 stage 包装 / 调度。零开销、幂等。

### 5.2 DSL 入口一览

| 全局变量 | 签名 | 职责 |
| --- | --- | --- |
| `apex` | `call(Closure)` | 注入 `PipelineContext` |
| `apexBuild` | `call(lang, [opts], Closure)` | 多语言构建（内部走 `sh`） |
| `apexScan` | `call(Closure)` | 并发扫描（内部走 `parallel` + `timeout`） |
| `apexDocker` | `call(image, Closure)` / `push(image, creds)` / `buildAndPush(...)` | 镜像构建与推送 |
| `apexPublish` | `call(baseUrl, repo, format, Closure)` | Nexus 制品发布 |
| `apexRetry` | `linear / exponential / until` | 重试 |
| `apexParams` | `call()` / `call(Closure)` | DynamicParams 工厂 |
| `apexConfig` | `fromYaml / fromProperties / fromJson` | 配置解析 |
| `apexNotify` | `call(Map, Closure)` | 邮件通知 |

### 5.3 DSL 设计原则

1. **闭包 delegate + `DELEGATE_FIRST`**：业务方写 `sast {}` 时可直接访问 delegate 的方法。
2. **数组形式 `sh`**：每个 DSL 入口内部必须用 `sh(script: [...], label: ...)`。
3. **不发明新流程原语**：stage / parallel / timeout / withCredentials 一律走原生。
4. **幂等**：多次调用同一入口不产生副作用（ctx 复用、scan runner 可重用）。

### 5.4 业务方典型 Jenkinsfile

```groovy
@Library('apex-ci-library@2.0') _

pipeline {
    agent any
    options { timeout(time: 30, unit: 'MINUTES') }

    stages {
        stage('Build') {
            steps {
                apexBuild('java') {
                    jdk = 17
                    goals = ['clean', 'verify']
                    params { flag('--batch-mode'); property('maven.javadoc.skip', 'true') }
                }
            }
        }
        stage('Tests') {
            steps {
                parallel 'unit':  { sh './mvnw test -Dtest=Unit' },
                          'integ': { sh './mvnw test -Dtest=Integ' }
            }
        }
        stage('Security') {
            steps {
                def r = apexScan {
                    sast    { sh 'sonar-scanner -Dsonar.qualitygate.wait=true' }
                    sca     { sh 'snyk test --json' }
                    container('app:1.0.0') { sh 'trivy image app:1.0.0' }
                }
                r.failOn = ['high', 'critical']
                r.assertPassed()
            }
        }
        stage('Image') {
            steps {
                apexDocker('registry.local/app:1.0.0') {
                    dockerfile = 'docker/Dockerfile'
                    platforms  = ['linux/amd64', 'linux/arm64']
                }
                apexDocker.push('registry.local/app:1.0.0', 'registry-creds')
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

    post {
        always {
            apexNotify(to: ['dev@local'], subject: "Build #${env.BUILD_NUMBER}",
                       body: "Status: ${currentBuild.currentResult}")
        }
    }
}
```

---

## 6. 模块化与扩展点

### 6.1 构建器（Builder）

```groovy
abstract class AbstractBuilder implements Serializable {
    abstract String getLanguage()
    abstract boolean detect(File projectDir)
    abstract Object parseConfig(Closure body)
    abstract Object execute(PipelineContext ctx, Closure body, Map opts = [:])

    protected List<String> mergeDynamicParams(List<String> base, DynamicParams params) { ... }
    protected List<String> platformAdapt(List<String> cmd, PipelineContext ctx) { ... }
}
```

注册表（v2.0 不用反射，直接 `new`）：

```groovy
class BuilderFactory {
    private static final Map<String, AbstractBuilder> REGISTRY = new LinkedHashMap<>()
    static {
        REGISTRY['java']   = new JavaBuilder()
        REGISTRY['node']   = new NodeBuilder()
        REGISTRY['python'] = new PythonBuilder()
        REGISTRY['go']     = new GoBuilder()
        REGISTRY['shell']  = new ShellBuilder()
    }
    static AbstractBuilder of(String language) { ... }
    static String autoDetect(File projectDir) { ... }
}
```

**新增 Builder**：实现 `AbstractBuilder` → 注册到 `BuilderFactory` → 业务方写 `apexBuild('xxx')`。

### 6.2 扫描器（Scanner）

v2.0 **不再**有独立的 `Scanner` 类。扫描是 `ScanRunner` 的闭包：

```groovy
class ScanRunner {
    void sast(String name = 'sast', Closure body)    { add('sast',      name, body) }
    void sca(String name = 'sca', Closure body)      { add('sca',       name, body) }
    void container(String name = 'container', Closure body) { add('container', name, body) }
    void generic(String name, Closure body)          { add('generic',   name, body) }

    Map<String, ScanResult> run() { /* 走 script.parallel */ }
    void assertPassed(Map<String, ScanResult> results = null) { /* 门禁 */ }
}
```

**新增扫描类型**：

- 简单场景：直接 `generic('secrets') { sh 'gitleaks ...' }` 即可。
- 复杂场景：新建 `vars/<tool>.groovy` 封装扫描命令 + 报告解析，返回 `ScanResult`。

### 6.3 上报器 / 通知器

- `ConsoleReporter`：扫描后输出汇总（已内置）。
- `EmailNotifier`：邮件（`apexNotify`）。
- 业务方可自行扩展 `vars/<channel>Notify.groovy`（Slack / Teams / Webhook）。

### 6.4 扩展点速查

| 扩展点 | 操作 |
| --- | --- |
| 新语言 | 新建 `xxx/Builder.groovy` + 注册到 `BuilderFactory` |
| 新扫描类型 | `apexScan { generic('xxx') { ... } }` 即可 |
| 新通知渠道 | 新建 `vars/<x>Notify.groovy` |
| 新报告格式 | 新建 `reporters/XxxReporter.groovy` |
| 新全局变量 | 新建 `vars/<name>.groovy` |
| 新 Artifact 仓库 | 新建 `artifact/XxxClient.groovy` |

---

## 7. 并发与扫描门禁

### 7.1 原生 `parallel` 是唯一并发入口

```groovy
Map<String, ScanResult> run() {
    if (entries.isEmpty()) return [:]
    if (entries.size() == 1) { /* 同步走避免 CPS 副作用 */ }
    Map<String, Closure> branches = [:]
    for (Map<String, Object> e : entries) {
        String taskName = "${e['type']}-${e['name']}".toString()
        branches[taskName] = { ->
            try {
                script.timeout(time: timeoutMin, unit: 'MINUTES') {
                    Object out = e['body'].call()
                    return (out instanceof ScanResult) ? out :
                        new ScanResult(scanner: taskName, status: 'OK', summary: 'no-op')
                }
            } catch (Throwable t) {
                return new ScanResult(scanner: taskName, status: 'FAILED',
                                      summary: t.message, error: t)
            }
        }
    }
    Map<String, Object> raw = script.parallel(branches)
    Map<String, ScanResult> results = [:]
    raw.each { k, v -> results[k as String] = v as ScanResult }
    new ConsoleReporter().reportScan(ctx, results.values() as List)
    return results
}
```

**关键设计**：

- **走 `script.parallel`**：不引入 `Executors` / `Thread`，沙箱安全。
- **单分支不调 parallel**：`entries.size() == 1` 时同步执行，避免 CPS 副作用。
- **每分支独立 `timeout`**：默认 30min，可配。
- **每分支独立 `try/catch`**：异常隔离，不让其他分支失败。
- **不创建 stage**：`run()` 自己不 `script.stage(...)`，由调用方决定 stage 命名。

### 7.2 门禁判断（`assertPassed`）

```groovy
void assertPassed(Map<String, ScanResult> results = null) {
    Map<String, ScanResult> r = (results != null) ? results :
        (this.lastResults ?: run())
    List<String> violations = []
    r.each { String name, ScanResult res ->
        if (res.status == 'FAILED' || res.status == 'TIMEOUT') {
            violations << "${name}:${res.status}:${res.summary}".toString()
        }
        for (String sev : failOn) {
            int n = res.getCount(sev)
            if (n > 0) violations << "${name}:${sev}=${n}".toString()
        }
    }
    if (!violations.isEmpty()) {
        throw new ScanException("Scanner gate failed: " + violations.join('; '))
    }
}
```

**门禁策略**：

```groovy
r.failOn = ['high']               // 默认：任意 high 失败
r.failOn = ['critical', 'high']   // critical 或 high 失败
r.failOn = []                     // 关闭门禁（仅记录）
r.failOn = ['medium', 'high']     // 严格模式
```

**状态门禁**：状态为 `FAILED` / `TIMEOUT` 的分支**永远**计为违规（与 `failOn` 无关）。

### 7.3 单分支优化

`ScanRunner.run()` 检测到 `entries.size() == 1` 时**不调 parallel**——直接同步执行：

- 避免无意义的 parallel 开销；
- 避免单分支时 parallel 的 CPS 副作用（如某些 Jenkins 版本在单分支 parallel 时阻塞）；
- 单测时 `MockScript` 容易断言。

### 7.4 等待扫描的语义

v2.0 明确：**`apexScan{}` 是同步阻塞**的（内部走 parallel，parallel 阻塞）。业务方要做"启动后等会儿"直接用 Jenkins 原生 stage 串行：

```groovy
stage('Build')      { apexBuild('java') { jdk = 17 } }   // ① Build
stage('Security')   {                                     // ② 等 Build 完成后
    def r = apexScan { sast {} sca {} container('app:1.0.0') {} }
    r.assertPassed()                                       // ③ 门禁
}
stage('Publish')    { apexPublish(...) }                   // ④ 等 Security 完成后
```

不需要"异步 + 收集"的两阶段模式——Jenkins 原生 stage 已经天然按顺序串行。

---

## 8. 动态参数

`DynamicParams` 解决"Maven 编译参数灵活加减"的需求：

```groovy
class DynamicParams implements Serializable {
    private final List<String> flags = []
    private final Map<String, String> props = [:]
    private final List<String> positionals = []
    private final Map<String, Object> extra = [:]

    DynamicParams flag(String f)            // 长选项
    DynamicParams property(String k, String v)  // -Dk=v
    DynamicParams positional(String p)     // 末尾参数
    DynamicParams extra(String k, Object v)  // 业务自定义

    // 链式
    DynamicParams addFlag(String f)
    DynamicParams addProperty(String k, String v)
    DynamicParams addPositional(String p)

    // 删除
    boolean removeFlag(String f)
    boolean removeProperty(String k)

    // 拷贝
    DynamicParams copyWith() / copyWith(Closure body)

    // 渲染
    List<String> toArgList()
}
```

**Builder 集成**（以 `JavaBuilder` 为例）：

```groovy
protected List<String> mergeDynamicParams(List<String> base, DynamicParams params) {
    List<String> out = new ArrayList<>(base)
    params?.flags.each { out << it }
    params?.props.each { k, v -> out << "-D${k}=${v}".toString() }
    params?.positionals.each { out << it }
    params?.extra.each { k, v -> out << it?.toString() }   // 业务自定义
    return out
}
```

**业务方使用**：

```groovy
apexBuild('java') {
    jdk = 17
    goals = ['clean', 'package']
    params {
        flag('--batch-mode')
        property('maven.javadoc.skip', 'true')
        positional('install')
    }
}

// 调试时临时加：
def p = apexParams().copyWith()
p.addFlag('--debug')
p.removeFlag('--batch-mode')
```

---

## 9. 重试策略

### 9.1 `Retry` 抽象

```groovy
class Retry implements Serializable {
    int maxAttempts = 1
    long initialDelayMs = 0L
    double backoffMultiplier = 1.0
    int maxDelayMs = 60000
    List<Class<? extends Throwable>> retryOn = [Exception]
    Sleeper sleeper

    static Retry none()                                    { ... }
    static Retry linear(int n, long delayMs)               { ... }
    static Retry exponential(int n, long initialMs, double mult) { ... }

    static <T> T execute(Retry retry, Closure<T> body)     { ... }
}
```

### 9.2 三种策略

| 策略 | 场景 | 示例 |
| --- | --- | --- |
| `linear(n, ms)` | 错误持续时间短，间隔固定 | `apexRetry.linear(3, 1000) { sh 'npm install' }` |
| `exponential(n, ms, mult)` | 服务重启 / 缓存预热，指数增长 | `apexRetry.exponential(5, 500, 2.0) { sh 'mvn deploy' }` |
| `until(n, ms) { cond }` | 等待外部条件 | `apexRetry.until(10, 2000) { sh 'check-status.sh'; return ready }` |

### 9.3 与扫描门禁的组合

```groovy
stage('Security') {
    def r = apexScan {
        // 内部：scanner 内部独立重试
        sast { apexRetry.linear(2, 1000) { sh 'sonar-scanner ...' } }
        sca  { apexRetry.linear(2, 1000) { sh 'snyk test' } }
    }
    // 扫描层门禁
    r.failOn = ['high']
    r.assertPassed()
}

stage('Publish') {
    // 整个 publish 阶段再重试
    apexRetry.exponential(3, 2000, 2.0) {
        withCredentials([...]) { sh 'mvn deploy -DskipTests' }
    }
}
```

**理由**：scanner 内部的瞬时错误不影响其他 scanner；publish 失败不会污染扫描结果；重试策略独立调参。

### 9.4 异常分类

```groovy
// 默认重试所有 Exception
new Retry(maxAttempts: 3).execute { ... }

// 只重试可恢复的异常
new Retry(
    maxAttempts: 5,
    retryOn: [java.net.SocketTimeoutException, java.io.IOException]
).execute { ... }

// 凭据错误（401/403）应不重试——让业务方快速发现
```

---

## 10. 沙箱安全

### 10.1 原则

Jenkins 沙箱审计所有未在白名单的方法调用。任何 `evaluate` / 反射 / 动态 import / 任意文件读写都会抛 `RejectedAccessException`。

### 10.2 必须遵守的规则

| 规则 | 反例（被拒） | 正例（通过） |
| --- | --- | --- |
| `sh` 命令 | `sh "mvn $goal"` | `sh(script: ['mvn', goal])` |
| `evaluate` | `evaluate("return $x")` | 静态 `if / else` |
| 反射 | `obj.'field'` | `obj.getField()` |
| 动态 import | `this.class.classLoader.loadClass('Foo')` | 静态 import + Factory |
| 多线程 | `new Thread({...}).start()` | `script.parallel {}` |
| 任意文件读写 | `new File('/etc/x').text` | `script.readFile` / `script.writeFile` |
| `Thread.sleep` | `Thread.sleep(1000)` | `script.sleep(1)` |

### 10.3 `Sandbox.runShell` —— 命令白名单

```groovy
class Sandbox {
    private static List<String> COMMAND_WHITELIST = [
        'mvn', 'gradle', 'npm', 'yarn', 'pnpm', 'poetry',
        'docker', 'kubectl', 'helm', 'git', 'curl', 'jq',
        'python', 'python3', 'go', 'dotnet', 'cargo', 'rustc'
    ]

    static int runShell(Object script, List<String> cmd, String label = null) {
        if (!cmd) throw new ApexCIException("Empty command")
        if (!COMMAND_WHITELIST.contains(cmd[0])) {
            throw new ApexCIException("Command not whitelisted: ${cmd[0]}")
        }
        return script.sh(script: cmd, label: label ?: cmd[0], returnStatus: true)
    }
}
```

> **注意**：`Sandbox.runShell` 仅做白名单审计，**不**执行沙箱——沙箱是 Jenkins 全局机制。

### 10.4 白名单申请

业务方需要新增命令时：

1. 在 `Sandbox.COMMAND_WHITELIST` 中添加
2. 提交 PR 说明用途
3. CI 跑 `bash docker/test-env/test-it.sh` 验证沙箱回放
4. 库管理员审批合并

### 10.5 调试沙箱

```bash
# Jenkins master
tail -f /var/log/jenkins/script-approval.log | grep -i apex
```

临时关闭（仅本地开发）：

```groovy
@Library('apex-ci-library@main') _   // 默认沙箱
// @Library('apex-ci-library@main') _; library 'apex-ci-library' /* 关闭沙箱 */
```

> **生产强制** 沙箱模式。

---

## 11. Docker 与 Nexus

### 11.1 Docker 构建

```groovy
stage('Build Image') {
    apexDocker('ghcr.io/acme/app:1.0.0') {
        dockerfile = 'docker/Dockerfile'
        context    = '.'
        platforms  = ['linux/amd64', 'linux/arm64']
        buildArgs  = ['NODE_VERSION=20', 'JAR_FILE=target/*.jar']
        secrets    = ['id=npmrc,src=.npmrc']
        cacheFrom  = ["type=registry,ref=registry.local/app:buildcache"]
        cacheTo    = ["type=registry,ref=registry.local/app:buildcache,mode=max"]
        noCache    = false
    }
}
```

内部走 `sh(script: ['docker', 'buildx', 'build', ...], label: 'docker-build:...')`。

### 11.2 Docker 推送

```groovy
stage('Push') {
    apexDocker.push('ghcr.io/acme/app:1.0.0', 'ghcr-creds')
}
```

**凭据注入**（库不主动管理）：

```groovy
stage('Push') {
    withCredentials([usernamePassword(credentialsId: 'registry-creds',
                                       usernameVariable: 'REG_USER',
                                       passwordVariable: 'REG_PASS')]) {
        apexDocker.buildAndPush('registry.local/app:1.0.0')
    }
}
```

### 11.3 Nexus 发布

```groovy
stage('Publish Maven') {
    apexPublish('https://nexus.local', 'maven-releases', 'maven2') {
        maven(['-DskipTests'], 'nexus-deployer') {
            sh 'mvn deploy --batch-mode'
        }
    }
}

stage('Publish npm') {
    apexPublish('https://nexus.local', 'npm-private', 'npm') {
        npm { sh 'npm ci && npm run build'; sh 'npm publish' }
    }
}

stage('Publish PyPI') {
    apexPublish('https://nexus.local', 'pypi-hosted', 'pypi') {
        pypi('dist') { sh 'python -m build' }
    }
}

stage('Upload Tarball') {
    apexPublish('https://nexus.local', 'raw-hosted', 'raw') {
        raw('app/1.0.0/release.tgz', 'release.tgz', 'application/gzip')
    }
}
```

### 11.4 外部服务不稳定处理

```groovy
stage('Publish (unstable external)') {
    apexRetry.exponential(5, 1000, 2.0) {
        apexPublish('https://nexus.local', 'maven-releases', 'maven2') {
            maven(['-DskipTests'], 'nexus-deployer') {
                sh 'mvn deploy --batch-mode'
            }
        }
    }
}
```

---

## 12. 测试策略

### 12.1 测试金字塔

```
        ┌────────────────────┐
        │   E2E / Sandbox    │  ← 真实 Jenkins 跑 Jenkinsfile-modules
        ├────────────────────┤
        │ 集成（MockScript） │  ← LightweightDslTest 覆盖并行/扫描/重试
        ├────────────────────┤
        │    单元测试        │  ← JUnit 4 + groovyc
        └────────────────────┘
```

### 12.2 单元测试

```bash
./build.sh
# 编译 src/ → build/classes/main
# 编译 test/ → build/classes/test
# 运行 JUnitCore all *Test.groovy
```

### 12.3 `MockScript`

```groovy
class MockScript {
    List<Map> shCalls = []
    List<String> echos = []
    List<Map> parallels = []

    Object sh(Map args) { shCalls << args; return 0 }
    void echo(String msg) { echos << msg }
    Object parallel(Map blocks) {
        parallels << [blocks: blocks]
        return blocks.collectEntries { k, v -> [(k): v.call()] }
    }
    Object timeout(Map args, Closure body) { body.call() }
    String pwd() { '/tmp/workspace' }
}
```

### 12.4 集成测试：LightweightDslTest

16 用例覆盖：
- `scanRunner_usesNativeParallelForMultipleBranches`
- `scanRunner_singleBranchDoesNotUseParallel`
- `scanRunner_isolatesExceptionsAcrossBranches`
- `scanRunner_blocksUntilAllBranchesComplete`
- `scanRunner_assertPassedRunsGateAfterAllScansDone`
- `scanRunner_othersPassEvenWhenOneFails`
- `scanRunner_emitsReporterOutput`
- `retry_recoversFromTransientExternalServiceError`
- `retry_givesUpAfterMaxAttempts`
- `retry_exponentialBackoffActuallyWaits`
- `javaBuilder_assemblesMavenCommandArray`
- `dynamicParams_freeAdditionAndRemoval`
- `ctx_attrsSurviveAcrossStages`
- `javaBuilder_missingBuildToolFailsFast`
- ...

### 12.5 真实 Jenkins 集成

```bash
docker compose -f docker/test-env/docker-compose.yml up -d
bash docker/test-env/test-it.sh
```

`test-it.sh` 触发 `Jenkinsfile-modules`（覆盖 apexBuild / apexScan / apexRetry / apexConfig 等所有模块），断言 `BUILD SUCCESS`。

---

## 13. 配置与通知

### 13.1 配置解析

```groovy
def cfg = apexConfig.fromYaml(readFile('apex-ci.yaml'))
def cfg2 = apexConfig.fromProperties(readFile('apex-ci.properties'))
def cfg3 = apexConfig.fromJson(readFile('apex-ci.json'))

// 闭包式
def cfg = apexConfig {
    fromYaml text: readFile('apex-ci.yaml')
}

// 取值
def app = cfg.getString('app.name', 'default-app')
def jdk = cfg.getInt('java.jdk', 17)
def platforms = cfg.getList('docker.platforms', ['linux/amd64'])
```

**优先级**：

```
环境变量 > 仓库 apex-ci.yaml > 业务方 Jenkinsfile 内 apexConfig > 内置默认
```

### 13.2 邮件通知

```groovy
post {
    always {
        apexNotify(to: ['dev@local'],
                   subject: "Build #${env.BUILD_NUMBER}",
                   body: "Status: ${currentBuild.currentResult}")
    }
}
```

---

## 14. 错误模型

```groovy
class ApexCIException extends RuntimeException {
    String code
    List<String> details
    ApexCIException(String msg, Throwable cause = null, String code = null, List<String> details = null) { ... }
}

class BuildException extends ApexCIException { ... }
class ScanException extends ApexCIException { ... }
class ConfigException extends ApexCIException { ... }
```

**异常分类**：

| 异常 | 触发条件 |
| --- | --- |
| `ApexCIException` | 通用：未指定 language、参数缺失、命令白名单拦截等 |
| `BuildException` | 构建阶段错误：buildx 失败、参数解析失败 |
| `ScanException` | 扫描门禁失败：`Scanner gate failed: sast:high=2; ...` |
| `ConfigException` | 配置解析失败：YAML 格式错误、键缺失 |

---

## 15. 发布与兼容性

### 15.1 版本

遵循 [SemVer 2.0.0](https://semver.org/)。

| 库版本 | Jenkins LTS | JDK | Pipeline Plugin |
| --- | --- | --- | --- |
| 2.0.x | 2.470.x+ | 17 / 21 | workflow-aggregator ≥ 650 |
| 1.x | 2.426.x+ | 11 / 17 / 21 | workflow-aggregator ≥ 596 |

### 15.2 发布流程

1. 切到 `main`，确保最新
2. `./build.sh`（全绿）
3. `bash docker/test-env/test-it.sh`（沙箱回放）
4. 更新 `CHANGELOG.md`（如有）
5. `git tag -a v2.0.0 -m "v2.0.0"`
6. `git push origin v2.0.0`
7. 通知 `#apex-ci-announce`

### 15.3 兼容性策略

- **偶数 MAJOR 长期维护**（≥ 18 个月）
- **奇数 MAJOR 仅维护至下个偶数发布**
- **破坏性变更** 必须新开 MAJOR
- **新增全局变量** 不算破坏性
- **修改现有入口签名** 算破坏性（需 deprecate → 移除 → 2 个 MAJOR 周期）

---

## 16. 文档组织

| 文档 | 目标读者 | 内容 |
| --- | --- | --- |
| `docs/design.md` | 架构师 / 库作者 | 架构、决策、模块划分、v1→v2 演进 |
| `docs/user-guide.md` | Jenkinsfile 编写者 | 快速上手、API、典型模式、迁移 |
| `docs/developer-guide.md` | 库维护者 / 贡献者 | 扩展点、测试、发布、沙箱、调错 |
| `README.md` | 全员 | 概览、能力、链接 |

---

## 17. 附录：典型 Pipeline 示例

### 17.1 最小流水线

```groovy
@Library('apex-ci-library@2.0') _

node {
    stage('Build') { apexBuild('java') { jdk = 17 } }
    stage('Test')  { sh 'mvn test' }
}
```

### 17.2 完整流水线（PR 门禁）

```groovy
@Library('apex-ci-library@2.0') _

pipeline {
    agent any
    options { timeout(time: 30, unit: 'MINUTES'); ansiColor('xterm') }
    stages {
        stage('Build')    { steps { apexBuild('java') { jdk = 17; goals = ['clean', 'verify'] } } }
        stage('Lint')     { steps { sh 'npm run lint' } }
        stage('Tests')    { steps { sh 'mvn -B test' } }
        stage('Security') { steps {
            def r = apexScan {
                sast    { sh 'sonar-scanner -Dsonar.qualitygate.wait=true' }
                sca     { sh 'snyk test --json' }
                container('app:1.0.0') { sh 'trivy image app:1.0.0' }
                generic('secrets') { sh 'gitleaks detect --source .' }
            }
            r.failOn = ['high', 'critical']
            r.assertPassed()
        } }
    }
}
```

### 17.3 Monorepo 多语言并行

```groovy
node {
    stage('Build & Test') {
        parallel(
            'core':   { apexBuild('java')   { jdk = 17; goals = ['-pl', 'core', 'clean', 'verify'] } },
            'api':    { apexBuild('java')   { jdk = 17; goals = ['-pl', 'api',  'clean', 'verify'] } },
            'portal': { apexBuild('node')   { commands = ['ci', 'test', 'build'] } },
            'ml':     { apexBuild('python') { commands = ['pytest', 'build'] } }
        )
    }
}
```

### 17.4 外部服务不稳定处理

```groovy
stage('Publish (with retry)') {
    apexRetry.exponential(5, 1000, 2.0) {
        apexPublish('https://nexus.local', 'maven-releases', 'maven2') {
            maven(['-DskipTests'], 'nexus-deployer') {
                sh 'mvn deploy --batch-mode'
            }
        }
    }
}
```

---

## 18. v1.0 历史参考与差异对比

### 18.1 触发原因

v1.0 在真实 Jenkins 集成测试中暴露：

1. **CPS 兼容性问题**：`Pipeline` / `Stage` / `Step` 三层自定义抽象与 Jenkins CPS 转换器冲突，业务方写闭包时容易踩坑（`@NonCPS` 误用、closure 变量捕获异常等）。
2. **冗余线程模型**：`AsyncResult` / `ResultAggregator` 模拟 Future 模式，但 Jenkins 原生 `parallel` 已天然支持并发 + 异常隔离，徒增复杂度。
3. **DSL 学习成本**：业务方需要学一套"apex DSL"才能用，Jenkinsfile 与官方文档脱节，IDE 跳转差。
4. **测试难**：v1.0 的强类型对象在 PipelineUnit 下难以 mock。
5. **调试难**：业务方报障时，需要追踪三层自定义抽象。

### 18.2 模块变更

| 类别 | v1.0 | v2.0 | 变化 |
| --- | --- | --- | --- |
| 流程编排 | 自定义 `Pipeline{ stages{} }` | Jenkins 原生 `node { stage { ... } }` | 移除自定义层 |
| 并发 | 自建线程池 + AsyncResult | Jenkins 原生 `parallel(branches)` | 移除 AsyncResult |
| 异常隔离 | `try/catch` + ResultAggregator | 每个 parallel branch 内置 `try/catch` | 简化 |
| 异步 | Future 风格 await/tryGet | 移除（parallel 阻塞即可） | 移除 |
| 扫描 | `SastScanner` / `ScaScanner` 继承 `AbstractScanner` | 直接用 `apexScan { sast { ... } }` 闭包 | 简化 |
| 上下文 | 不可变 `PipelineContext` + withConfig | 可变 attrs + withEnv | 简化 |
| Builder | 实现 `Step` 接口 | `execute(ctx, body, opts)` 一步完成 | 简化 |
| 配置 | `PipelineConfig` / `BuildConfig` 强类型 | 纯 `apexConfig` 解析 + map | 简化 |
| 上报 | `HtmlReporter` / `JunitReporter` / `SummaryReporter` | 仅保留 `ConsoleReporter` | 精简 |
| 通知 | `SlackNotifier` / `EmailNotifier` / `TeamsNotifier` | 仅保留 `EmailNotifier` | 精简 |
| 模板 | `resources/templates/*.tpl` | 移除（业务方自管） | 移除 |

### 18.3 保留与简化

| 模块 | v1.0 | v2.0 |
| --- | --- | --- |
| `PipelineContext` | 不可变、强类型 | 轻量容器、attrs 可写 |
| `Retry` / `Sleeper` | 已实现 | 保留，新增 `JenkinsSleeper` |
| `DynamicParams` | 已实现 | 保留 |
| `Sandbox` | 已实现 | 保留 |
| `LibraryConfig` | 已实现 | 保留 |
| `DockerBuilder` / `DockerPusher` | 已实现 | 保留 |
| `NexusClient` / `ArtifactPublisher` | 已实现 | 保留 |
| `EmailNotifier` | 已实现 | 保留 |

### 18.4 新增

- **`ScanRunner`**：替代 `ScannerCollector` + `SastScanner` / `ScaScanner` / `ContainerScanner`，走 Jenkins 原生 `parallel`，支持 `sast / sca / container / generic` 四种注册方法。
- **`ConsoleReporter`**：替代分散的日志逻辑，扫描完成后统一输出汇总。
- **`Sleeper` / `JenkinsSleeper` / `NoOpSleeper`**：抽象睡眠，便于单测注入 mock。

### 18.5 API 差异

| 旧 API（v1.x） | 新 API（v2.0） | 备注 |
| --- | --- | --- |
| `apex { stages { stage { java { } } } }` | `node { stage { apexBuild('java') { } } }` | 走原生 |
| `apex.startScan {}` / `apex.collectScans()` | `apexScan { ... }` 合并 | 同步 + 门禁 |
| `apex.gate(results, policy: 'high+')` | `runner.assertPassed()` | 设置 `failOn` |
| `ScannerRegistry` | 移除 | 闭包组合替代 |
| `apex.detectLanguage()` | `BuilderFactory.autoDetect(...)` | 自动检测 |
| `apexNexus {}` | `apexPublish(...)` | 同名 |
| `apexContext.vars.team` | `apexCtx.getAttr('team')` | attrs 命名 |
| 自建线程池并发 | Jenkins 原生 `parallel` | 沙箱安全 |

### 18.6 收益

- **更短**：业务方 Jenkinsfile 减少 30%~50% 行数
- **更稳**：少一处 CPS 转换，Jenkins 升级不易破坏
- **更兼容**：与官方文档 1:1 对应
- **更可读**：Jenkinsfile 像普通 Groovy
- **更可测**：核心类直接用 `MockScript` 测，无需 PipelineUnit
- **更可调试**：业务方报障时，问题在原生日志中已能定位

### 18.7 迁移步骤

1. **替换入口**：`apex { ... }` → `node { ... }` 或保留 `apex { ... }` 仅注入 ctx。
2. **替换 stage**：`stages { stage { java { jdk = 21 } } }` → `stage('Build') { apexBuild('java') { jdk = 17 } }`。
3. **替换扫描**：`apex.startScan { sast { ... } }` → `apexScan { sast { ... } }`。
4. **替换门禁**：`apex.gate(results, ...)` → `runner.assertPassed()`。
5. **跑测试**：`bash build.sh` + `bash docker/test-env/test-it.sh` 验证。

---

## 19. 参考

- Jenkins Shared Libraries：<https://www.jenkins.io/doc/book/pipeline/shared-libraries/>
- Pipeline Development Tools：<https://www.jenkins.io/doc/book/pipeline/development/>
- Pipeline Syntax：<https://www.jenkins.io/doc/book/pipeline/syntax/>
- JUnit 4：<https://junit.org/junit4/>
- 内部 APEX 平台文档：`confluence://apex/ci/standards`
