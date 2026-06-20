// =========================================================================
// apex — apex-ci-library 主入口
//
//   apex {
//       stage('Build') { it.step(new JavaBuilder()); java { jdk = 21; buildTool = 'maven' } }
//       stage('Scans') { it.withParallel(true); scanner { sast { ... }; sca { ... } } }
//   }
//
// 沙箱安全：本文件不使用 Groovy eval，所有动态逻辑走配置对象。
// =========================================================================
import com.hsbc.treasury.apex.ci.core.Pipeline
import com.hsbc.treasury.apex.ci.core.PipelineBuilder
import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.core.Stage
import com.hsbc.treasury.apex.ci.core.StageSpec
import com.hsbc.treasury.apex.ci.core.CollectedResult
import com.hsbc.treasury.apex.ci.core.Retry
import com.hsbc.treasury.apex.ci.core.LambdaStep
import com.hsbc.treasury.apex.ci.config.LibraryConfig
import com.hsbc.treasury.apex.ci.scanners.ScannerCollector
import com.hsbc.treasury.apex.ci.builders.*
import com.hsbc.treasury.apex.ci.docker.DockerBuilder
import com.hsbc.treasury.apex.ci.docker.DockerPusher
import com.hsbc.treasury.apex.ci.artifact.NexusClient
import com.hsbc.treasury.apex.ci.artifact.ArtifactPublisher
import com.hsbc.treasury.apex.ci.reporters.ConsoleReporter
import com.hsbc.treasury.apex.ci.notifiers.EmailNotifier

def call(Map args = [:], Closure body) {
    Object script = args.script ?: this
    String name = (args.name ?: 'apex-pipeline').toString()
    boolean failFast = args.failFast != null ? (boolean) args.failFast : true
    String description = args.description
    String nodeLabel = args.node
    Map<String, String> env = (args.env ?: [:]) as Map<String, String>
    Map<String, Object> params = (args.params ?: [:]) as Map<String, Object>
    Map<String, Object> attrs = (args.attrs ?: [:]) as Map<String, Object>

    PipelineContext ctx = PipelineContext.builder()
            .script(script)
            .env(env)
            .params(params)
            .attrs(attrs)
            .nodeLabel(nodeLabel)
            .build()

    // 把 ctx 暂存到 script 绑定的属性，便于子闭包（java { ... } / scanner { ... }）取用
    script.binding?.setVariable('apexCtx', ctx)
    script.binding?.setVariable('apexPipeline', null) // 稍后回填

    PipelineBuilder pb = Pipeline.builder()
            .name(name)
            .description(description)
            .withFailFast(failFast)

    // 外层 body：用户写 stage(...) 即可
    body.delegate = new ApexSpec(pb, ctx, script)
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body()

    Pipeline pipeline = pb.build()
    script.binding?.setVariable('apexPipeline', pipeline)
    pipeline.run(ctx)
    return pipeline
}

/** apex { } 闭包的 delegate：提供 stage / java / node / python / scanner / containerBuild / publish / docker 入口 */
class ApexSpec implements Serializable {
    private static final long serialVersionUID = 1L
    final PipelineBuilder pb
    final PipelineContext ctx
    final Object script
    ApexSpec(PipelineBuilder pb, PipelineContext ctx, Object script) {
        this.pb = pb
        this.ctx = ctx
        this.script = script
    }

    void stage(String name, Closure body) { pb.stage(name, body) }
    void withFailFast(boolean f)         { pb.withFailFast(f) }

    void java(Closure body) {
        // 将 java { } 配置阶段注册为 LambdaStep，body 解析时调用 JavaBuilder.execute
        pb.stage('Build-Java', { StageSpec s ->
            s.step('java-build', { PipelineContext c ->
                new JavaBuilder().execute(c, body)
            })
        })
    }
    void node(Closure body) {
        pb.stage('Build-Node', { StageSpec s ->
            s.step('node-build', { PipelineContext c ->
                new NodeBuilder().execute(c, body)
            })
        })
    }
    void python(Closure body) {
        pb.stage('Build-Python', { StageSpec s ->
            s.step('python-build', { PipelineContext c ->
                new PythonBuilder().execute(c, body)
            })
        })
    }
    void go(Closure body) {
        pb.stage('Build-Go', { StageSpec s ->
            s.step('go-build', { PipelineContext c ->
                new GoBuilder().execute(c, body)
            })
        })
    }
    void shell(Closure body) {
        pb.stage('Build-Shell', { StageSpec s ->
            s.step('shell-build', { PipelineContext c ->
                new ShellBuilder().execute(c, body)
            })
        })
    }

    /** scanner 收集器入口 */
    ScannerCollector scanner(Closure body) {
        ScannerCollector sc = new ScannerCollector()
        sc.withScript(script)
        body.delegate = sc
        body.resolveStrategy = Closure.DELEGATE_FIRST
        body()
        pb.stage('Security-Scans', { StageSpec s ->
            s.step('scanner-run', { PipelineContext c -> sc.run(c) })
        })
        pb.stage('Security-Gates', { StageSpec sg ->
            sg.step('scanner-gates', { PipelineContext c ->
                // 在同一阶段里收集并 gate
                List<CollectedResult<com.hsbc.treasury.apex.ci.scanners.ScanResult>> results = sc.run(c)
                sc.assertPassed(results)
                List<com.hsbc.treasury.apex.ci.scanners.ScanResult> values = results.findAll { it.status == 'OK' }.collect { it.value }
                new ConsoleReporter().reportScan(c, values)
            })
        })
        return sc
    }

    /** containerBuild 入口 */
    void containerBuild(String imageRef, Closure body) {
        pb.stage('Container-Build', { StageSpec s ->
            s.step('docker-build', { PipelineContext c ->
                new DockerBuilder().build(c, imageRef, body)
            })
        })
    }

    void containerPush(String imageRef, String credentialsId = null) {
        pb.stage('Container-Push', { StageSpec s ->
            s.step('docker-push', { PipelineContext c ->
                new DockerPusher().push(c, imageRef, credentialsId)
            })
        })
    }

    /** 制品发布 */
    void publish(String baseUrl, String repository, String format, Closure body) {
        NexusClient client = NexusClient.of(baseUrl, repository, format, null)
        ArtifactPublisher publisher = new ArtifactPublisher(client)
        pb.stage('Publish-' + format, { StageSpec s ->
            s.step('nexus-publish', { PipelineContext c ->
                body.delegate = publisher
                body.resolveStrategy = Closure.DELEGATE_FIRST
                body(c)
            })
        })
    }

    /** 通用 step（完全自由） */
    void step(String name, Closure body) {
        pb.stage('Custom', { StageSpec s ->
            s.step(name, { PipelineContext c -> body(c) })
        })
    }

    /** 读静态库配置 */
    LibraryConfig config(Closure body) {
        LibraryConfig cfg = LibraryConfig.empty()
        body.delegate = cfg
        body.resolveStrategy = Closure.DELEGATE_FIRST
        body()
        return cfg
    }
}

return this
