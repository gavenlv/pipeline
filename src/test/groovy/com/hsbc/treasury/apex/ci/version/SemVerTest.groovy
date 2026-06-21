package com.hsbc.treasury.apex.ci.version

import com.hsbc.treasury.apex.ci.errors.ApexCIException
import org.junit.Assert
import org.junit.Test

class SemVerTest {

    @Test
    void parseSimpleVersion() {
        SemVer v = SemVer.parse('1.2.3')
        Assert.assertEquals(1, v.major)
        Assert.assertEquals(2, v.minor)
        Assert.assertEquals(3, v.patch)
        Assert.assertNull(v.pre)
        Assert.assertNull(v.buildMeta)
        Assert.assertEquals('1.2.3', v.toString())
    }

    @Test
    void parseWithLeadingV() {
        SemVer v = SemVer.parse('v2.0.0')
        Assert.assertEquals(2, v.major)
        Assert.assertEquals('2.0.0', v.toString())
    }

    @Test
    void parseWithPreRelease() {
        SemVer v = SemVer.parse('1.2.3-rc.1')
        Assert.assertEquals('rc.1', v.pre)
        Assert.assertTrue(v.isPreRelease())
        Assert.assertEquals('1.2.3-rc.1', v.toString())
    }

    @Test
    void parseWithPreReleaseAndBuild() {
        SemVer v = SemVer.parse('1.2.3-rc.1+build.42')
        Assert.assertEquals('rc.1', v.pre)
        Assert.assertEquals('build.42', v.buildMeta)
        Assert.assertEquals('1.2.3-rc.1+build.42', v.toString())
    }

    @Test
    void parseInvalidThrows() {
        try {
            SemVer.parse('1.2')
            Assert.fail("expected throw")
        } catch (ApexCIException ignore) {}
    }

    @Test
    void tryParseReturnsNullOnInvalid() {
        Assert.assertNull(SemVer.tryParse('not-a-version'))
        Assert.assertNotNull(SemVer.tryParse('1.0.0'))
    }

    @Test
    void bumpPatch() {
        Assert.assertEquals('1.2.4', SemVer.parse('1.2.3').bumpPatch().toString())
        Assert.assertEquals('0.0.1', SemVer.parse('0.0.0').bumpPatch().toString())
    }

    @Test
    void bumpMinorClearsPatch() {
        Assert.assertEquals('1.3.0', SemVer.parse('1.2.3').bumpMinor().toString())
    }

    @Test
    void bumpMajorClearsMinorAndPatch() {
        Assert.assertEquals('2.0.0', SemVer.parse('1.2.3').bumpMajor().toString())
    }

    @Test
    void bumpClearsPreRelease() {
        // 升正式版应清空预发布段
        Assert.assertEquals('1.2.4', SemVer.parse('1.2.3-rc.1').bumpPatch().toString())
        Assert.assertEquals('1.3.0', SemVer.parse('1.2.3-rc.5').bumpMinor().toString())
    }

    @Test
    void toReleaseClearsPreRelease() {
        Assert.assertEquals('1.2.3', SemVer.parse('1.2.3-rc.5').toRelease().toString())
    }

    @Test
    void withPreReleaseAndBuild() {
        SemVer v = SemVer.parse('1.2.3').withPreRelease('beta.2').withBuild('abc1234')
        Assert.assertEquals('1.2.3-beta.2+abc1234', v.toString())
    }

    @Test
    void compareToOrdersByMajor() {
        Assert.assertTrue(SemVer.parse('2.0.0') > SemVer.parse('1.99.99'))
    }

    @Test
    void compareToOrdersByMinor() {
        Assert.assertTrue(SemVer.parse('1.3.0') > SemVer.parse('1.2.99'))
    }

    @Test
    void compareToOrdersByPatch() {
        Assert.assertTrue(SemVer.parse('1.2.4') > SemVer.parse('1.2.3'))
    }

    @Test
    void preReleaseIsLessThanRelease() {
        // SemVer 2.0.0 §11: 预发布 < 正式版
        Assert.assertTrue(SemVer.parse('1.2.3-rc.1') < SemVer.parse('1.2.3'))
        Assert.assertTrue(SemVer.parse('1.2.3') > SemVer.parse('1.2.3-rc.1'))
    }

    @Test
    void equalsIgnoresBuildMeta() {
        // build 段不参与比较（SemVer 2.0.0 §10）
        SemVer a = SemVer.parse('1.2.3+build.1')
        SemVer b = SemVer.parse('1.2.3+build.2')
        Assert.assertEquals(a, b)
    }

    @Test
    void negativeVersionPartsThrow() {
        try {
            new SemVer(-1, 0, 0)
            Assert.fail("expected throw")
        } catch (ApexCIException ignore) {}
    }
}
