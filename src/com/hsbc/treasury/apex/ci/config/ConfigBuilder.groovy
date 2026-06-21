package com.hsbc.treasury.apex.ci.config

/**
 * ConfigBuilder — 给 apexConfig { ... } 闭包用的真实 Groovy builder。
 *
 * 在 CPS 沙箱下，Map 作为 closure delegate 时，方法调用会绕过 delegate
 * 直接走到 CpsScript.invokeMethod -> DSL 查找，导致 `fromYaml` 被误判
 * 为 DSL 步骤。用一个 Serializable Groovy 类当 delegate 可以避开这个
 * 问题，因为 GroovyClassDispatcher 会先在目标类上查找同名方法。
 */
class ConfigBuilder implements Serializable {
    private static final long serialVersionUID = 1L

    String text = null
    String format = 'properties'  // yaml | json | properties

    void fromYaml(Map args)      { text = args?.text?.toString(); format = 'yaml' }
    void fromJson(Map args)      { text = args?.text?.toString(); format = 'json' }
    void fromProperties(Map args){ text = args?.text?.toString(); format = 'properties' }

    LibraryConfig resolve() {
        if (text == null) {
            throw new com.hsbc.treasury.apex.ci.errors.ApexCIException(
                "apexConfig: must call fromYaml/fromProperties/fromJson")
        }
        switch (format) {
            case 'yaml':       return LibraryConfig.fromYamlLite(text)
            case 'json':       return LibraryConfig.fromJson(text)
            case 'properties': return LibraryConfig.fromProperties(text)
            default:           return LibraryConfig.fromProperties(text)
        }
    }
}
