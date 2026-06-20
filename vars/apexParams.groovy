// =========================================================================
// apexParams — DynamicParams 工厂（轻量版）
//
//   def p = apexParams()
//   p.flag('--batch-mode')
//   p.property('maven.javadoc.skip','true')
//   p.positional('clean')
//
//   def q = apexParams { flag('-DskipTests'); positional('install') }
//
//   def r = apexParams().copyWith()  // 复制后添加 / 删除参数
//   r.addFlag('--quiet')
//   r.removeFlag('--batch-mode')
// =========================================================================
import com.hsbc.treasury.apex.ci.core.DynamicParams

def call() {
    return new DynamicParams()
}

def call(Closure body) {
    def p = new DynamicParams()
    body.delegate = p
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body()
    return p
}

return this
