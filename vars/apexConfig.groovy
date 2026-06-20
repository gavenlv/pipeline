// =========================================================================
// apexConfig — 配置解析（轻量版）
//
// 用法：
//   def cfg = apexConfig.fromYaml(readFile('apex.yaml'))
//   def cfg = apexConfig.fromProperties(readFile('apex.properties'))
//   def cfg = apexConfig.fromJson(readFile('apex.json'))
//
// 或 builder 形式：
//   def cfg = apexConfig {
//       fromYaml text: readFile('apex.yaml')
//   }
// =========================================================================
import com.hsbc.treasury.apex.ci.config.LibraryConfig
import com.hsbc.treasury.apex.ci.errors.ApexCIException

this.metaClass.fromYaml = { String t -> LibraryConfig.fromYamlLite(t) }
this.metaClass.fromProperties = { String t -> LibraryConfig.fromProperties(t) }
this.metaClass.fromJson = { String t -> LibraryConfig.fromJson(t) }
this.metaClass.emptyConfig = { LibraryConfig.empty() }

def call(Closure body) {
    String text = null
    String format = 'properties'
    body.delegate = [
        fromYaml:       { String t -> text = t; format = 'yaml' },
        fromProperties: { String t -> text = t; format = 'properties' },
        fromJson:       { String t -> text = t; format = 'json' }
    ]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body()
    if (text == null) throw new ApexCIException("apexConfig: must call fromYaml/fromProperties/fromJson")
    switch (format) {
        case 'yaml':       return LibraryConfig.fromYamlLite(text)
        case 'json':       return LibraryConfig.fromJson(text)
        case 'properties': return LibraryConfig.fromProperties(text)
        default:           return LibraryConfig.fromProperties(text)
    }
}

return this
