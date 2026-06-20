// =========================================================================
// apexRetry — 重试（轻量版）
//
//   stage('Install') {
//       apexRetry.linear(3, 1000) {
//           sh 'npm install --no-audit'
//       }
//   }
//
//   apexRetry.exponential(5, 500, 2.0) {
//       sh 'curl -fSL https://.../pkg.tgz -o pkg.tgz'
//   }
//
//   apexRetry.until(3, 2000) { status ->
//       sh 'smoke-test.sh'
//       return currentBuild.currentResult == 'SUCCESS'
//   } as Closure<Boolean>
//
// 实现：走原生 try/catch + sleep（避免 Retry.none() 在 CPS 中被误识别）
// =========================================================================
import com.hsbc.treasury.apex.ci.core.Retry

def linear(int attempts, long delayMs = 0L, Closure body) {
    return Retry.linear(attempts, delayMs).execute(body)
}

def exponential(int attempts, long initialMs = 500L, double multiplier = 2.0, Closure body) {
    return Retry.exponential(attempts, initialMs, multiplier).execute(body)
}

def until(int attempts, long delayMs = 1000L, Closure<Boolean> body) {
    Object script = this
    int attempt = 1
    long delay = delayMs
    Throwable last = null
    while (attempt <= attempts) {
        try {
            if (body.call()) return true
        } catch (Throwable t) {
            last = t
            script?.echo("[apexRetry.until] attempt ${attempt} threw: ${t.message}".toString())
        }
        if (attempt < attempts && delay > 0) {
            if (script?.respondsTo('sleep')) script.sleep((int)(delay / 1000L))
            else Thread.sleep(delay)
        }
        attempt++
        delay = (long)(delay * 2.0)
    }
    if (last != null) throw last
    return false
}

return this
