package com.hsbc.treasury.apex.ci.builders

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.core.DynamicParams
import com.hsbc.treasury.apex.ci.utils.Sandbox
import com.hsbc.treasury.apex.ci.utils.Util
import com.hsbc.treasury.apex.ci.errors.ApexCIException
import com.hsbc.treasury.apex.ci.errors.BuildException

/**
 * Java / Maven / Gradle 构建器。
 * - 沙箱安全：使用数组形式 sh，避免字符串拼接
 * - DynamicParams 自由加减：调用方传入的 property / flag / positional 自动追加
 */

class JavaBuilder extends AbstractBuilder implements Serializable {
    private static final long serialVersionUID = 1L

    @Override
    String getLanguage() { return 'java' }

    @Override
    boolean detect(File projectDir) {
        return new File(projectDir ?: new File('.'), 'pom.xml').exists() ||
               new File(projectDir ?: new File('.'), 'build.gradle').exists() ||
               new File(projectDir ?: new File('.'), 'build.gradle.kts').exists()
    }

    @Override
    Object parseConfig(Closure body) { return JavaBuildConfig.fromClosure(body) }

    Object execute(PipelineContext ctx) {
        throw new com.hsbc.treasury.apex.ci.errors.BuildException("JavaBuilder.execute requires config. Use execute(ctx, body, opts).")
    }

    @Override
    Object execute(PipelineContext ctx, Closure body, Map opts = [:]) {
        JavaBuildConfig cfg = (JavaBuildConfig) parseConfig(body)
        validate(cfg)
        ctx?.log("==> [Java:${cfg.buildTool}] jdk=${cfg.jdk} goals=${cfg.goals}")

        List<String> cmd = buildCommand(cfg)
        if (cfg.params != null) {
            cmd = mergeDynamicParams(cmd, cfg.params)
        }
        cmd = platformAdapt(cmd, ctx)

        if (opts?.shellStyle == 'string' || opts?.stringShell == true) {
            // 走原生 sh 字符串形式
            String rendered = Sandbox.render(cmd)
            ctx?.script?.sh(rendered)
        } else {
            // 走 array 形式（默认）
            Sandbox.runShell(ctx, cmd, "java-${cfg.buildTool}".toString())
        }
        ctx?.setAttr("java.build.tool", cfg.buildTool)
        ctx?.setAttr("java.build.goals", cfg.goals)
        return ['tool': cfg.buildTool, 'goals': cfg.goals]
    }

    private static void validate(JavaBuildConfig cfg) {
        if (cfg.buildTool == null) throw new com.hsbc.treasury.apex.ci.errors.BuildException("buildTool is required")
        if (cfg.goals == null || cfg.goals.isEmpty()) throw new com.hsbc.treasury.apex.ci.errors.BuildException("goals cannot be empty")
    }

    private static List<String> buildCommand(JavaBuildConfig cfg) {
        switch (cfg.buildTool) {
            case 'maven':   return buildMavenCommand(cfg)
            case 'gradle':  return buildGradleCommand(cfg)
            default: throw new com.hsbc.treasury.apex.ci.errors.BuildException("Unsupported Java build tool: ${cfg.buildTool}")
        }
    }

    private static List<String> buildMavenCommand(JavaBuildConfig cfg) {
        List<String> cmd = [cfg.mvnExecutable]
        cfg.cliOptions.each { cmd << it }
        if (cfg.skipTests) cmd << '-DskipTests'
        cfg.properties.each { k, v -> cmd << "-D${k}=${v}".toString() }
        cfg.goals.each { cmd << it }
        return cmd
    }

    private static List<String> buildGradleCommand(JavaBuildConfig cfg) {
        List<String> cmd = [cfg.gradleExecutable]
        cfg.cliOptions.each { cmd << it }
        if (cfg.skipTests) cmd << '-x' << 'test'
        cfg.goals.each { cmd << it }
        cfg.properties.each { k, v -> cmd << "-P${k}=${v}".toString() }
        return cmd
    }
}
