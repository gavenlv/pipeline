package com.hsbc.treasury.apex.ci.builders

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.utils.MockScript
import com.hsbc.treasury.apex.ci.errors.BuildException
import org.junit.Test
import org.junit.Assert

class BuilderFactoryTest {

    @Test
    void supportsAllLanguages() {
        Assert.assertNotNull(BuilderFactory.of('java'))
        Assert.assertNotNull(BuilderFactory.of('node'))
        Assert.assertNotNull(BuilderFactory.of('python'))
        Assert.assertNotNull(BuilderFactory.of('go'))
        Assert.assertNotNull(BuilderFactory.of('shell'))
    }

    @Test
    void rejectsUnknownLanguage() {
        try {
            BuilderFactory.of('rust')
            Assert.fail("expected throw")
        } catch (Exception ex) {
            Assert.assertTrue(ex.message.contains('rust') || ex.message.contains('No builder'))
        }
    }

    @Test
    void autoDetectPicksJava() {
        File d = File.createTempDir()
        try {
            new File(d, 'pom.xml').text = '<project/>'
            Assert.assertEquals('java', BuilderFactory.autoDetect(d))
        } finally { d.deleteDir() }
    }

    @Test
    void autoDetectPicksNode() {
        File d = File.createTempDir()
        try {
            new File(d, 'package.json').text = '{}'
            Assert.assertEquals('node', BuilderFactory.autoDetect(d))
        } finally { d.deleteDir() }
    }

    @Test
    void nodeBuilderRejectsInvalidPm() {
        def s = new MockScript()
        def ctx = PipelineContext.builder().script(s).build()
        try {
            new NodeBuilder().execute(ctx, { packageManager = 'bun'; scripts = ['build'] })
            Assert.fail("expected")
        } catch (BuildException ex) {
            Assert.assertTrue(ex.message.contains('packageManager'))
        }
    }

    @Test
    void pythonBuilderRuns() {
        def s = new MockScript()
        def ctx = PipelineContext.builder().script(s).build()
        new PythonBuilder().execute(ctx, { packageManager = 'pip'; commands = ['install', 'test'] })
        Assert.assertTrue(s.shCalls.size() >= 1)
        String script = s.shCalls[0].script as String
        Assert.assertTrue(script.contains('python3') || script.contains('python'))
        Assert.assertTrue(script.contains('install'))
    }

    @Test
    void goBuilderRuns() {
        def s = new MockScript()
        def ctx = PipelineContext.builder().script(s).build()
        new GoBuilder().execute(ctx, { commands = ['build', 'test']; mainPackage = './...' })
        Assert.assertTrue(s.shCalls.size() >= 1)
        String script = s.shCalls[0].script as String
        Assert.assertTrue(script.contains('go'))
        Assert.assertTrue(script.contains('./...'))
    }
}
