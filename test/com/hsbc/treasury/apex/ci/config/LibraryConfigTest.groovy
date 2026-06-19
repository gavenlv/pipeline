package com.hsbc.treasury.apex.ci.config

import org.junit.Test
import org.junit.Assert

class LibraryConfigTest {

    @Test
    void readsNestedKeys() {
        def cfg = new LibraryConfig([docker: [registry: 'ghcr.io/x', tags: ['a', 'b']], debug: true])
        Assert.assertEquals('ghcr.io/x', cfg.getString('docker.registry'))
        Assert.assertEquals(true, cfg.getBoolean('debug'))
        Assert.assertEquals(['a', 'b'], cfg.getList('docker.tags'))
    }

    @Test
    void defaultValuesWork() {
        def cfg = new LibraryConfig([:])
        Assert.assertEquals('def', cfg.getString('missing', 'def'))
        Assert.assertEquals(42, cfg.getInt('missing', 42))
        Assert.assertTrue(cfg.getBoolean('missing', true))
        Assert.assertEquals([], cfg.getList('missing'))
    }

    @Test
    void fromPropertiesParsesBasic() {
        def cfg = LibraryConfig.fromProperties('a=1\nb=hello\n# comment\nc=true'.toString())
        Assert.assertEquals('1', cfg.getString('a'))
        Assert.assertEquals('hello', cfg.getString('b'))
        Assert.assertEquals('true', cfg.getString('c'))
    }

    @Test
    void fromJsonParsesBasic() {
        def cfg = LibraryConfig.fromJson('{"a":{"b":42},"c":true}')
        Assert.assertEquals(42, cfg.getInt('a.b'))
        Assert.assertTrue(cfg.getBoolean('c'))
    }

    @Test
    void fromYamlLiteParsesNested() {
        def yaml = """
        docker:
          registry: ghcr.io/x
          tags:
            - a
            - b
        debug: true
        """.toString()
        def cfg = LibraryConfig.fromYamlLite(yaml)
        Assert.assertEquals('ghcr.io/x', cfg.getString('docker.registry'))
        Assert.assertEquals(['a', 'b'], cfg.getList('docker.tags'))
        Assert.assertTrue(cfg.getBoolean('debug'))
    }
}
