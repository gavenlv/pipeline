package com.hsbc.treasury.apex.ci.builders

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.errors.ApexCIException

/**
 * Builder 注册表与工厂。
 */

class BuilderFactory implements Serializable {
    private static final long serialVersionUID = 1L

    private static final Map<String, AbstractBuilder> REGISTRY = new LinkedHashMap<>()
    static {
        REGISTRY['java']   = new JavaBuilder()
        REGISTRY['node']   = new NodeBuilder()
        REGISTRY['python'] = new PythonBuilder()
        REGISTRY['go']     = new GoBuilder()
        REGISTRY['shell']  = new ShellBuilder()
    }

    static AbstractBuilder of(String language) {
        if (language == null) throw new ApexCIException("language required")
        def b = REGISTRY[language.toLowerCase()]
        if (b == null) throw new ApexCIException("No builder for language: ${language}")
        return b
    }

    static List<String> supportedLanguages() { return new ArrayList<>(REGISTRY.keySet()) }

    static String autoDetect(File projectDir) {
        for (Map.Entry<String, AbstractBuilder> e : REGISTRY.entrySet()) {
            if (e.value.detect(projectDir)) return e.key
        }
        return 'shell'
    }
}
