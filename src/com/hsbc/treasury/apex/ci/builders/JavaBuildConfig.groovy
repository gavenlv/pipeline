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
    DynamicParams params

    static JavaBuildConfig fromClosure(Closure body) {
        def cfg = new JavaBuildConfig()
        if (body == null) return cfg
        body.delegate = cfg
        body.resolveStrategy = Closure.DELEGATE_FIRST
        body()
        if (cfg.goals == null) cfg.goals = ['clean', 'package']
        if (cfg.cliOptions == null) cfg.cliOptions = []
        if (cfg.properties == null) cfg.properties = [:]
        return cfg
    }
}
