package com.hsbc.treasury.apex.ci.core

import com.hsbc.treasury.apex.ci.errors.ApexCIException
import org.junit.Test
import org.junit.Assert

class DynamicParamsTest {

    @Test
    void emptyValidationFails() {
        def p = new DynamicParams()
        try {
            p.validate()
            Assert.fail("Should have thrown")
        } catch (ApexCIException ex) {
            Assert.assertTrue(ex.message.contains("empty"))
        }
    }

    @Test
    void flagPropertyPositionalAreAccumulable() {
        def p = new DynamicParams()
        p.flag('--batch-mode')
        p.property('maven.test.skip', 'true')
        p.positional('clean')
        Assert.assertEquals(['--batch-mode'], p.flags)
        Assert.assertEquals(['maven.test.skip': 'true'], p.props)
        Assert.assertEquals(['clean'], p.positionals)
    }

    @Test
    void removeOperationsWork() {
        def p = new DynamicParams()
        p.flag('--foo')
        p.flag('--bar')
        p.property('k1', 'v1')
        p.property('k2', 'v2')
        p.positional('a')
        p.positional('b')

        p.removeFlag('--foo')
        p.removeProperty('k1')
        p.removePositional('a')
        Assert.assertEquals(['--bar'], p.flags)
        Assert.assertEquals(['k2': 'v2'], p.props)
        Assert.assertEquals(['b'], p.positionals)
    }

    @Test
    void asFlagListIsOrdered() {
        def p = new DynamicParams()
        p.flag('--batch-mode')
        p.property('k1', 'v1')
        p.positional('clean')

        def list = p.asFlagList()
        // flags + props + positionals
        Assert.assertEquals('--batch-mode', list[0])
        Assert.assertTrue(list.contains('k1=v1'))
        Assert.assertTrue(list.contains('clean'))
    }

    @Test
    void copyWithIsIsolated() {
        def a = new DynamicParams()
        a.flag('--foo')
        def b = a.copyWith { flag('--bar') }
        Assert.assertEquals(['--foo'], a.flags)
        Assert.assertEquals(['--foo', '--bar'], b.flags)
    }
}
