package com.hsbc.treasury.apex.ci.builders

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.errors.BuildException
import com.hsbc.treasury.apex.ci.utils.MockScript
import org.junit.Assert
import org.junit.Test

/**
 * Python 构建器（pip / poetry / pipenv）的全场景测试。
 */
class PythonBuilderTest {

    private static Map<String, Object> fresh() {
        def s = new MockScript()
        def ctx = PipelineContext.builder().script(s).build()
        return ['ctx': ctx, 's': s]
    }

    @Test
    void detectsPythonProjectByPyproject() {
        File d = File.createTempDir()
        try {
            new File(d, 'pyproject.toml').text = '[tool.poetry]'
            Assert.assertTrue(new PythonBuilder().detect(d))
        } finally { d.deleteDir() }
    }

    @Test
    void detectsPythonProjectBySetupPy() {
        File d = File.createTempDir()
        try {
            new File(d, 'setup.py').text = ''
            Assert.assertTrue(new PythonBuilder().detect(d))
        } finally { d.deleteDir() }
    }

    @Test
    void detectsPythonProjectByRequirementsTxt() {
        File d = File.createTempDir()
        try {
            new File(d, 'requirements.txt').text = 'requests'
            Assert.assertTrue(new PythonBuilder().detect(d))
        } finally { d.deleteDir() }
    }

    @Test
    void doesNotDetectPythonWhenNoMarker() {
        File d = File.createTempDir()
        try {
            Assert.assertFalse(new PythonBuilder().detect(d))
        } finally { d.deleteDir() }
    }

    @Test
    void pipCommandUsesPython3() {
        Map f = fresh()
        def ctx = f.ctx
        def s = f.s
        new PythonBuilder().execute(ctx, {
            packageManager = 'pip'
            pythonVersion = 3
            commands = ['install', 'pytest', 'test']
        })
        String cmd = s.shCalls[0].script as String
        Assert.assertTrue(cmd.contains('python3'))
        Assert.assertTrue(cmd.contains('install'))
    }

    @Test
    void poetryCommandRuns() {
        Map f = fresh()
        def ctx = f.ctx
        def s = f.s
        new PythonBuilder().execute(ctx, {
            packageManager = 'poetry'
            commands = ['install', 'test']
        })
        String cmd = s.shCalls[0].script as String
        Assert.assertTrue(cmd.contains('poetry'))
        Assert.assertTrue(cmd.contains('install'))
    }

    @Test
    void pipenvCommandRuns() {
        Map f = fresh()
        def ctx = f.ctx
        def s = f.s
        new PythonBuilder().execute(ctx, {
            packageManager = 'pipenv'
            commands = ['install', 'test']
        })
        String cmd = s.shCalls[0].script as String
        Assert.assertTrue(cmd.contains('pipenv'))
    }

    @Test
    void unsupportedPackageManagerFailsFast() {
        Map f = fresh()
        def ctx = f.ctx
        try {
            new PythonBuilder().execute(ctx, { packageManager = 'conda'; commands = ['install'] })
            Assert.fail("expected throw")
        } catch (BuildException ex) {
            Assert.assertTrue(ex.message.contains('packageManager'))
        }
    }

    @Test
    void emptyCommandsFailsFast() {
        Map f = fresh()
        def ctx = f.ctx
        try {
            new PythonBuilder().execute(ctx, { packageManager = 'pip'; commands = [] })
            Assert.fail("expected throw")
        } catch (BuildException ex) {
            Assert.assertTrue(ex.message.contains('commands'))
        }
    }

    @Test
    void dynamicParamsAppended() {
        Map f = fresh()
        def ctx = f.ctx
        def s = f.s
        new PythonBuilder().execute(ctx, {
            packageManager = 'pip'
            commands = ['install', '-r', 'requirements.txt']
            params { flag('--quiet'); property('index-url', 'https://pypi.org/simple') }
        })
        String cmd = s.shCalls[0].script as String
        Assert.assertTrue(cmd.contains('--quiet'))
        Assert.assertTrue(cmd.contains('index-url=https://pypi.org/simple'))
    }

    @Test
    void contextAttrsRecordedAfterBuild() {
        Map f = fresh()
        def ctx = f.ctx
        new PythonBuilder().execute(ctx, { packageManager = 'poetry'; commands = ['install'] })
        Assert.assertEquals('poetry', ctx.getAttr('python.build.pm'))
    }
}
