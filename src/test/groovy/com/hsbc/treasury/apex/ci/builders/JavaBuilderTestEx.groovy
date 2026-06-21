package com.hsbc.treasury.apex.ci.builders

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.utils.MockScript
import org.junit.Assert
import org.junit.Test

/**
 * Java 构建器（Maven / Gradle）的全场景测试。
 * 覆盖：maven 模式、gradle 模式、properties、cliOptions、动态参数、错误路径。
 */
class JavaBuilderTestEx {

    private static Map<String, Object> fresh() {
        def s = new MockScript()
        def ctx = PipelineContext.builder().script(s).build()
        return ['ctx': ctx, 's': s]
    }

    @Test
    void mavenCommandIsAssembledWithProperties() {
        Map f = fresh()
        def ctx = f.ctx
        def s = f.s
        new JavaBuilder().execute(ctx, {
            buildTool = 'maven'
            jdk = 17
            goals = ['clean', 'verify']
            properties = ['maven.test.skip': 'true', 'maven.javadoc.skip': 'true']
        })
        String cmd = s.shCalls[0].script as String
        Assert.assertTrue(cmd.contains('mvn'))
        Assert.assertTrue(cmd.contains('clean'))
        Assert.assertTrue(cmd.contains('verify'))
        Assert.assertTrue(cmd.contains('-Dmaven.test.skip=true'))
        Assert.assertTrue(cmd.contains('-Dmaven.javadoc.skip=true'))
    }

    @Test
    void mavenCommandWithCliOptions() {
        Map f = fresh()
        def ctx = f.ctx
        def s = f.s
        new JavaBuilder().execute(ctx, {
            buildTool = 'maven'
            jdk = 21
            goals = ['package']
            cliOptions = ['--batch-mode', '-pl', 'core,api']
        })
        String cmd = s.shCalls[0].script as String
        Assert.assertTrue(cmd.contains('--batch-mode'))
        Assert.assertTrue(cmd.contains('-pl'))
        Assert.assertTrue(cmd.contains('core,api'))
    }

    @Test
    void mavenCommandWithSkipTests() {
        Map f = fresh()
        def ctx = f.ctx
        def s = f.s
        new JavaBuilder().execute(ctx, {
            buildTool = 'maven'
            jdk = 17
            goals = ['package']
            skipTests = true
        })
        String cmd = s.shCalls[0].script as String
        Assert.assertTrue(cmd.contains('-DskipTests'))
    }

    @Test
    void gradleCommandHasPrefixedProperties() {
        Map f = fresh()
        def ctx = f.ctx
        def s = f.s
        new JavaBuilder().execute(ctx, {
            buildTool = 'gradle'
            jdk = 17
            goals = ['build']
            properties = ['version': '1.0.0', 'org.gradle.jvmargs': '-Xmx2g']
        })
        String cmd = s.shCalls[0].script as String
        Assert.assertTrue(cmd.startsWith('gradle'))
        Assert.assertTrue(cmd.contains('build'))
        // Gradle 用 -P 前缀
        Assert.assertTrue(cmd.contains('-Pversion=1.0.0'))
    }

    @Test
    void gradleCommandWithSkipTestsUsesXTest() {
        Map f = fresh()
        def ctx = f.ctx
        def s = f.s
        new JavaBuilder().execute(ctx, {
            buildTool = 'gradle'
            jdk = 17
            goals = ['build']
            skipTests = true
        })
        String cmd = s.shCalls[0].script as String
        Assert.assertTrue(cmd.contains('-x'))
        Assert.assertTrue(cmd.contains('test'))
    }

    @Test
    void dynamicParamsOnTopOfMavenDefaults() {
        Map f = fresh()
        def ctx = f.ctx
        def s = f.s
        new JavaBuilder().execute(ctx, {
            buildTool = 'maven'
            jdk = 17
            goals = ['verify']
            cliOptions = ['--batch-mode']
            params { flag('--debug'); flag('--update-snapshots'); property('maven.test.failure.ignore', 'true') }
        })
        String cmd = s.shCalls[0].script as String
        Assert.assertTrue(cmd.contains('--debug'))
        Assert.assertTrue(cmd.contains('--update-snapshots'))
        Assert.assertTrue(cmd.contains('-Dmaven.test.failure.ignore=true'))
    }

    @Test
    void contextAttrsRecordedAfterBuild() {
        Map f = fresh()
        def ctx = f.ctx
        new JavaBuilder().execute(ctx, {
            buildTool = 'maven'
            jdk = 17
            goals = ['verify']
        })
        Assert.assertEquals('maven', ctx.getAttr('java.build.tool'))
        Assert.assertEquals(['verify'], ctx.getAttr('java.build.goals'))
    }
}
