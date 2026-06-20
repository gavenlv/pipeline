package com.hsbc.treasury.apex.ci.builders

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.core.DynamicParams
import com.hsbc.treasury.apex.ci.utils.Sandbox
import com.hsbc.treasury.apex.ci.errors.ApexCIException

/**
 * Java / Maven / Gradle 构建配置。
 *
 *   java {
 *       jdk = 21
 *       buildTool = 'maven'                // maven | gradle
 *       goals = ['clean', 'package']
 *       properties = ['maven.javadoc.skip': 'true']
 *       cliOptions = ['-pl', 'core,api', '-am', '--batch-mode']
 *   }
 */

class JavaBuildConfig implements Serializable {
    private static final long serialVersionUID = 1L

    int jdk = 17
    String buildTool = 'maven'           // maven | gradle
    List<String> goals = ['clean', 'package']
    Map<String, String> properties = [:]
    List<String> cliOptions = []
    String mvnExecutable = 'mvn'
    String gradleExecutable = 'gradle'
    boolean skipTests = false
    DynamicParams params = new DynamicParams()

    /**
     * 兼容两种调用方式：
     *   1) params = new DynamicParams(...)
     *   2) params { flag(...); property(...) }
     *      → 闭包形式：复用现有对象，避免覆盖
     */
    void setParams(DynamicParams p) { this.params = (p != null) ? p : new DynamicParams() }

    /**
     * 直接接受 Closure 参数的方法。
     * 由于 Groovy 的属性名解析优先级，"params { ... }" 写法在某些场景下
     * 会被错误地解析为对 DynamicParams 实例的 methodMissing 调用。
     * 我们额外提供一个显式方法，确保 DSL 能稳定工作。
     */
    Object params(Closure body) {
        if (this.params == null) this.params = new DynamicParams()
        body.delegate = this.params
        body.resolveStrategy = Closure.DELEGATE_FIRST
        body()
        return this
    }

    void setParams(Closure body) {
        if (this.params == null) this.params = new DynamicParams()
        body.delegate = this.params
        body.resolveStrategy = Closure.DELEGATE_FIRST
        body()
    }

    static JavaBuildConfig fromClosure(Closure body) {
        def cfg = new JavaBuildConfig()
        if (body == null) return cfg
        body.delegate = cfg
        body.resolveStrategy = Closure.DELEGATE_FIRST
        body()
        if (cfg.goals == null) cfg.goals = ['clean', 'package']
        if (cfg.cliOptions == null) cfg.cliOptions = []
        if (cfg.properties == null) cfg.properties = [:]
        if (cfg.params == null) cfg.params = new DynamicParams()
        return cfg
    }
}
