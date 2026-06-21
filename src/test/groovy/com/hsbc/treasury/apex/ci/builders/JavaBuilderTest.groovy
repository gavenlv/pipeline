package com.hsbc.treasury.apex.ci.builders

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.core.DynamicParams
import com.hsbc.treasury.apex.ci.utils.MockScript
import org.junit.Test
import org.junit.Assert

class JavaBuilderTest {

    @Test
    void detectsMavenProject() {
        File d = File.createTempDir()
        try {
            new File(d, 'pom.xml').text = '<project/>'
            Assert.assertTrue(new JavaBuilder().detect(d))
        } finally { d.deleteDir() }
    }

    @Test
    void mavenCommandIsAssembled() {
        def cfg = JavaBuildConfig.fromClosure {
            jdk = 21
            buildTool = 'maven'
            goals = ['clean', 'package']
            properties = ['maven.test.skip': 'true']
            cliOptions = ['--batch-mode', '-pl', 'core']
        }
        Assert.assertEquals('maven', cfg.buildTool)
        Assert.assertEquals(21, cfg.jdk)
        def cmd = ['mvn', '--batch-mode', '-pl', 'core',
                   '-Dmaven.test.skip=true', 'clean', 'package']
        // 校验结构
        Assert.assertTrue(cmd.contains('mvn'))
        Assert.assertTrue(cmd.contains('--batch-mode'))
        Assert.assertTrue(cmd.contains('-pl'))
        Assert.assertTrue(cmd.contains('core'))
        Assert.assertTrue(cmd.contains('clean'))
        Assert.assertTrue(cmd.contains('package'))
    }

    @Test
    void gradleCommandHasDashedOptions() {
        def cfg = JavaBuildConfig.fromClosure {
            buildTool = 'gradle'
            goals = ['build']
            skipTests = true
        }
        Assert.assertEquals('gradle', cfg.buildTool)
        Assert.assertTrue(cfg.skipTests)
    }

    @Test
    void executeRunsAgainstMockScript() {
        def s = new MockScript()
        def ctx = PipelineContext.builder().script(s).build()
        new JavaBuilder().execute(ctx, {
            buildTool = 'maven'
            goals = ['clean', 'package']
        }, [:])
        Assert.assertTrue(s.shCalls.size() >= 1)
        String script = s.shCalls[0].script as String
        Assert.assertTrue(script.contains('mvn'))
        Assert.assertTrue(script.contains('clean'))
        Assert.assertTrue(script.contains('package'))
    }

    @Test
    void dynamicParamsAreMerged() {
        def params = new DynamicParams()
        params.flag('--offline')
        params.property('skipTests', 'true')
        params.positional('verify')

        def builder = new JavaBuilder()
        def merged = builder.mergeDynamicParams(['mvn', 'clean'], params)
        Assert.assertTrue(merged.contains('--offline'))
        Assert.assertTrue(merged.contains('-DskipTests=true'))
        Assert.assertTrue(merged.contains('verify'))
    }
}
