# apex-ci-library

> HSBC Treasury Apex 项目的 Jenkins 共享库。
> 提供模块化、可组合、沙箱安全、并发执行的 CI 流水线构建能力。

## 主要能力

- 统一入口 `apex{}` DSL，配置驱动 + 自由组装
- 模块化构建器：Java / Node / Python / Go / Shell
- 沙箱安全（不依赖 script 反射 / 动态 Groovy）
- 并发执行：`parallel` 阶段、异步结果 `AsyncResult` / `AsyncCollector`
- 异步安全扫描：SAST / SCA / 容器镜像扫描，结果聚合与门禁
- 动态参数：`DynamicParams` 支持 flags / properties / positional 自由增删
- Docker 构建：多架构、BuildKit secrets、缓存策略
- Nexus 制品发布：Maven / npm / PyPI / raw
- YAML / Properties / JSON 配置加载

## 目录结构

```
apex-ci-library/
├── Jenkinsfile              # 自测试流水线
├── build.sh / build.bat     # 编译 & 单元测试
├── vars/                    # 全局变量（apex, apexBuild, apexScan...）
├── src/com/hsbc/treasury/apex/ci/
│   ├── core/                # Pipeline / Stage / Step / Context / DynamicParams / Retry
│   ├── builders/            # JavaBuilder / NodeBuilder / PythonBuilder / ...
│   ├── scanners/            # SastScanner / ContainerScanner / ScannerCollector
│   ├── docker/              # DockerBuilder / DockerBuildConfig
│   ├── artifact/            # NexusClient / ArtifactPublisher
│   ├── config/              # LibraryConfig (YAML/Properties/JSON)
│   ├── errors/              # ApexCIException
│   └── utils/               # Logger / IO / env helpers
├── resources/               # 默认 YAML 配置
├── test/                    # JUnit 4.12 + Hamcrest 测试
└── docs/                    # design.md / user-guide.md / developer-guide.md
```

## 快速开始

```groovy
@Library('apex-ci-library@1.0.0') _

apex {
    appName = 'apex-treasury-svc'
    configFile = 'apex.yaml'   // 可选

    stages {
        stage('Build') {
            java {
                tool = 'jdk17'
                dynamicParams {
                    flag('skipTests')
                    property('maven.test.failure.ignore', 'false')
                }
            }
        }
        stage('Scan') {
            parallel {
                sast  { tool = 'sonar' }
                sca   { tool = 'owasp' }
                image { tool = 'trivy' }
            }
        }
        stage('Docker') {
            docker {
                dockerfile = 'Dockerfile'
                tags = ['${env.BUILD_NUMBER}', 'latest']
                platforms = ['linux/amd64', 'linux/arm64']
            }
        }
    }
}
```

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

目标：`clean` / `compile` / `test` / `all`（默认 all）

## 版本

当前开发版本：v1.0.0

## 许可

MIT License，详见 [LICENSE](LICENSE)。
