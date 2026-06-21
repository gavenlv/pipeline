# apex-ci-library

> HSBC Treasury Apex 项目的 Jenkins 共享库（**轻量版 v2.0.0**）。
> 复用 Jenkins 原生 stage / parallel / sh，仅封装与外部系统交换的步骤。
> 最后更新：2026-06-21

## 主要能力

- **轻量 DSL**：`apex{}` 仅注入共享 `PipelineContext`，**不**发明新流程抽象
- **多语言构建**：`apexBuild('java' | 'node' | 'python' | 'go' | 'shell')`，自动检测语言
- **并发扫描**：`apexScan{}` 走 Jenkins 原生 `parallel`，异常隔离、自动 timeout、门禁判断
- **沙箱安全**：所有 `sh` 用数组形式，无反射、无动态 import、无线程
- **重试策略**：`apexRetry.linear / exponential / until` 处理外部服务瞬时错误
- **动态参数**：`DynamicParams` 自由加减 flag / property / positional / extra
- **自动版本管理**：`apexVersion.auto / bump / parse` 实现 SemVer 2.0.0 五种 bump 类型
- **Docker 构建**：`apexDocker` + `apexDocker.push` + `apexDocker.buildAndPush`
- **Nexus 发布**：`apexPublish` 支持 maven / npm / pypi / raw
- **配置加载**：`apexConfig { fromYaml / fromJson / fromProperties }` 解析配置

## 6 个端到端 Jenkinsfile（已全部验证通过）

仓库自带 6 个独立的 Jenkinsfile（位于 `docker/test-env/jenkins/`），覆盖单语言、并行、扫描、版本、混合五大场景，已在本地 Jenkins 沙箱里跑过数百轮：

| 任务名 | 文件 | 覆盖场景 | 验证 |
| --- | --- | --- | --- |
| `apex-modules-test` | `Jenkinsfile-modules` | 全模块接口（config/params/build/scan/docker/publish/retry/notify/ctx）+ 集成用例 | 51 次成功，62/62 用例通过 |
| `apex-build-java` | `Jenkinsfile-build-java` | 单一 Java/Maven 构建：版本管理 → Maven → 验证 jar | 39 次成功，`target/demo.jar` 已生成 |
| `apex-parallel-build` | `Jenkinsfile-parallel-build` | 4 语言（java/node/python/go）`native parallel` 并行构建 + 产物聚合 | 25 次成功，4 个 stash 全部成功 |
| `apex-wait-scan` | `Jenkinsfile-wait-scan` | 4 个 scanner 并行 + 门禁判断 + 失败注入（`SCAN_FAIL_BRANCH`） | 24 次成功，并行性通过耗时断言 |
| `apex-version` | `Jenkinsfile-version` | 5 种 bump（patch/minor/major/release/prerelease）+ SemVer parse / max / min + 升级判定 | 24 次成功，5 种 bump 全验证 |
| `apex-mixed` | `Jenkinsfile-mixed` | 混合：版本管理 → 单构建 → 并行构建 → 等待扫描 → 失败重试 | 26 次成功，端到端串联通过 |

入口脚本：`docker/test-env/jenkins/init.groovy.d/01-seed.groovy` 会把上述 6 个任务注册到本地 Jenkins。

## 目录结构

```
apex-ci-library/
├── Jenkinsfile              # 自测试流水线（原生 pipeline 块，调用 build.sh / build.bat）
├── pom.xml                  # Maven 配置（gmavenplus + surefire）
├── build.sh / build.bat     # Maven 构建入口脚本（薄包装）
├── vars/                    # 全局变量（apex, apexBuild, apexScan, apexRetry, apexVersion...）
├── src/main/groovy/com/hsbc/treasury/apex/ci/
│   ├── core/                # PipelineContext / Retry / DynamicParams / Sleeper
│   ├── builders/            # JavaBuilder / NodeBuilder / PythonBuilder / GoBuilder / ShellBuilder / BuilderFactory
│   ├── scanners/            # ScanRunner / ScanResult
│   ├── docker/              # DockerBuilder / DockerPusher / DockerBuildConfig
│   ├── artifact/            # NexusClient / ArtifactPublisher
│   ├── reporters/           # ConsoleReporter
│   ├── notifiers/           # EmailNotifier
│   ├── config/              # LibraryConfig / ConfigBuilder / ConfigParserHelper
│   ├── utils/               # Sandbox / Util / ProjectDetector
│   ├── version/             # SemVer / VersionManager
│   └── errors/              # ApexCIException / BuildException / ScanException / ConfigException
├── src/test/groovy/         # JUnit 4.13 + Hamcrest 测试（含 LightweightDslTest / ParallelBuildTest / ScanWaitIntegrationTest / VersionUpgradeIntegrationTest）
├── resources/               # Jenkins Shared Library 资源（JCasC / templates）
├── docker/test-env/         # 集成测试环境（Nexus3 + Registry + Jenkins + 6 个 Jenkinsfile）
│   ├── docker-compose.yml
│   ├── jenkins/             # Dockerfile + JCasC + init scripts + 6 个 Jenkinsfile
│   └── test-it.sh           # 端到端集成（Nexus publish + Trivy + 库 API 集成）
└── docs/                    # design.md / user-guide.md / developer-guide.md
```

