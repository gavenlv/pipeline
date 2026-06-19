# apex-ci-library 二次开发指南

> **包名**：`com.hsbc.treasury.apex.ci`
> **版本**：v1.0.0
> **目标读者**：参与本库开发 / 扩展的工程师、CI 平台维护者
> **最后更新**：2026-06-19

---

## 目录

1. [开发环境搭建](#1-开发环境搭建)
2. [项目结构与 Gradle 配置](#2-项目结构与-gradle-配置)
3. [核心模型详解](#3-核心模型详解)
4. [扩展：新增 Builder](#4-扩展新增-builder)
5. [扩展：新增 Scanner](#5-扩展新增-scanner)
6. [扩展：新增全局变量（vars/）](#6-扩展新增全局变量vars)
7. [并发与异步实现](#7-并发与异步实现)
8. [沙箱安全（Sandbox-safe）](#8-沙箱安全sandbox-safe)
9. [测试策略](#9-测试策略)
10. [发布流程](#10-发布流程)
11. [调试与排错](#11-调试与排错)
12. [代码规范与 Lint](#12-代码规范与-lint)

---

## 1. 开发环境搭建

### 1.1 必备工具

| 工具 | 版本 | 用途 |
| --- | --- | --- |
| JDK | 11 / 17 / 21 | Groovy / Java 编译 |
| Gradle | 8.10+ | 构建 + Lint |
| Groovy | 4.0.x | Library 语言 |
| Jenkins | 2.426.x LTS | 本地自检（推荐 Docker） |
| Docker | 24+ | buildx 测试 |
| Node | 20+ | Lint 工具（npm-groovy-lint） |

### 1.2 推荐 IDE

- **IntelliJ IDEA Ultimate**（自带 Jenkins / Groovy 插件）
- VS Code + `Groovy Lint` / `Jenkinsfile Lint` 扩展

### 1.3 本地启动 Jenkins

```bash
docker run -d --name jenkins-dev \
  -p 8080:8080 \
  -v jenkins_home:/var/jenkins_home \
  jenkins/jenkins:2.426.3-lts
```

挂载本库：

```bash
docker cp . jenkins-dev:/var/jenkins_home/jobs/apex-ci-library/workspace/
```

在 Jenkins 中配置 Shared Library：

> Manage Jenkins → System → Global Pipeline Libraries → Add
> - Name: `apex-ci-library`
> - Default version: `main`
> - Source: `Modern SCM` → Git → 本库仓库

### 1.4 启动本地构建

```bash
./gradlew clean build          # 编译 + 单测
./gradlew lint                 # groovy lint
./gradlew integrationTest      # PipelineUnit
./gradlew sandboxTest          # 在真实 Jenkins 上跑 smoke
```

---

## 2. 项目结构与 Gradle 配置

### 2.1 目录约定

```
apex-ci-library/
├── build.gradle                # 根项目
├── settings.gradle
├── gradle.properties
├── gradle/
│   └── wrapper/
│
├── src/main/groovy/            # 主源码（与 src/ 等价，但用 gradle 标准目录）
│   └── com/hsbc/treasury/apex/ci/
│       ├── builders/
│       ├── scanners/
│       ├── core/
│       └── ...
│
├── src/main/resources/         # = 仓库根的 resources/
│
├── src/test/groovy/            # 单元测试
│
├── vars/                       # 全局 DSL（gradle 复制到 build/resources/main）
│
├── Jenkinsfile                 # 库的自检
└── docs/
```

> **注意**：Jenkins 加载 Library 时**只看仓库根**的 `src/`、`vars/`、`resources/`、`test/`。为了在 IDE 调试方便，本仓库**同时**提供 `src/main/groovy/` 和 `src/` 两套目录，通过 `build.gradle` 的 `sourceSets` 桥接。发布的产物**只包含** `src/`、`vars/`、`resources/`。

### 2.2 `build.gradle` 示例

```groovy
plugins {
    id 'groovy'
    id 'java'
    id 'maven-publish'
}

group   = 'com.hsbc.treasury.apex.ci'
version = '1.0.0'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories { mavenCentral() }

dependencies {
    implementation 'org.codehaus.groovy:groovy-all:3.0.21'

    // Pipeline 单元测试
    testImplementation 'com.lesfurets:jenkins-pipeline-unit:1.18'

    // 断言
    testImplementation 'org.spockframework:spock-core:2.3-groovy-3.0'
    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'

    // Lint
    npm 'com.nvuillam:groovy-lint:0.0.16'
}

sourceSets {
    main {
        groovy {
            srcDirs = ['src/main/groovy', 'src']   // 双源
        }
        resources {
            srcDirs = ['src/main/resources', 'resources', 'vars']
        }
    }
    test {
        groovy { srcDirs = ['src/test/groovy', 'test'] }
    }
}

tasks.named('test') {
    useJUnitPlatform()
    testLogging {
        events 'passed', 'skipped', 'failed'
        showStandardStreams = true
    }
}

// Lint
tasks.register('lint', JavaExec) {
    description = 'Run groovy lint'
    classpath = sourceSets.test.runtimeClasspath
    mainClass = 'com.nvuillam.github.NodeGroovyLint'
    args '--config', 'config/groovylintrc.json', '--path', 'src', '--path', 'vars'
}
```

### 2.3 Gradle 关键约定

| 项目 | 路径 | 说明 |
| --- | --- | --- |
| 主源码 | `src/main/groovy` + `src/` | 二选一；推荐 `src/main/groovy` 用于 IDE 友好 |
| 测试 | `src/test/groovy` + `test/` | 同上 |
| 资源 | `src/main/resources` + `resources/` | 模板、策略、脚本 |
| 全局变量 | `vars/` | 复制到 `build/resources/main/vars/` |
| 编译产物 | `build/libs/apex-ci-library.jar` | 调试用，不发布 |

---

## 3. 核心模型详解

### 3.1 `Pipeline`

```groovy
package com.hsbc.treasury.apex.ci

class Pipeline implements Serializable {
    private static final long serialVersionUID = 1L

    PipelineContext context
    List<Stage>     stages = []

    void stages(Closure body) { /* delegate body */ }
    void stage(String name, Closure body) { ... }

    /** 真正执行（在 sandbox 内） */
    void execute(Object script) {
        // 1) 注入 script
        stages.each { st -> st.script = script }
        // 2) 串行执行
        stages.each { st -> st.run() }
    }
}
```

### 3.2 `Stage`

```groovy
class Stage implements Serializable {
    private static final long serialVersionUID = 1L

    String name
    StageType type = StageType.SEQUENTIAL      // SEQUENTIAL | PARALLEL
    List<Step> steps = []
    Object script
    Closure when = { true }                    // 条件执行

    void run() {
        if (!when.call()) {
            script.echo "Skip stage: ${name}"
            return
        }
        script.stage(name) {
            switch (type) {
                case StageType.SEQUENTIAL:
                    steps.each { it.run() }
                    break
                case StageType.PARALLEL:
                    script.parallel steps.collectEntries {
                        [(it.name): { it.run() }]
                    }
                    break
            }
        }
    }
}
```

### 3.3 `Step`

```groovy
class Step implements Serializable {
    private static final long serialVersionUID = 1L

    String name
    Closure body
    Object script
    RetryPolicy retry = RetryPolicy.NONE

    void run() {
        Retry.execute(retry) {
            body.delegate = this
            body.resolveStrategy = Closure.DELEGATE_FIRST
            body()
        }
    }
}
```

### 3.4 `PipelineContext`（不可变）

```groovy
@groovy.transform.Immutable(knownImmutables = ['global', 'build', 'scan'])
class PipelineContext implements Serializable {
    private static final long serialVersionUID = 1L

    String appName
    String branch
    String commitSha
    String semver
    GlobalConfig global
    BuildConfig  build
    ScanConfig   scan
    Map<String, Object> userVars = [:]

    static PipelineContext fromEnv(env) {
        return new PipelineContext(
            appName:   env.JOB_NAME?.split('/')?.last(),
            branch:    env.BRANCH_NAME,
            commitSha: env.GIT_COMMIT,
            global:    GlobalConfig.fromEnv(env),
            build:     new BuildConfig(),
            scan:      new ScanConfig()
        )
    }

    PipelineContext withVars(Map<String, Object> extra) {
        return new PipelineContext(
            appName:   appName,
            branch:    branch,
            commitSha: commitSha,
            semver:    semver,
            global:    global,
            build:     build,
            scan:      scan,
            userVars:  userVars + extra
        )
    }
}
```

### 3.5 `DynamicParams`（通用动态参数）

详见 `core/DynamicParams.groovy` —— 任何 Builder / Scanner 都内嵌一份。

---

## 4. 扩展：新增 Builder

### 4.1 目标

新增一个 `RustBuilder`，支持 Cargo 项目构建。

### 4.2 步骤

#### 1) 定义配置类

```groovy
// src/main/groovy/com/hsbc/treasury/apex/ci/builders/RustBuildConfig.groovy
package com.hsbc.treasury.apex.ci.builders

import com.hsbc.treasury.apex.ci.core.DynamicParams

class RustBuildConfig implements Serializable {
    private static final long serialVersionUID = 1L

    String rustVersion = '1.81'
    String target      = 'x86_64-unknown-linux-gnu'
    List<String> commands = ['build', '--release']
    List<String> features = []
    boolean   allFeatures = false
    DynamicParams params = new DynamicParams()
}
```

#### 2) 实现 Builder

```groovy
// src/main/groovy/com/hsbc/treasury/apex/ci/builders/RustBuilder.groovy
package com.hsbc.treasury.apex.ci.builders

class RustBuilder extends AbstractBuilder {

    @Override
    String getLanguage() { 'rust' }

    @Override
    boolean detect(File projectDir) {
        return new File(projectDir, 'Cargo.toml').exists()
    }

    @Override
    void build(PipelineContext ctx, Closure configBody) {
        def cfg = RustBuildConfig.fromClosure(configBody)
        // 1) 安装 rustup 工具链
        script.tool(name: "rust-${cfg.rustVersion}", type: 'cargo')

        // 2) 拼装 cargo 命令
        def cmd = ['cargo'] + cfg.commands
        if (cfg.allFeatures) cmd << '--all-features'
        cfg.features.each { cmd += ['--features', it] }
        cmd += ['--target', cfg.target]
        cmd += cfg.params.flags

        // 3) sandbox-safe 执行
        Sandbox.runShell(script, cmd, 'cargo-build')
    }
}
```

#### 3) 注册

```groovy
// builders/BuilderRegistry.groovy
static {
    REGISTRY['rust'] = RustBuilder   // ← 新增一行
    ...
}
```

#### 4) 提供 DSL 入口

```groovy
// vars/rust.groovy
import com.hsbc.treasury.apex.ci.builders.RustBuilder
import com.hsbc.treasury.apex.ci.PipelineContext

def call(Closure body) {
    def ctx = PipelineContext.current()
    new RustBuilder().withScript(script).withContext(ctx).build(ctx, body)
}
```

#### 5) 单测

```groovy
// test/groovy/com/hsbc/treasury/apex/ci/builders/RustBuilderTest.groovy
class RustBuilderTest extends Specification {

    @Shared PipelineContext ctx = Mock()
    @Shared Object script = Mock()

    def "build composes cargo command"() {
        given:
        def builder = new RustBuilder().withScript(script).withContext(ctx)
        def cfg = new RustBuildConfig(
            commands:  ['build'],
            features:  ['serde', 'tls'],
            allFeatures: false
        )

        when:
        builder.build(ctx) { /* empty */ }

        then:
        1 * script.sh({ it.script == ['cargo', 'build',
                                       '--features', 'serde',
                                       '--features', 'tls',
                                       '--target', 'x86_64-unknown-linux-gnu'] },
                      _ as Map)
    }
}
```

### 4.3 模板（如需）

如需内置 Cargo 项目骨架，可加 `resources/templates/rust-cargo.tpl`。

---

## 5. 扩展：新增 Scanner

### 5.1 目标

新增 `DastScanner`（动态应用安全测试）。

### 5.2 实现

```groovy
// scanners/DastScanner.groovy
package com.hsbc.treasury.apex.ci.scanners

import com.hsbc.treasury.apex.ci.core.AsyncResult

class DastScanner extends AbstractScanner {

    @Override
    String getType() { 'dast' }

    @Override
    String getTool() { cfg.tool ?: 'zap' }

    @Override
    AsyncResult start(PipelineContext ctx) {
        // 1) 启动 OWASP ZAP baseline scan（异步）
        def reportPath = "target/scans/zap-${ctx.commitSha}.json"
        script.sh(script: [
            'docker', 'run', '--rm',
            '-v', "${script.env.WORKSPACE}:/zap/wrk:rw",
            'owasp/zap2docker-stable',
            'zap-baseline.py',
            '-t', cfg.targetUrl,
            '-r', reportPath,
            '-I'                          // info only by default
        ], returnStatus: true, label: 'zap-baseline', async: true)

        // 2) 返回 AsyncResult
        return new AsyncResult(
            id:    "dast-${ctx.commitSha}",
            type:  'dast',
            tool:  'zap',
            state: 'RUNNING',
            payload: [reportPath: reportPath]
        )
    }

    @Override
    ScanResult fetch(AsyncResult handle) {
        // 解析 JSON 报告，转为 ScanResult
        def json = script.readJSON(file: handle.payload.reportPath)
        return new ScanResult(
            type:   'dast',
            tool:   'zap',
            high:   json.findAll { it.risk == 'High' }.size(),
            medium: json.findAll { it.risk == 'Medium' }.size(),
            low:    json.findAll { it.risk == 'Low' }.size(),
            report: handle.payload.reportPath
        )
    }
}
```

### 5.3 注册

```groovy
// scanners/ScannerRegistry.groovy
static {
    REGISTRY['dast'] = DastScanner
    ...
}
```

### 5.4 DSL 入口

```groovy
// vars/dast.groovy
import com.hsbc.treasury.apex.ci.scanners.DastScanner

def call(Closure body) {
    def cfg = new DastConfig()
    body.delegate = cfg
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body()
    new DastScanner(script: script, config: cfg)
}
```

---

## 6. 扩展：新增全局变量（vars/）

`vars/<name>.groovy` 中定义一个 `call(...)` 方法即成为全局变量。

### 6.1 简单变量

```groovy
// vars/apexVersion.groovy
import com.hsbc.treasury.apex.ci.ApexCIRoot

def call() {
    return ApexCIRoot.VERSION
}
```

### 6.2 DSL 块

```groovy
// vars/apexConfig.groovy
import com.hsbc.treasury.apex.ci.config.GlobalConfig

def call(Closure body) {
    def cfg = GlobalConfig.getInstance()
    body.delegate = cfg
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body()
    cfg.persist(script)
}
```

### 6.3 一步执行

```groovy
// vars/apexBuild.groovy
import com.hsbc.treasury.apex.ci.builders.BuilderRegistry
import com.hsbc.treasury.apex.ci.PipelineContext

def call(String language, Closure config = null) {
    def ctx = PipelineContext.current()
    def builder = BuilderRegistry.resolve(language)
    builder.withScript(script).withContext(ctx).build(ctx, config ?: {})
}
```

> **注意**：`vars/` 中的全局变量**没有 `@Field` / `@NonCPS` 注解限制**，但函数体本身**会被沙箱审计**，仍必须 sandbox-safe。

---

## 7. 并发与异步实现

### 7.1 并发执行器

```groovy
// core/ParallelExecutor.groovy
package com.hsbc.treasury.apex.ci.core

class ParallelExecutor {

    /**
     * 在 Jenkins Pipeline 内并发执行多个 Step。
     * @param script  Jenkins 上下文（CPS 步骤宿主）
     * @param steps   Map<String, Closure> name -> body
     * @param failFast 一个失败是否立即中断其他
     */
    static Map<String, Object> execute(
        Object script,
        Map<String, Closure> steps,
        boolean failFast = true
    ) {
        def results = [:]
        def errors  = []

        script.parallel steps.collectEntries { name, body ->
            [(name): {
                try {
                    results[name] = body.call()
                } catch (err) {
                    errors << [name: name, error: err]
                    if (failFast) throw err
                }
            }]
        }

        if (errors) {
            script.error "Parallel failed: ${errors*.name.join(',')}"
        }
        return results
    }
}
```

### 7.2 异步句柄 `AsyncResult`

```groovy
// core/AsyncResult.groovy
package com.hsbc.treasury.apex.ci.core

class AsyncResult<T> implements Serializable {
    private static final long serialVersionUID = 1L

    String id
    String type
    String tool
    long   startTime = System.currentTimeMillis()
    String state     = 'PENDING'
    T      payload

    /** 阻塞 await（带 timeout） */
    T await(int timeoutSeconds = 1800) {
        def deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        while (state in ['PENDING', 'RUNNING']) {
            if (System.currentTimeMillis() > deadline) {
                state = 'TIMEOUT'
                throw new ApexCIException("Async ${id} timeout")
            }
            script.sleep(5)
            refresh()
        }
        return payload
    }

    /** 非阻塞 tryGet */
    Optional<T> tryGet() {
        try { return Optional.of(await(0)) } catch (e) { return Optional.empty() }
    }

    private void refresh() {
        // 子类或 holder 注入
    }
}
```

### 7.3 异步结果聚合

```groovy
// core/ResultAggregator.groovy
class ResultAggregator {

    static List<ScanResult> aggregate(List<AsyncResult> handles, int timeout = 1800) {
        def results = []
        handles.each { h ->
            try {
                results << h.await(timeout)
            } catch (TimeoutException) {
                results << new ScanResult(type: h.type, tool: h.tool, state: 'TIMEOUT')
            }
        }
        return applyGatePolicy(results)
    }

    private static List<ScanResult> applyGatePolicy(List<ScanResult> results) {
        // 根据 ScanConfig.failOn 等策略应用门禁
        results.each { r ->
            if (r.shouldFail()) throw new ScanException("Scan gate failed: ${r}")
        }
        return results
    }
}
```

### 7.4 CPS-safe 等待

⚠️ **CPS 引擎不允许 `Thread.sleep` / `while(true)`**：

- **必须** 使用 `script.sleep(seconds)`
- **必须** 在循环条件里加入 `script` 上的可观察对象（`state` 字段）
- **避免** 长时间忙等，建议 5~10s 间隔

---

## 8. 沙箱安全（Sandbox-safe）

### 8.1 Jenkins Sandbox 原理

Jenkins 在执行 Shared Library 时，默认会通过 `groovy-sandbox` 插件做静态白名单审计。任何**未在白名单**的方法调用都会抛 `RejectedAccessException`。

### 8.2 白名单申请流程

1. **Jenkins 管理员** 在 `Manage Jenkins → In-process Script Approval` 中审批
2. **业务方** 提供"为什么需要"的说明 + 最小化调用示例
3. 审批后**仅本类**调用合法

### 8.3 必须遵守的规则

| 规则 | 反例（被拒） | 正例（通过） |
| --- | --- | --- |
| 字符串拼接 `sh` | `sh "mvn $goal"` | `sh(script: ['mvn', goal])` |
| `evaluate` | `evaluate("return $x")` | 静态 `if / else` |
| 反射 | `obj.'field'` | `obj.getField()` |
| 动态 import | `this.class.classLoader.loadClass('Foo')` | 静态 import + Registry |
| 多线程 | `new Thread({...}).start()` | `parallel {}` |
| 任意文件读写 | `new File('/etc/x').text` | 通过 `utils/FileUtils` |

### 8.4 `utils/Sandbox.groovy`

```groovy
package com.hsbc.treasury.apex.ci.utils

class Sandbox {

    /** 白名单（来自 Jenkins ConfigFileProvider / apex-ci.yaml） */
    private static List<String> COMMAND_WHITELIST = [
        'mvn', 'gradle', 'npm', 'yarn', 'pnpm', 'poetry',
        'docker', 'kubectl', 'helm', 'git', 'curl', 'jq',
        'python', 'python3', 'go', 'dotnet', 'cargo', 'rustc'
    ]

    private static List<String> PATH_WHITELIST = [
        'target/', 'build/', 'dist/', 'out/',
        'src/', 'test/', '.apex/'
    ]

    static int runShell(Object script, List<String> cmd, String label = null) {
        if (!cmd) throw new ApexCIException("Empty command")
        if (!COMMAND_WHITELIST.contains(cmd[0])) {
            throw new ApexCIException("Command not whitelisted: ${cmd[0]}")
        }
        return script.sh(
            script:  cmd,
            label:   label ?: cmd[0],
            returnStatus: true
        )
    }

    static String readFile(Object script, String path) {
        if (!PATH_WHITELIST.any { path.startsWith(it) || path.startsWith("./${it}") }) {
            throw new ApexCIException("Path not whitelisted: ${path}")
        }
        return script.readFile(path)
    }
}
```

### 8.5 调试沙箱

临时关闭沙箱（仅开发）：

```groovy
// Jenkinsfile
@Library('apex-ci-library@main') _   // 必须以非沙箱方式加载
```

> **生产强制** 沙箱模式。

### 8.6 沙箱 CI 强制

`.github/workflows/ci.yml`：

```yaml
- name: Sandbox Replay
  run: |
    # 真实 Jenkins 上以 sandbox 模式跑 smoke
    curl -X POST "$JENKINS_URL/job/sandbox-replay/build" \
         --user "$JENKINS_USER:$JENKINS_TOKEN"
```

---

## 9. 测试策略

### 9.1 测试金字塔

```
        ┌─────────────┐
        │  E2E Smoke  │  ← 真实 Jenkins
        ├─────────────┤
        │ PipelineUnit│  ← JVM 模拟 Jenkins
        ├─────────────┤
        │    Spock    │  ← 单元测试
        └─────────────┘
```

### 9.2 Spock 单元测试

```groovy
import spock.lang.*

class ParallelExecutorTest extends Specification {

    def "executes branches in parallel"() {
        given:
        def script = [
            parallel: { Map branches ->
                branches.each { name, body -> body() }
            }
        ] as Object

        when:
        def r = ParallelExecutor.execute(script, [
            a: { 'A' },
            b: { 'B' }
        ])

        then:
        r == [a: 'A', b: 'B']
    }

    def "failFast interrupts on first error"() {
        given:
        def script = [
            parallel: { Map branches ->
                branches.each { name, body ->
                    try { body() } catch (e) { if (failFast) throw e }
                }
            }
        ] as Object
        boolean failFast = true

        when:
        ParallelExecutor.execute(script, [
            a: { throw new RuntimeException("A fail") },
            b: { 'B' }
        ], failFast)

        then:
        thrown(RuntimeException)
    }
}
```

### 9.3 PipelineUnit 集成测试

```groovy
import com.lesfurets.jenkins.junit.*
import org.junit.*

class PipelineSmokeTest extends PipelineTestBase {

    @Test
    void javaBuildRuns() {
        def script = loadScript("""
            @Library('apex-ci-library') _
            apex {
                appName = 'demo'
                stages {
                    stage('Build') {
                        java { jdk = 21 }
                    }
                }
            }
        """)
        script.execute()

        assertJobStatusSuccess()
        // 验证执行了 mvn
        printCallStack()
        assertCallStackContains('mvn clean verify')
    }
}
```

### 9.4 Sandbox Replay（真实 Jenkins）

`Jenkinsfile`（库的自检）：

```groovy
@Library('apex-ci-library@main') _   // 沙箱模式
apex {
    appName = 'apex-ci-library-smoke'
    stages {
        stage('Self-Test') {
            java { jdk = 21; goals = ['clean', 'test'] }
        }
        stage('Lint') {
            sh 'npm run lint'
        }
    }
}
```

### 9.5 覆盖率

```groovy
// build.gradle
jacoco {
    toolVersion = '0.8.11'
}
jacocoTestReport {
    dependsOn test
    reports {
        xml.required = true
        html.required = true
    }
}
```

要求：

- 核心模型（`core/`）覆盖率 ≥ 90%
- Builder / Scanner 覆盖率 ≥ 80%
- vars/ 不计入覆盖率（仅冒烟）

---

## 10. 发布流程

### 10.1 版本号

遵循 [SemVer 2.0.0](https://semver.org/)：

- `MAJOR`：API 不兼容变更
- `MINOR`：向后兼容的功能新增
- `PATCH`：Bug 修复

### 10.2 发布步骤

```bash
# 1) 切到 main，确保最新
git checkout main && git pull

# 2) 跑全量检查
./gradlew clean build lint sandboxTest

# 3) 更新 CHANGELOG.md
# 4) 提交
git add CHANGELOG.md
git commit -m "release: v1.2.0"
git push

# 5) 打 tag（推送触发 release workflow）
git tag -a v1.2.0 -m "v1.2.0"
git push origin v1.2.0
```

### 10.3 Release Workflow

`.github/workflows/release.yml`：

```yaml
name: Release
on:
  push:
    tags: ['v*.*.*']
jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Build
        run: ./gradlew clean build
      - name: Create GitHub Release
        uses: softprops/action-gh-release@v2
        with:
          files: |
            build/libs/apex-ci-library.jar
            CHANGELOG.md
```

### 10.4 兼容性矩阵

| 库版本 | Jenkins LTS | JDK | Pipeline Plugin |
| --- | --- | --- | --- |
| 1.0.x | 2.426.x+ | 11 / 17 / 21 | workflow-aggregator ≥ 596 |
| 1.1.x | 2.452.x+ | 17 / 21 | workflow-aggregator ≥ 600 |
| 2.0.x | 2.470.x+ | 21 | workflow-aggregator ≥ 650 |

> **LTS 策略**：偶数 MAJOR 长期维护（≥ 18 个月）；奇数 MAJOR 仅维护至下个偶数发布。

### 10.5 业务方升级提示

发布到内部 Nexus Update Center 后，触发 **Dependabot** 类机器人通知所有引用仓库。

---

## 11. 调试与排错

### 11.1 本地 IDE 调试

1. 在 IntelliJ 中打开 `apex-ci-library`
2. Run → Edit Configurations → Groovy Script
   - Script path: `src/main/groovy/com/hsbc/treasury/apex/ci/Pipeline.groovy`
   - Working dir: 项目根
3. 模拟 script：

```groovy
def script = [
    echo:    { println it },
    sh:      { Map m -> println "[SH] ${m.script}" ; 0 },
    readFile:{ println "[READ] ${it}" ; '' },
    stage:   { name, Closure c -> println "── stage: ${name} ──" ; c() },
    parallel:{ Map b -> b.each { k, v -> println "[P] $k" ; v() } }
] as Object
```

### 11.2 远程调试真实 Jenkins

Jenkins 启动参数：

```bash
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005 \
     -jar jenkins.war
```

IDEA 中：`Run → Attach to Process → localhost:5005`，在 Library 代码里打断点。

### 11.3 沙箱审计日志

```bash
# Jenkins master
tail -f /var/log/jenkins/script-approval.log | grep -i apex
```

### 11.4 常见错误

| 错误 | 原因 | 解决 |
| --- | --- | --- |
| `RejectedAccessException: unclassified ...` | 调用了未审批的方法 | 提交 Script Approval |
| `NotSerializableException` | Builder 持有非 Serializable 引用 | 全部字段加 `implements Serializable` |
| `MissingMethodException` | DSL 闭包未 delegate | `body.delegate = cfg; body.resolveStrategy = Closure.DELEGATE_FIRST` |
| `Pipeline stuck` | 异步句柄未设置 timeout | 改用 `await(timeoutSeconds)` |
| `CPS exception` | 在 `@NonCPS` 外调用 Groovy 反射 | 用 `script` 步骤代替 |

---

## 12. 代码规范与 Lint

### 12.1 命名

| 类型 | 命名 | 示例 |
| --- | --- | --- |
| 包 | 全小写 | `com.hsbc.treasury.apex.ci.builders` |
| 类 | PascalCase | `JavaBuilder` |
| 方法 / 字段 | camelCase | `buildMaven` |
| 常量 | UPPER_SNAKE | `MAX_RETRY_ATTEMPTS` |
| 全局变量 | lowerCamel | `apexBuild` |
| DSL 块 | lowerCamel | `containerBuild` |

### 12.2 强制规则（CI 校验）

- **禁止** `evaluate(...)`
- **禁止** 字符串拼接 `sh`（必须数组形式）
- **禁止** `Class.forName` / 反射
- **禁止** `new Thread`
- **必须** 所有类 `implements Serializable` 并显式 `serialVersionUID`
- **必须** 跨 Pipeline 共享对象用 `@Immutable` / `@TupleConstructor`
- **必须** DSL 闭包使用 `DELEGATE_FIRST`

### 12.3 Lint 配置 `config/groovylintrc.json`

```json
{
  "extends": "recommended",
  "rules": {
    "no-eval":             { "enabled": true },
    "no-raw-sh":           { "enabled": true },
    "no-reflection":       { "enabled": true },
    "no-thread":           { "enabled": true },
    "no-system-println":   { "enabled": true },
    "require-immutable-context": { "enabled": true }
  }
}
```

### 12.4 PR 流程

1. Fork → Branch → 提交
2. 跑 `./gradlew clean build lint`（必须全绿）
3. 提交 PR 至 `apex-ci-library/main`
4. CI 自动跑：
   - 单元测试
   - PipelineUnit 集成
   - Lint
   - Sandbox Replay（真实 Jenkins）
5. Code Owner Review（≥ 1 人）
6. 合并 → 自动构建 SNAPSHOT

---

## 附录 A：发布检查清单

- [ ] `CHANGELOG.md` 已更新
- [ ] 所有新增 DSL 在 `docs/api-reference.md` 有说明
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
| 新语言 | 新建 `xxx/Builder.groovy` + 注册 + `vars/xxx.groovy` |
| 新扫描器 | 新建 `xxx/Scanner.groovy` + 注册 + `vars/xxx.groovy` |
| 新通知渠道 | 新建 `notifiers/XxxNotifier.groovy` + 注册 |
| 新报告格式 | 新建 `reporters/XxxReporter.groovy` + 注册 |
| 全局变量 | 新建 `vars/<name>.groovy` |
| 新工具链 | `resources/tools/install-<tool>.sh` |
| 新 Artifact 仓库 | 新建 `artifact/XxxClient.groovy` |
| 新 Registry | `docker/DockerRegistry.groovy` SPI |
