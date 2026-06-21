// =========================================================================
// apexConfig — 配置解析（轻量版）
//
// 用法：
//   def cfg = apexConfig(text)              // 默认为 fromYaml
//   def cfg = apexConfig { fromYaml text: '...' }
//   def cfg = apexConfig { fromJson text: '...' }
//   def cfg = apexConfig { fromProperties text: '...' }
//
// 实现要点：
// - script 类只暴露 def call(String) / def call(Closure)，避免 CPS 把
//   同名方法误判为 DSL 步骤
// - 闭包内用 ConfigBuilder（src/）当 delegate，因为 CPS 不会把 Groovy
//   类的方法误判为 DSL
// - 实际解析逻辑在 LibraryConfig（src/）里
// =========================================================================
import com.hsbc.treasury.apex.ci.config.LibraryConfig
import com.hsbc.treasury.apex.ci.config.ConfigBuilder
import com.hsbc.treasury.apex.ci.config.ConfigParserHelper

/** 字符串快捷：apexConfig(text) 默认按 YAML 解析。 */
def call(String text) {
    return ConfigParserHelper.fromYaml(text)
}

/** Builder 形式：apexConfig { fromYaml text: '...' } */
def call(Closure body) {
    ConfigBuilder b = new ConfigBuilder()
    body.delegate = b
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body()
    return b.resolve()
}

return this