## 快速开始

### 最小流水线

```groovy
@Library('apex-ci-library@2.0.0') _

node {
    stage('Build') {
        apexBuild('java') {
            jdk = 17
            goals = ['clean', 'verify']
            params { flag('--batch-mode'); property('maven.javadoc.skip', 'true') }
        }
    }
}
```

### 完整流水线（PR 门禁）

```groovy
@Library('apex-ci-library@2.0.0') _

pipeline {
    agent any
    options { timeout(time: 30, unit: 'MINUTES') }

    stages {
        stage('Build') {
            steps {
                apexBuild('java') {
                    jdk = 17
                    goals = ['clean', 'verify']
                    params { flag('--batch-mode') }
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
                apexDocker('registry.local/app:1.0.0') { dockerfile = 'docker/Dockerfile' }
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
}
```

### 自动版本管理（一键 bump）

```groovy
environment {
    BUILD_VERSION = '1.2.3'      // 当前版本
    BUMP_TYPE     = 'patch'      // patch | minor | major | release | prerelease
    BUILD_META    = sh(returnStdout: true, script: 'git rev-parse --short HEAD').trim()
}

stage('Compute Version') {
    steps {
        script {
            def v = apexVersion.auto([
                BUILD_VERSION: env.BUILD_VERSION,
                BUMP_TYPE    : env.BUMP_TYPE,
                BUILD_META   : env.BUILD_META,
            ])
            env.APP_VERSION = v
            echo "Publishing as ${v}"
        }
    }
}
```

## 设计理念

- **流程编排** 走 Jenkins 原生 `stage` / `parallel` / `when` / `matrix`
- **库只封装**：上下文注入、配置解析、命令拼装、并发扫描与门禁、重试、版本管理
- **优势**：Jenkinsfile 与官方文档 1:1 对应，IDE 跳转、CPS 转换都更稳定
- **沙箱安全**：所有 `sh` 走数组形式；`apexConfig` 用 `ConfigBuilder` 类当 delegate 避开 CPS 误判

## 文档

| 文档 | 目标读者 | 链接 |
| --- | --- | --- |
| `docs/design.md` | 架构师 / 库作者 | [设计文档](docs/design.md) |
| `docs/user-guide.md` | Jenkinsfile 编写者 | [用户手册](docs/user-guide.md) |
| `docs/developer-guide.md` | 库维护者 / 贡献者 | [开发者指南](docs/developer-guide.md) |

## 构建 & 测试

```bash
# Linux / macOS / Git Bash
bash build.sh                  # 默认 mvn test（编译 + 单测）
bash build.sh -compile         # 仅编译
bash build.sh -package         # 打包成 JAR
bash build.sh -verify          # 跑 verify（含集成前检查）
bash build.sh -skipTests       # 跳过测试

# Windows cmd
build.bat
build.bat -package
```

> 本项目自 2026-06 起已改造为 **Maven 项目**：`pom.xml` 定义依赖与插件（`gmavenplus-plugin` 编译 Groovy、`maven-surefire-plugin` 跑 JUnit 4）。
> `build.sh` / `build.bat` 是薄包装，参数映射到 `mvn` 的标准生命周期目标。也可以直接调用：

```bash
mvn clean test                 # 等价 build.sh
mvn package                    # 等价 build.sh -package
```

构建产物：

- JAR：`target/apex-ci-library-1.0.0-SNAPSHOT.jar`（含 `vars/` 与 `resources/` 资源）
- 测试报告：`target/surefire-reports/`

## 集成测试

### 1. 端到端集成（真实 Nexus / Registry）

```bash
docker compose -f docker/test-env/docker-compose.yml up -d
bash docker/test-env/test-it.sh
```

`test-it.sh` 会：
1. 启动 Nexus3 + Registry + Jenkins 容器
2. 创建 maven/npm/pypi/raw/docker 仓库
3. 跑 Java/Maven deploy、Node publish、Python twine upload、Docker build/push
4. 跑 Trivy 容器扫描、SAST 模式匹配、SCA npm audit
5. 校验 `LibraryConfig` YAML 解析
6. 输出 `build/test-it-results.json` 总结

### 2. 6 个端到端 Pipeline（在 Jenkins UI 跑）

Jenkins 启动后会自动注册 6 个任务：

```
apex-modules-test    # 全模块接口测试
apex-build-java      # 单一 Java 构建
apex-parallel-build  # 并行多语言构建
apex-wait-scan       # 等待扫描结果
apex-version         # 自动版本管理
apex-mixed           # 混合场景
```

每个都可在 Jenkins UI 手动触发，也可通过 `test-it.sh` 串联。

## 版本

当前开发版本：**v2.0.0（Lightweight）**

| 库版本 | Jenkins LTS | JDK | Pipeline Plugin |
| --- | --- | --- | --- |
| 2.0.x | 2.470.x+ | 17 / 21 | workflow-aggregator ≥ 650 |

## 许可

MIT License，详见 [LICENSE](LICENSE)。
