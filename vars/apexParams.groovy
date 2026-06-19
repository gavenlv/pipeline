// =========================================================================
// apexParams — DynamicParams 工厂
//
//   def p = apexParams()
//   p.flag('--batch-mode')
//   p.property('maven.javadoc.skip','true')
//   p.positional('clean')
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
