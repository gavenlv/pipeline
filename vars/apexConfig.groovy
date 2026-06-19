// =========================================================================
// apexConfig — 读取静态库配置
//
//   def cfg = apexConfig { fromYaml(file('apex.yaml').text) }
// =========================================================================
import com.hsbc.treasury.apex.ci.config.LibraryConfig
import com.hsbc.treasury.apex.ci.errors.ConfigException

def call(Closure body) {
    Object script = this
    String text = null
    String format = 'properties'    // properties | yaml | json
    String path = null

    body.delegate = [
        setText: { String t -> text = t },
        fromFile: { String p -> path = p },
        fromProperties: { String t -> text = t; format = 'properties' },
        fromYaml: { String t -> text = t; format = 'yaml' },
        fromJson: { String t -> text = t; format = 'json' },
        getText: { -> text }
    ]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body()

    if (text == null && path != null && script != null) {
        text = script.readFile(path)
    }
    if (text == null) throw new ConfigException("apexConfig requires text or file path")

    switch (format) {
        case 'properties': return LibraryConfig.fromProperties(text)
        case 'yaml':       return LibraryConfig.fromYamlLite(text)
        case 'json':       return LibraryConfig.fromJson(text)
        default:           return LibraryConfig.fromProperties(text)
    }
}

return this
