# apex-ci-library 二次开发指南（轻量版）

> **包名**：`com.hsbc.treasury.apex.ci`
> **版本**：v2.0.0（Lightweight）
> **目标读者**：参与本库开发 / 扩展的工程师、CI 平台维护者
> **最后更新**：2026-06-20

---

## 目录

1. [开发环境搭建](#1-开发环境搭建)
2. [项目结构](#2-项目结构)
3. [核心模型详解（轻量版）](#3-核心模型详解轻量版)
4. [扩展：新增 Builder](#4-扩展新增-builder)
5. [扩展：新增 Scanner](#5-扩展新增-scanner)
6. [扩展：新增全局变量（vars/）](#6-扩展新增全局变量vars)
7. [并发、并行与异常隔离实现](#7-并发并行与异常隔离实现)
8. [外部服务不稳定处理（重试与门禁）](#8-外部服务不稳定处理重试与门禁)
9. [沙箱安全（Sandbox-safe）](#9-沙箱安全sandbox-safe)
10. [测试策略](#10-测试策略)
11. [发布流程](#11-发布流程)
12. [调试与排错](#12-调试与排错)
13. [代码规范与 Lint](#13-代码规范与-lint)
14. [从 v1.x 迁移](#14-从-v1x-迁移)

---

## 1. 开发环境搭建

### 1.1 必备工具

| 工具 | 版本 | 用途 |
| --- | --- | --- |
| JDK | 17 / 21 | Groovy / Java 编译 |
| Groovy | 4.0.x | Library 语言（自带编译器，无需 Gradle） |
| JUnit | 4.12 | 单元测试 |
| Hamcrest | 1.3 | 断言库 |
| Jenkins | 2.426.x LTS | 本地集成测试（推荐 Docker） |
| Docker | 24+ | buildx / 集成环境 |
| Nexus3 | 3.x | 集成测试用制品库 |
| Registry | v2 | 集成测试用镜像库 |

> **轻量化原则**：本仓库**不依赖 Gradle / Maven**，直接用 `groovyc` 编译。`build.sh` / `build.bat` 是独立的编译脚本，避免在沙箱 Jenkins 上跑构建时还要解决 Gradle 依赖网络问题。

### 1.2 推荐 IDE

- **IntelliJ IDEA Ultimate**（自带 Jenkins / Groovy 插件）
- VS Code + `Groovy Lint` 扩展

### 1.3 本地启动 Jenkins（集成测试）

```bash
docker compose -f docker/test-env/docker-compose.yml up -d
# 等 nexus、registry、jenkins 都 healthy
bash docker/test-env/test-it.sh
```

### 1.4 本地构建 + 单测

```bash
# Linux / macOS / Git-Bash
./build.sh

# Windows 原生 cmd
build.bat
```

> 这两个脚本会自动编译 `src/` 和 `test/`，用 `JUnitCore` 跑所有 `*Test.groovy` 类。

挂载本库到本地 Jenkins：

```bash
docker cp . jenkins-dev:/var/jenkins_home/jobs/apex-ci-library/workspace/
```

在 Jenkins 中配置 Shared Library：

> Manage Jenkins → System → Global Pipeline Libraries → Add
> - Name: `apex-ci-library`
> - Default version: `main`
> - Source: `Modern SCM` → Git → 本库仓库

---

## 2. 项目结构

### 2.1 目录约定

```
apex-ci-library/
├── src/                                  # 主源码（与发布产物一致）
│   └── com/hsbc/treasury/apex/ci/
│       ├── core/                         # PipelineContext / Retry / DynamicParams / Sleeper
│       ├── builders/                     # AbstractBuilder + 各语言实现 + Factory
│       ├── scanners/                     # ScanRunner / ScanResult
│       ├── docker/                       # DockerBuilder / DockerPusher
│       ├── artifact/                     # NexusClient / ArtifactPublisher
│       ├── reporters/                    # ConsoleReporter
│       ├── notifiers/                    # EmailNotifier
│       ├── config/                       # LibraryConfig（YAML/Properties/JSON）
│       ├── utils/                        # Sandbox / Util / ProjectDetector
│       └── errors/                       # ApexCIException / BuildException / ScanException
│
├── test/                                 # 单元 + 集成测试
│   └── com/hsbc/treasury/apex/ci/
│       ├── integration/                  # 轻量级 DSL 集成测试
│       └── ...
│
├── vars/                                 # 全局 DSL（apex / apexBuild / apexScan / ...）
│
├── docker/test-env/                      # 集成测试 Docker Compose
├── docs/                                 # design.md / user-guide.md / developer-guide.md
├── Jenkinsfile                           # 库的自检（被 Jenkinsfile-modules 替代为更全面测试）
├── build.sh / build.bat                  # groovyc 编译 + JUnit
└── README.md
```

> **轻量化原则**：
> - 不使用 `build.gradle` / `gradlew`，避免在 CI 容器内拉取 Gradle 依赖。
> - `src/` 直接作为发布产物被 Jenkins 加载，无需 IDE 友好的"双源目录"。
> - 编译产物直接落到 `out/` 即可，不需要 `build/libs/*.jar`。

### 2.2 build.sh / build.bat 关键约定

| 项 | 路径 | 说明 |
| --- | --- | --- |
| 主源码 | `src/` | 唯一来源 |
| 测试 | `test/` | 唯一来源 |
| 全局变量 | `vars/` | 由 Jenkins 直接读取，不编译 |
| 编译输出 | `out/` | 调试用，发布时随仓库一起发即可 |
| 单测运行器 | `JUnitCore` | 标准 JDK 即可，无需 surefire |

---

## 3. 核心模型详解（轻量版）

> **设计原则**：库**不发明**新流程抽象。
> - 流程编排（stage / parallel / when / matrix）一律走 Jenkins 原生 DSL。
> - 库只负责：注入共享上下文、解析配置、组装外部命令、并发执行扫描与门禁、重试。
> - 这样业务方的 Jenkinsfile 与官方文档 1:1 对应，IDE 跳转、CPS 转换都更稳定。

### 3.1 `PipelineContext` — 共享上下文

`PipelineContext` 是一个**轻量级容器**，主要解决"在多个 vars 闭包之间共享 script、env、attrs"。

```groovy
package com.hsbc.treasury.apex.ci.core

class PipelineContext implements Serializable {
    private static final long serialVersionUID = 1L

    final Object script                  // Jenkins script 代理
    final String workDir                 // script.pwd() 缓存
    final Map<String, String> env        // 不可变视图
    final Map<String, Object> params     // 不可变视图
    final Map<String, Object> attrs      // ConcurrentHashMap（业务方可读可写）
    final Sleeper sleeper                // 默认 NoOpSleeper，测试可注入 JenkinsSleeper
    final String nodeLabel
    final long startedAt

    void setAttr(String k, Object v)   { attrs.put(k, v) }
    Object getAttr(String k)            { return attrs.get(k) }
    Object getAttr(String k, Object d)  { return attrs.getOrDefault(k, d) }
    boolean hasAttr(String k)           { return attrs.containsKey(k) }

    PipelineContext withEnv(Map<String, String> more) { /* 合并 env 返回新 ctx */ }
    void log(String message)           { script?.echo(message) }
}
```

**关键设计**：

- `script` 是**唯一代理点**——所有需要走 `sh` / `echo` / `parallel` 的代码都从 ctx 拿 script。
- `env` / `params` 是**只读视图**（`Collections.unmodifiableMap`），业务方修改时用 `withEnv(...)` 派生新 ctx，避免误改。
- `attrs` 是**唯一可写 map**——业务方用它跨 stage 传值。
- `sleeper` 抽象：默认 `NoOpSleeper`（不睡眠），测试用 `JenkinsSleeper(script)`（走 `script.sleep`），单测可注入 mock。

**使用模式**：

```groovy
// 入口 vars/apex.groovy：
PipelineContext ctx = (script.binding?.hasVariable('apexCtx') ? script.apexCtx :
    PipelineContext.builder().script(script).build())
script.binding?.setVariable('apexCtx', ctx)
```

### 3.2 `apex.groovy` — 上下文注入器

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

**特点**：
- 零开销（不做 stage 包装、不调度 steps）
- 不强制使用——Jenkinsfile 简单时可不写 `apex{}`
- 多次调用幂等：第二次发现 `binding.apexCtx` 存在就复用

### 3.3 `AbstractBuilder` — 构建器抽象

```groovy
abstract class AbstractBuilder implements Serializable {
    private static final long serialVersionUID = 1L

    abstract String getLanguage()
    abstract boolean detect(File projectDir)
    abstract Object parseConfig(Closure body)
    abstract Object execute(PipelineContext ctx, Closure body, Map opts = [:])

    /** 合并 DynamicParams 到基础命令末尾 */
    protected List<String> mergeDynamicParams(List<String> base, DynamicParams params) {
        List<String> out = new ArrayList<>(base)
        params?.flags.each { out << it }
        params?.props.each { k, v -> out << "-D${k}=${v}".toString() }
        params?.positionals.each { out << it }
        return out
    }

    /** Windows 平台自动加 .cmd 后缀 */
    protected List<String> platformAdapt(List<String> cmd, PipelineContext ctx) { ... }
}
```

**关键变化（vs v1.x）**：

- **不再实现 Step 接口**：没有 `run()`、不参与自定义调度。
- **execute 直接返回**：由 Builder 内部走 `script.sh(script: [...])` 执行命令。
- **子类职责更清晰**：解析 Config → 拼装 cmd → 调 sh。

### 3.4 `DynamicParams` — 动态参数容器

详见 [user-guide.md §5](./user-guide.md#5-动态参数-apexparams)。

简而言之：
- `flag('--batch-mode')`：追加长选项
- `property('maven.javadoc.skip', 'true')`：追加 `-Dk=v`
- `positional('clean')`：追加到末尾
- `extra(k, v)`：业务自定义，由 Builder 决定如何用

支持链式（`addFlag().addProperty().addPositional()`）和拷贝（`copyWith { ... }`）。

### 3.5 `ScanRunner` — 并发扫描运行器

```groovy
class ScanRunner implements Serializable {
    private static final long serialVersionUID = 1L

    Object script
    PipelineContext ctx
    List<String> failOn = ['high']
    long timeoutMin = 30L
    boolean failFast = true

    private final List<Map<String, Object>> entries = []   // [type, name, body]

    void sast(String name = 'sast', Closure body)            { add('sast',      name, body) }
    void sca(String name = 'sca', Closure body)              { add('sca',       name, body) }
    void container(String name = 'container', Closure body)  { add('container', name, body) }
    void generic(String name, Closure body)                  { add('generic',   name, body) }

    int getScannerCount()

    /** 走 Jenkins 原生 parallel 执行；不自动包 stage */
    Map<String, ScanResult> run()

    /** 门禁判断：throw ApexCIException if any failOn severity hit */
    void assertPassed(Map<String, ScanResult> results = null)
}
```

**关键设计**：

- **走原生 parallel**：库把每个 scanner 包成闭包，调用 `script.parallel(branches)`，沙箱安全。
- **单分支不调 parallel**：`entries.size() == 1` 时直接执行，避免无意义的 parallel 开销。
- **异常隔离**：每个 branch 用 try/catch 包住，抛异常时转成 `ScanResult(status='FAILED')`。
- **自动 timeout**：每个 branch 用 `script.timeout(time: timeoutMin, unit: 'MINUTES')` 包裹。
- **不创建 stage**：`run()` 自己不 `script.stage(...)`，由调用方决定 stage 命名（更灵活）。

**详细使用见 [user-guide.md §6](./user-guide.md#6-并发扫描-apexscan)。**

### 3.6 `ScanResult` — 扫描结果

```groovy
class ScanResult implements Serializable {
    String scanner
    String status = 'OK'                  // OK | WARN | FAILED | SKIPPED | TIMEOUT
    int critical = 0
    int high = 0
    int medium = 0
    int low = 0
    long elapsedMs = 0
    String reportPath
    String summary = ''
    List<Map<String, Object>> findings = []
    Throwable error
    Map<String, Object> extras = [:]

    boolean passed(String severity = 'high') { ... }
}
```

业务方在 scanner 闭包里 `return new ScanResult(scanner: 'sast', status: 'OK', high: 0)` 即可被 `assertPassed()` 收集。

### 3.7 `Retry` — 重试策略

```groovy
class Retry implements Serializable {
    int maxAttempts = 1
    long initialDelayMs = 0L
    double backoffMultiplier = 1.0
    int maxDelayMs = 60000
    List<Class<? extends Throwable>> retryOn = [Exception]
    Sleeper sleeper

    static Retry none()        { new Retry(maxAttempts: 1) }
    static Retry linear(int n, long delayMs) { ... }
    static Retry exponential(int n, long initialMs, double mult) { ... }

    static <T> T execute(Retry retry, Closure<T> body) { ... }
}
```

**关键约束**：

- `Retry` 构造里**不要**在构造函数里 `new Sleeper()`——CPS 下会误识别。
- 静态工厂方法 `linear/exponential/none` 里**才**创建默认 Sleeper，**禁止**在 `@NonCPS` 之外的字段初始化中调用。
- `sleeper.sleep(int seconds)` 接收**秒**，不是毫秒。`Retry` 内部负责 ms→s 转换。

**详细使用见 [user-guide.md §7](./user-guide.md#7-重试-apexretryxxx)。**

### 3.8 `Sleeper` / `JenkinsSleeper` / `NoOpSleeper`

```groovy
interface Sleeper { void sleep(int seconds) }

class JenkinsSleeper implements Sleeper {
    Object script
    void sleep(int seconds) { script?.sleep(seconds) }
}

class NoOpSleeper implements Sleeper {
    void sleep(int seconds) { /* no-op */ }
}
```

**为什么抽象**：
- 业务场景：`Retry.exponential` 在生产环境用 `script.sleep`（CPS-safe）；在沙箱测试里可能直接 `Thread.sleep`。
- 单测场景：注入 mock，断言"调用了 sleep N 秒"。

### 3.9 `ConsoleReporter` — 扫描汇总

```groovy
class ConsoleReporter implements Serializable {
    void reportScan(PipelineContext ctx, List<ScanResult> results) {
        if (ctx?.script == null) return
        ctx.script.echo("=".multiply(72))
        ctx.script.echo(" Apex Security Scan Summary")
        results.each { r ->
            ctx.script.echo(" ${r.scanner.padRight(20)} | ${r.status.padRight(8)} | H=${r.high} M=${r.medium} L=${r.low} | ${r.elapsedMs}ms".toString())
        }
        ctx.script.echo("=".multiply(72))
    }
}
```

**沙箱安全**：只走 `script.echo`，没有任何反射、动态 import。

---

## 4. 扩展：新增 Builder

### 4.1 目标

新增一个 `RustBuilder`，支持 Cargo 项目构建。

### 4.2 步骤

#### 1) 定义 Config 类

```groovy
// src/com/hsbc/treasury/apex/ci/builders/RustBuildConfig.groovy
package com.hsbc.treasury.apex.ci.builders

import com.hsbc.treasury.apex.ci.core.DynamicParams

class RustBuildConfig implements Serializable {
    private static final long serialVersionUID = 1L

    String rustVersion = '1.81'
    String target      = 'x86_64-unknown-linux-gnu'
    List<String> commands = ['build', '--release']
    List<String> features = []
    boolean allFeatures = false
    DynamicParams params = new DynamicParams()
}
```

#### 2) 实现 Builder

```groovy
// src/com/hsbc/treasury/apex/ci/builders/RustBuilder.groovy
package com.hsbc.treasury.apex.ci.builders

import com.hsbc.treasury.apex.ci.core.PipelineContext

class RustBuilder extends AbstractBuilder {

    @Override String getLanguage() { 'rust' }

    @Override boolean detect(File projectDir) {
        return new File(projectDir, 'Cargo.toml').exists()
    }

    @Override Object parseConfig(Closure body) {
        def cfg = new RustBuildConfig()
        if (body != null) {
            body.delegate = cfg
            body.resolveStrategy = Closure.DELEGATE_FIRST
            body()
        }
        return cfg
    }

    @Override Object execute(PipelineContext ctx, Closure body, Map opts = [:]) {
        RustBuildConfig cfg = (RustBuildConfig) parseConfig(body)
        def cmd = ['cargo'] + cfg.commands
        if (cfg.allFeatures) cmd << '--all-features'
        cfg.features.each { cmd += ['--features', it] }
        cmd += ['--target', cfg.target]
        cmd = mergeDynamicParams(cmd, cfg.params)
        cmd = platformAdapt(cmd, ctx)
        ctx.script.sh(script: cmd, label: 'cargo-build')
        return null
    }
}
```

#### 3) 注册到 Factory

```groovy
// src/com/hsbc/treasury/apex/ci/builders/BuilderFactory.groovy
static {
    REGISTRY['rust'] = new RustBuilder()
    ...
}
```

#### 4) 业务方使用

```groovy
stage('Build') {
    apexBuild('rust') {
        rustVersion = '1.81'
        features = ['serde', 'tls']
        params { flag('--locked') }
    }
}
```

#### 5) 单测

```groovy
class RustBuilderTest {
    @Test
    void build_composesCargoCommand() {
        def script = new MockScript()
        def ctx = PipelineContext.builder().script(script).build()
        BuilderFactory.of('rust').execute(ctx, {
            features = ['serde', 'tls']
        } as Closure)
        def rendered = script.shCalls[-1].script.toString()
        Assert.assertTrue(rendered.contains('cargo'))
        Assert.assertTrue(rendered.contains('--features'))
        Assert.assertTrue(rendered.contains('serde'))
    }
}
```

### 4.3 模板（如需）

如需内置 Cargo 项目骨架，可加 `resources/templates/rust-cargo.tpl`（注意 `resources/` 会被打包到 Library 根，Jenkins `libraryResource('...')` 可直接读）。

---

## 5. 扩展：新增 Scanner

### 5.1 目标

新增 `ContainerScanner`（容器镜像漏洞扫描），集成 Trivy / Clair。

### 5.2 实现思路

轻量版**不再要求**继承 `AbstractScanner`——直接在 `vars/` 入口或业务方 Jenkinsfile 中通过 `apexScan { container(name) { ... } }` 闭包调用即可。

#### 1) 在闭包中实现

```groovy
stage('Security') {
    def r = apexScan {
        container('app:1.0.0') {
            def report = 'target/trivy.json'
            sh "trivy image --format json --output ${report} app:1.0.0"
            def json = readJSON(file: report)
            def high = json.findAll { it.Severity == 'HIGH' }.size()
            return new ScanResult(scanner: 'trivy', status: 'OK', high: high)
        }
    }
    r.failOn = ['high', 'critical']
    r.assertPassed()
}
```

#### 2) 封装为可复用 step（推荐）

```groovy
// vars/trivy.groovy
import com.hsbc.treasury.apex.ci.scanners.ScanResult

def call(String image, String reportPath = 'target/trivy.json') {
    sh "trivy image --format json --output ${reportPath} ${image}"
    def json = readJSON(file: reportPath)
    return new ScanResult(
        scanner: 'trivy',
        status : 'OK',
        high   : json.count { it.Severity == 'HIGH' },
        medium : json.count { it.Severity == 'MEDIUM' }
    )
}
```

```groovy
// 使用：
stage('Security') {
    def r = apexScan {
        container('app:1.0.0') { trivy('app:1.0.0') }
    }
    r.assertPassed()
}
```

#### 3) 不再使用 ScannerRegistry

v1.x 的 `ScannerRegistry` 已经被废弃——注册多一层间接性，对业务方没价值。直接用闭包 + `apexScan` 组合即可。

### 5.3 DSL 入口

如果想给 scanner 起一个独立名字（如 `apexTrivy(image)`），新建 `vars/trivy.groovy` 即可，参见 [§6](#6-扩展新增全局变量vars)。

---

## 6. 扩展：新增全局变量（vars/）

`vars/<name>.groovy` 中定义 `def call(...)` 即成为 Jenkins 全局变量。

### 6.1 简单变量

```groovy
// vars/apexVersion.groovy
def call() { return '2.0.0' }
```

### 6.2 DSL 块（最常见）

```groovy
// vars/apexConfig.groovy
import com.hsbc.treasury.apex.ci.config.LibraryConfig

def call(Closure body) {
    def cfg = new LibraryConfig()
    body.delegate = cfg
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body()
    return cfg
}
```

### 6.3 一步执行

```groovy
// vars/apexBuild.groovy
import com.hsbc.treasury.apex.ci.builders.BuilderFactory
import com.hsbc.treasury.apex.ci.core.PipelineContext

def call(String language, Map opts = [:], Closure body) {
    Object script = this
    PipelineContext ctx = script.binding?.hasVariable('apexCtx') ? script.apexCtx :
        PipelineContext.builder().script(script).build()
    BuilderFactory.of(language).execute(ctx, body, opts)
    return null
}
```

### 6.4 必须遵守的规则

| 规则 | 反例 | 正例 |
| --- | --- | --- |
| 字符串拼接 `sh` | `sh "mvn $goal"` | `sh(script: ['mvn', goal])` |
| `evaluate` | `evaluate("return $x")` | 静态 `if / else` |
| 反射 | `obj.'field'` | `obj.getField()` |
| 动态 import | `loadClass('Foo')` | 静态 import + Factory |
| 多线程 | `new Thread({...}).start()` | `parallel {}` |
| 任意文件读写 | `new File('/etc/x').text` | `script.readFile` / 白名单 |

> `vars/` 中的全局变量**没有 `@Field` / `@NonCPS` 注解限制**，但函数体本身**会被沙箱审计**，仍必须 sandbox-safe。

---

## 7. 并发、并行与异常隔离实现

### 7.1 原生 `parallel` 是首选

库**不复用**自建线程池——所有并发都走 Jenkins 原生 `script.parallel(branches)`：

```groovy
def branches = [:]
entries.each { e ->
    branches["${e.type}-${e.name}".toString()] = {
        try {
            script.timeout(time: timeoutMin, unit: 'MINUTES') {
                def out = e.body.call()
                return (out instanceof ScanResult) ? out : new ScanResult(...)
            }
        } catch (Throwable t) {
            return new ScanResult(scanner: 'unknown', status: 'FAILED', summary: t.message, error: t)
        }
    }
}
script.parallel(branches)
```

**为什么不用 ExecutorService**：
- CPS 不允许后台线程访问 Pipeline 变量
- 沙箱拒绝 `new Thread` / `Executors`
- Jenkins 原生 `parallel` 本身就是按分支调度执行，错误隔离、超时控制、UI 嵌套 stage 都是它最擅长

### 7.2 异常隔离

每个 branch 的 `body.call()` 被 `try / catch (Throwable)` 包住：
- 抛任何异常 → 转 `ScanResult(status='FAILED', error=t)`
- **不会**让整个 `parallel` 失败（与 `failFast=false` 一致）
- 业务方可以单独判断"哪个 scanner 挂了"并发送通知

```groovy
// 业务方使用
def r = apexScan {
    sast { sh 'sonar-scanner' }                            // OK
    sca  { error('SCA server is down') }                   // FAILED, isolated
    container('app') { sh 'trivy image app' }              // OK
}
r.assertPassed()   // sca 状态为 FAILED → 抛异常
```

### 7.3 单分支优化

`ScanRunner.run()` 检测到 `entries.size() == 1` 时**不调 parallel**——直接同步执行，避免无意义的 parallel 开销（也避免单分支时 parallel 的 CPS 副作用）。

### 7.4 等待扫描

轻量版**不**提供"启动扫描 + 异步取结果"模式——一律用 `apexScan { ... }` 同步等待 + 门禁。理由：

- Jenkins stage 本来就按顺序串行；扫描放在独立 stage 后，下游 stage 天然会等它完成。
- 异步模型需要外部状态（文件、数据库、API），增加复杂度。
- 业务方要"先 build 再 wait scan"时，把 build stage 写在 scan stage **前面**即可。

如果确实需要"启动后立刻去做别的"，可拆成两个 stage：

```groovy
stage('Kickoff') {
    apexScan { sast { sh 'sonar-scanner &'; sleep 1 } }   // 启动（假设异步）
}
stage('Wait') {
    timeout(30) { sh 'wait-for-sonar.sh' }                // 显式等待
}
```

但 90% 场景下，**顺序 stage** 是最直观的做法。

---

## 8. 外部服务不稳定处理（重试与门禁）

### 8.1 线性 vs 指数退避

| 策略 | 适用场景 | 示例 |
| --- | --- | --- |
| `linear(n, ms)` | 错误持续时间短（几秒），间隔固定 | 镜像推送瞬时网络抖动 |
| `exponential(n, ms, mult)` | 服务刚重启 / 缓存预热，间隔指数增长 | Nexus 502、Snyk 504 |
| `none()` | 调试 / 不希望重试 | — |

### 8.2 重试 + 扫描门禁组合

最常见的反模式：**直接把所有事都包在一个 retry 里**。正确做法是**分层重试**：

```groovy
stage('Security') {
    def r = apexScan {
        // 每个 scanner 内部独立重试
        sast { apexRetry.linear(2, 1000) { sh 'sonar-scanner ...' } }
        sca  { apexRetry.linear(2, 1000) { sh 'snyk test' } }
    }
    // 扫描层门禁
    r.failOn = ['critical']
    r.assertPassed()
}

stage('Publish') {
    // 整个 publish 阶段再重试
    apexRetry.exponential(3, 2000, 2.0) {
        withCredentials([...]) { sh 'mvn deploy -DskipTests' }
    }
}
```

**理由**：
- scanner 内部的瞬时错误不会影响其他 scanner
- publish 失败不会污染扫描结果
- 重试策略独立调参

### 8.3 异常分类

`Retry.retryOn` 默认 `[Exception]`。如果想区分"可重试 vs 不可重试"：

```groovy
new Retry(
    maxAttempts: 3,
    initialDelayMs: 1000,
    retryOn: [java.net.SocketTimeoutException, java.io.IOException]
).execute { ... }
```

凭据错误（401 / 403）应**不**在重试范围内——让业务方快速发现并修复。

### 8.4 失败快速终止（failFast）

`ScanRunner.failFast = true` 时，**单个 scanner 抛异常不会**让整个 parallel 终止（与 v1.x 不同）。这与"异常隔离"是一对设计权衡：

- 想要"一个失败就全部停"？把 scanner 互相 `dependsOn` 写在闭包外。
- 想要"每个 scanner 独立跑完"？默认行为。

---

## 9. 沙箱安全（Sandbox-safe）

### 9.1 Jenkins Sandbox 原理

Jenkins 在执行 Shared Library 时，默认会通过 `groovy-sandbox` 插件做静态白名单审计。任何**未在白名单**的方法调用都会抛 `RejectedAccessException`。

### 9.2 白名单申请流程

1. **Jenkins 管理员** 在 `Manage Jenkins → In-process Script Approval` 中审批
2. **业务方** 提供"为什么需要"的说明 + 最小化调用示例
3. 审批后**仅本类**调用合法

### 9.3 必须遵守的规则

| 规则 | 反例（被拒） | 正例（通过） |
| --- | --- | --- |
| 字符串拼接 `sh` | `sh "mvn $goal"` | `sh(script: ['mvn', goal])` |
| `evaluate` | `evaluate("return $x")` | 静态 `if / else` |
| 反射 | `obj.'field'` | `obj.getField()` |
| 动态 import | `this.class.classLoader.loadClass('Foo')` | 静态 import + Factory |
| 多线程 | `new Thread({...}).start()` | `parallel {}` |
| 任意文件读写 | `new File('/etc/x').text | `script.readFile` / `script.writeFile` |
| `Thread.sleep` | `Thread.sleep(1000)` | `script.sleep(1)` |

### 9.4 `utils/Sandbox.groovy` — 命令白名单

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

### 9.5 调试沙箱

临时关闭沙箱（仅开发）：

```groovy
@Library('apex-ci-library@main') _   // 必须以非沙箱方式加载
```

> **生产强制** 沙箱模式。

### 9.6 Sandbox CI 强制

```bash
# docker/test-env/test-it.sh 中：
curl -X POST "$JENKINS_URL/job/sandbox-replay/build" --user "$USER:$TOKEN"
```

> 沙箱回放测试在真实 Jenkins 上跑所有 `vars/` 入口 + `src/` 核心类。

---

## 10. 测试策略

### 10.1 测试金字塔

```
        ┌────────────────────┐
        │   E2E / Sandbox    │  ← 真实 Jenkins 跑 Jenkinsfile-modules
        ├────────────────────┤
        │ 集成（MockScript） │  ← LightweightDslTest 覆盖并行/扫描/重试
        ├────────────────────┤
        │    单元测试        │  ← JUnit 4 + groovyc
        └────────────────────┘
```

### 10.2 单元测试

使用 `groovyc` 直接编译，`JUnitCore` 运行：

```bash
./build.sh
# 编译 src/ → out/classes
# 编译 test/ → out/test-classes
# 拷贝 junit/hamcrest/groovy 到 out/lib
# 运行 JUnitCore out/test-classes com.hsbc.treasury.apex.ci...
```

### 10.3 MockScript — 模拟 Jenkins script

```groovy
// test/com/hsbc/treasury/apex/ci/utils/MockScript.groovy
class MockScript {
    List<Map> shCalls = []
    List<String> echos = []
    List<Map> parallels = []
    Closure timeoutBehavior

    Object sh(Map args) {
        shCalls << args
        return 0
    }
    void echo(String msg) { echos << msg }
    Object parallel(Map blocks) {
        parallels << [blocks: blocks]
        return blocks.collectEntries { k, v -> [(k): v.call()] }
    }
    Object timeout(Map args, Closure body) {
        return timeoutBehavior ? timeoutBehavior(args, body) : body.call()
    }
    String pwd() { return '/tmp/workspace' }
}
```

### 10.4 集成测试：LightweightDslTest

`test/com/hsbc/treasury/apex/ci/integration/LightweightDslTest.groovy` 覆盖：

| 用例 | 验证 |
| --- | --- |
| `scanRunner_usesNativeParallelForMultipleBranches` | 3 个分支 → 调 `script.parallel` |
| `scanRunner_singleBranchDoesNotUseParallel` | 1 个分支 → 不调 parallel |
| `scanRunner_isolatesExceptionsAcrossBranches` | 单个抛异常 → 转 FAILED，其它 OK |
| `scanRunner_blocksUntilAllBranchesComplete` | 阻塞直到全部完成 |
| `scanRunner_assertPassedRunsGateAfterAllScansDone` | 门禁在所有扫描后执行 |
| `retry_recoversFromTransientExternalServiceError` | 第 3 次成功 → 整体成功 |
| `retry_givesUpAfterMaxAttempts` | 超过 max → 抛 ApexCIException |
| `retry_exponentialBackoffActuallyWaits` | 指数退避实际等待 |
| `javaBuilder_assemblesMavenCommandArray` | 命令包含 key 参数 |
| `dynamicParams_freeAdditionAndRemoval` | 自由加减 |
| `ctx_attrsSurviveAcrossStages` | attrs 跨 stage 共享 |
| `scanRunner_emitsReporterOutput` | ConsoleReporter 输出 sast/sca |
| `javaBuilder_missingBuildToolFailsFast` | 缺 buildTool 立即抛 |
| `scanRunner_othersPassEvenWhenOneFails` | 一个 FAILED → 门禁只针对它 |

### 10.5 真实 Jenkins 集成测试

`docker/test-env/jenkins/Jenkinsfile-modules` 跑一个真实 Pipeline 验证：
- `apexBuild`（java / node / python / go）
- `apexScan`（3+ scanner 并发）
- `apexRetry.linear / exponential`
- `apexDocker`（如果 docker-in-docker 可用）
- `apexPublish`（如果 nexus 可达）
- `apexConfig`（YAML/Properties/JSON）
- `apexParams`（动态参数加减）

`test-it.sh` 通过 curl 触发这个 job，断言成功。

### 10.6 覆盖率

| 模块 | 目标 |
| --- | --- |
| `core/` | ≥ 90% |
| `builders/` | ≥ 80% |
| `scanners/` | ≥ 80% |
| `docker/` | ≥ 70% |
| `vars/` | 不计入（仅冒烟） |

---

## 11. 发布流程

### 11.1 版本号

遵循 [SemVer 2.0.0](https://semver.org/)：

- `MAJOR`：API 不兼容变更
- `MINOR`：向后兼容的功能新增
- `PATCH`：Bug 修复

### 11.2 发布步骤

```bash
# 1) 切到 main，确保最新
git checkout main && git pull

# 2) 跑全量检查
./build.sh
bash docker/test-env/test-it.sh

# 3) 更新 CHANGELOG.md
# 4) 提交
git add CHANGELOG.md
git commit -m "release: v2.0.0"
git push

# 5) 打 tag
git tag -a v2.0.0 -m "v2.0.0"
git push origin v2.0.0
```

### 11.3 兼容性矩阵

| 库版本 | Jenkins LTS | JDK | Pipeline Plugin |
| --- | --- | --- | --- |
| 2.0.x | 2.470.x+ | 17 / 21 | workflow-aggregator ≥ 650 |
| 1.x | 2.426.x+ | 11 / 17 / 21 | workflow-aggregator ≥ 596 |

> **LTS 策略**：偶数 MAJOR 长期维护（≥ 18 个月）；奇数 MAJOR 仅维护至下个偶数发布。

### 11.4 业务方升级提示

发布到内部 Nexus Update Center 后，触发 **Dependabot** 类机器人通知所有引用仓库。

---

## 12. 调试与排错

### 12.1 本地 IDE 调试

1. 在 IntelliJ 中打开 `apex-ci-library`
2. 配置 Groovy SDK = 本地 groovy 4.0.x
3. 标记 `src/` 为 Sources，`test/` 为 Test Sources，`vars/` 不参与编译
4. 写一个测试类，构造 `MockScript` + `PipelineContext.builder().script(script).build()` 即可

### 12.2 远程调试真实 Jenkins

Jenkins 启动参数：

```bash
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005 \
     -jar jenkins.war
```

IDEA 中：`Run → Attach to Process → localhost:5005`，在 Library 代码里打断点。

### 12.3 沙箱审计日志

```bash
# Jenkins master
tail -f /var/log/jenkins/script-approval.log | grep -i apex
```

### 12.4 常见错误

| 错误 | 原因 | 解决 |
| --- | --- | --- |
| `RejectedAccessException: unclassified ...` | 调用了未审批的方法 | 提交 Script Approval |
| `NotSerializableException` | Builder 持有非 Serializable 引用 | 全部字段加 `implements Serializable` |
| `MissingMethodException` | DSL 闭包未 delegate | `body.delegate = cfg; body.resolveStrategy = Closure.DELEGATE_FIRST` |
| `Pipeline stuck` | `Thread.sleep` 阻塞 CPS | 用 `script.sleep(seconds)` |
| `CPS exception` | 在 CPS 外用 `for` 循环 | 用 `while` + 状态字段 |
| `parallel returned null` | 单分支走 fall-back | 检查 `entries.size() == 1` 路径 |
| `apexScan 闭包外忘了 stage` | 业务方没在 stage 内 | 提示业务方包 stage |

---

## 13. 代码规范与 Lint

### 13.1 命名

| 类型 | 命名 | 示例 |
| --- | --- | --- |
| 包 | 全小写 | `com.hsbc.treasury.apex.ci.builders` |
| 类 | PascalCase | `JavaBuilder` |
| 方法 / 字段 | camelCase | `buildMaven` |
| 常量 | UPPER_SNAKE | `MAX_RETRY_ATTEMPTS` |
| 全局变量 | lowerCamel | `apexBuild` |
| DSL 块 | lowerCamel | `containerBuild` |

### 13.2 强制规则（CI 校验）

- **禁止** `evaluate(...)`
- **禁止** 字符串拼接 `sh`（必须数组形式）
- **禁止** `Class.forName` / 反射
- **禁止** `new Thread` / `Executors`
- **必须** 所有类 `implements Serializable` 并显式 `serialVersionUID`
- **必须** DSL 闭包使用 `DELEGATE_FIRST`
- **必须** 跨闭包共享字段用 `final` + 不可变容器
- **禁止** 在构造函数里调用 `Retry.xxx()` 静态工厂（CPS 兼容）
- **必须** `Retry` / `Sleeper` 配合测试场景

### 13.3 Lint 配置

```bash
# 使用 npm groovy-lint
npx groovy-lint --config .groovylintrc.json --path src --path vars
```

### 13.4 PR 流程

1. Fork → Branch → 提交
2. 跑 `./build.sh`（必须全绿）
3. 跑 `bash docker/test-env/test-it.sh`（沙箱回放）
4. 提交 PR 至 `apex-ci-library/main`
5. CI 自动跑：单元测试 + 沙箱回放
6. Code Owner Review（≥ 1 人）
7. 合并 → 自动构建 SNAPSHOT

---

## 14. 从 v1.x 迁移

### 14.1 旧 DSL（v1.x）

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

### 14.2 新 DSL（v2.0）

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

### 14.3 关键差异

| 旧 API（v1.x） | 新 API（v2.0） | 备注 |
| --- | --- | --- |
| `apex { stages { ... } }` | `node { stage { ... } }` | 走原生 |
| 自定义 `Pipeline` / `Stage` / `Step` | 全部移除 | 不再自创流程抽象 |
| `apex.startScan {}` / `apex.collectScans()` | `apexScan { ... }` 合并 | 同步 + 门禁 |
| `apex.gate(results, policy: 'high+')` | `runner.assertPassed()` | 设置 `failOn` |
| `ScannerRegistry` | 移除 | 闭包组合替代 |
| `apex.detectLanguage()` | `BuilderFactory.autoDetect(...)` | 自动检测 |
| `apexNexus {}` | `apexPublish(...)` | 同名 |
| `apexContext.vars.team` | `apexCtx.getAttr('team')` | attrs 命名 |
| 自建线程池并发 | Jenkins 原生 `parallel` | 沙箱安全 |

### 14.4 收益

- **更短**：少一层自定义抽象
- **更稳**：少一处 CPS 转换
- **更兼容**：Jenkins 升级时几乎不破坏
- **更可读**：Jenkinsfile 像普通 Groovy，业务方不需要学新 DSL

---

## 附录 A：发布检查清单

- [ ] `CHANGELOG.md` 已更新
- [ ] 所有新增 DSL 在 `docs/user-guide.md` 有说明
- [ ] `docs/user-guide.md` 给出至少一个完整示例
- [ ] 单测覆盖率达标
- [ ] 沙箱 Smoke 在真实 Jenkins 通过
- [ ] 版本号合规 SemVer
- [ ] 兼容性矩阵已更新
- [ ] 通知 Slack `#apex-ci-announce`

---

## 附录 B：常见扩展点

| 扩展点 | 如何扩展 |
| --- | --- |
| 新语言 | 新建 `xxx/Builder.groovy` + 注册到 `BuilderFactory` |
| 新扫描器 | 业务方直接在 `apexScan { generic('name') { ... } }` 注册 |
| 新通知渠道 | 新建 `notifiers/XxxNotifier.groovy` |
| 新报告格式 | 新建 `reporters/XxxReporter.groovy` |
| 全局变量 | 新建 `vars/<name>.groovy` |
| 新 Artifact 仓库 | 新建 `artifact/XxxClient.groovy` |
| 新 Registry | `docker/DockerRegistry.groovy` SPI |
