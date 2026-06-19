// =========================================================================
// apexBuild — 独立构建入口（无需 apex { } 包装）
//
//   apexBuild(java) { jdk = 21; goals = ['clean','package'] }
//   apexBuild('node') { scripts = ['install','build','test'] }
// =========================================================================
import com.hsbc.treasury.apex.ci.builders.JavaBuilder
import com.hsbc.treasury.apex.ci.builders.NodeBuilder
import com.hsbc.treasury.apex.ci.builders.PythonBuilder
import com.hsbc.treasury.apex.ci.builders.GoBuilder
import com.hsbc.treasury.apex.ci.builders.ShellBuilder
import com.hsbc.treasury.apex.ci.builders.BuilderFactory
import com.hsbc.treasury.apex.ci.core.PipelineContext

def call(String language, Closure body) {
    Object script = this
    PipelineContext ctx = script.binding?.hasVariable('apexCtx') ? script.apexCtx :
        PipelineContext.builder().script(script).build()
    def builder = BuilderFactory.of(language)
    return builder.execute(ctx, body)
}

def call(Object ignored, Closure body) {
    // 兼容 apexBuild(java) { ... } 这种语法糖
    Object script = this
    PipelineContext ctx = script.binding?.hasVariable('apexCtx') ? script.apexCtx :
        PipelineContext.builder().script(script).build()
    return new JavaBuilder().execute(ctx, body)
}

return this
