// =========================================================================
// apexVersion — 自动版本管理（auto upgrade）
//
// 业务方：
//   // 基础用法：从环境变量或显式声明计算下一个版本
//   stage('Compute Version') {
//       def v = apexVersion.bump('1.2.3', 'minor') {
//           buildMeta = "${env.GIT_COMMIT_SHORT}"
//           preReleaseTag = 'rc.x'
//       }
//       // v == "1.3.0-rc.x+abc1234"
//   }
//
//   // 便捷：从环境变量自动
//   stage('Auto') {
//       def v = apexVersion.auto()          // 读 BUILD_VERSION / BUMP_TYPE / BUILD_META
//   }
//
//   // 解析与比较
//   def a = apexVersion.parse('1.2.3')
//   def b = apexVersion.parse('1.2.3-rc.1')
//   def newer = apexVersion.max(a, b)      // SemVer.compareTo
// =========================================================================
import com.hsbc.treasury.apex.ci.version.SemVer
import com.hsbc.treasury.apex.ci.version.VersionManager
import com.hsbc.treasury.apex.ci.core.PipelineContext

this.metaClass.parse = { String t -> SemVer.parse(t) }
this.metaClass.tryParse = { String t -> SemVer.tryParse(t) }
this.metaClass.max = { a, b -> (a.compareTo(b) >= 0) ? a : b }
this.metaClass.min = { a, b -> (a.compareTo(b) <= 0) ? a : b }

/** 显式声明 base + bump + 可选 preReleaseTag / buildMeta */
def bump(String base, String bumpType, Closure body = null) {
    Object script = this
    PipelineContext ctx = script.binding?.hasVariable('apexCtx') ? script.apexCtx :
        PipelineContext.builder().script(script).build()

    String pre = null
    String meta = null
    if (body != null) {
        Map opts = [:]
        body.delegate = opts
        body.resolveStrategy = Closure.DELEGATE_FIRST
        body()
        pre  = opts.preReleaseTag as String
        meta = opts.buildMeta as String
    }
    VersionManager mgr = new VersionManager(ctx, base, VersionManager.BumpType.valueOf(bumpType.toUpperCase()), pre, meta)
    String next = mgr.resolve()
    return [next, mgr]
}

def auto() {
    return auto(null)
}

/** 可显式传入 env（Map），便于在 script { } 中调用时直接拿到 BUILD_VERSION 等。 */
def auto(Map envMap) {
    Object script = this
    PipelineContext ctx
    if (script.binding?.hasVariable('apexCtx')) {
        ctx = script.apexCtx
        if (envMap != null && !envMap.isEmpty()) {
            ctx = ctx.withEnv(envMap)
            script.binding.setVariable('apexCtx', ctx)
        }
    } else {
        Map<String, String> jenkinsEnv = [:]
        // 优先用调用方显式传入的 env
        if (envMap != null) {
            envMap.each { k, v -> jenkinsEnv[k?.toString()] = v?.toString() }
        }
        ctx = PipelineContext.builder().script(script).env(jenkinsEnv).build()
    }
    return VersionManager.auto(ctx).resolve()
}

return this
