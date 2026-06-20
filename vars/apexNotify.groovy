// =========================================================================
// apexNotify — 邮件 / 通知（轻量版）
//
//   stage('Notify') {
//       apexNotify { to = ['a@x','b@y']; subject = 'CI done'; body = 'all good' }
//   }
//
//   stage('Notify') {
//       apexNotify(to: ['a@x','b@y'], subject: 'CI done', body: 'all good')
//   }
// =========================================================================
import com.hsbc.treasury.apex.ci.notifiers.EmailNotifier
import com.hsbc.treasury.apex.ci.core.PipelineContext

def call(Map args = [:], Closure body = null) {
    Object script = this
    PipelineContext ctx = script.binding?.hasVariable('apexCtx') ? script.apexCtx :
        PipelineContext.builder().script(script).build()

    EmailNotifier n = new EmailNotifier()
    if (args) {
        n.to      = (args.to ?: []) as List
        n.subject = (args.subject ?: '') as String
        n.body    = (args.body ?: '') as String
        n.from    = (args.from ?: n.from) as String
        n.smtp    = (args.smtp ?: n.smtp) as String
    }
    if (body != null) {
        body.delegate = n
        body.resolveStrategy = Closure.DELEGATE_FIRST
        body()
    }
    script.stage('notify') {
        n.notify(ctx, 'OK', [], script.currentBuild?.absoluteUrl?.toString() ?: '')
    }
    return n
}

def call(Closure body) { call([:], body) }

return this
