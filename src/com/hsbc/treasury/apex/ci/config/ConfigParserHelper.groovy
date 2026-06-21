package com.hsbc.treasury.apex.ci.config

/**
 * ConfigParserHelper — 静态辅助类，把 YAML / JSON / Properties 解析逻辑
 * 从 vars/apexConfig.groovy 抽出来，避免 script-style 方法在 CPS 下被
 * 误判为 DSL 步骤（CpsScript.invokeMethod -> DSL.invokeMethod）。
 *
 * 使用方式：
 *   import com.hsbc.treasury.apex.ci.config.ConfigParserHelper
 *   def cfg = ConfigParserHelper.fromYaml(text)
 */
class ConfigParserHelper implements Serializable {
    private static final long serialVersionUID = 1L

    static LibraryConfig fromYaml(String text) {
        return LibraryConfig.fromYamlLite(text)
    }

    static LibraryConfig fromJson(String text) {
        return LibraryConfig.fromJson(text)
    }

    static LibraryConfig fromProperties(String text) {
        return LibraryConfig.fromProperties(text)
    }
}
