# apex-ci-library

> HSBC Treasury Apex 项目的 Jenkins 共享库（**轻量版 v2.0.0**）。
> 复用 Jenkins 原生 stage / parallel / sh，仅封装与外部系统交换的步骤。

## 主要能力

- **轻量 DSL**：`apex{}` 仅注入共享 `PipelineContext`，**不**发明新流程抽象
- **多语言构建**：`apexBuild('java' | 'node' | 'python' | 'go' | 'shell')`
- **并发扫描**：`apexScan{}` 走 Jenkins 原生 `parallel`，异常隔离、自动 timeout、门禁判断
- **沙箱安全**：所有 `sh` 用数组形式，无反射、无动态 import、无线程
- **重试策略**：`apexRetry.linear / exponential` 处理外部服务瞬时错误
- **动态参数**：`DynamicParams` 自由加减 flag / property / positional
- **Docker 构建**：`apexDocker` + `apexDocker.push` + `apexDocker.buildAndPush`
- **Nexus 发布**：`apexPublish` 支持 maven / npm / pypi / raw
- **配置加载**：`apexConfig` 解析 YAML / Properties / JSON

## 目录结构

```
apex-ci-library/
├── Jenkinsfile              # 自测试流水线（原生 pipeline 块）
├── build.sh / build.bat     # groovyc 编译 + JUnit
├── vars/                    # 全局变量（apex, apexBuild, apexScan, apexRetry...）
├── src/com/hsbc/treasury/apex/ci/
│   ├── core/                # PipelineContext / Retry / DynamicParams / Sleeper
│   ├── builders/            # JavaBuilder / NodeBuilder / PythonBuilder / GoBuilder / ShellBuilder / BuilderFactory
│   ├── scanners/            # ScanRunner / ScanResult
│   ├── docker/              # DockerBuilder / DockerPusher / DockerBuildConfig
│   ├── artifact/            # NexusClient / ArtifactPublisher
│   ├── reporters/           # ConsoleReporter
│   ├── notifiers/           # EmailNotifier
│   ├── config/              # LibraryConfig
│   ├── utils/               # Sandbox / Util / ProjectDetector
│   └── errors/              # ApexCIException / BuildException / ScanException / ConfigException
├── test/                    # JUnit 4.12 + Hamcrest 测试（含 LightweightDslTest 集成测试）
├── docker/test-env/         # 集成测试环境（Nexus3 + Registry + Jenkins）
└── docs/                    # design.md / user-guide.md / developer-guide.md
```

## 快速开始

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

    stage('Tests') {
        parallel 'unit':  { sh './mvnw test -Dtest=Unit' },
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
}
```

## 设计理念

- **流程编排** 走 Jenkins 原生 `stage` / `parallel` / `when` / `matrix`
- **库只封装**：上下文注入、配置解析、命令拼装、并发扫描与门禁、重试
- **优势**：Jenkinsfile 与官方文档 1:1 对应，IDE 跳转、CPS 转换都更稳定

## 文档

| 文档 | 目标读者 | 链接 |
| --- | --- | --- |
| `docs/design.md` | 架构师 / 库作者 | [设计文档](docs/design.md) |
| `docs/user-guide.md` | Jenkinsfile 编写者 | [用户手册](docs/user-guide.md) |
| `docs/developer-guide.md` | 库维护者 / 贡献者 | [开发者指南](docs/developer-guide.md) |

## 构建 & 测试

```bash
# Linux / macOS / Git Bash
bash build.sh

# Windows cmd
build.bat
```

> 直接用 `groovyc` 编译 + `JUnitCore` 运行所有 `*Test.groovy`。
> 无需 Gradle / Maven 依赖。

## 集成测试（真实 Jenkins）

```bash
docker compose -f docker/test-env/docker-compose.yml up -d
bash docker/test-env/test-it.sh
```

`test-it.sh` 触发 `Jenkinsfile-modules`（覆盖 apexBuild / apexScan / apexRetry / apexConfig 等所有模块），
断言 `BUILD SUCCESS` 后清理。

## 版本

当前开发版本：**v2.0.0（Lightweight）**

## 许可

MIT License，详见 [LICENSE](LICENSE)。
