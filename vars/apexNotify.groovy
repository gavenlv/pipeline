// =========================================================================
// apexNotify — 邮件 / 通知
//
//   apexNotify { to(['a@x','b@y']); subject('CI done'); body('all good') }
// =========================================================================
import com.hsbc.treasury.apex.ci.notifiers.EmailNotifier
import com.hsbc.treasury.apex.ci.core.PipelineContext

def call(Closure body) {
    Object script = this
    PipelineContext ctx = script.binding?.hasVariable('apexCtx') ? script.apexCtx :
        PipelineContext.builder().script(script).build()

    EmailNotifier n = new EmailNotifier()
    body.delegate = n
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body()

    script.stage('notify') {
        n.notify(ctx, 'OK', [], script.currentBuild?.absoluteUrl?.toString() ?: '')
    }
    return n
}

return this
