// =========================================================================
// apexRetry — 重试策略
//
//   apexRetry.linear(3, 1000) { sh 'curl ...' }
//   apexRetry.exponential(5, 500) { sh 'npm install' }
// =========================================================================
import com.hsbc.treasury.apex.ci.core.Retry

def linear(int attempts, long delayMs = 0L, Closure body) {
    Retry r = Retry.linear(attempts, delayMs)
    return Retry.execute(r, body)
}

def exponential(int attempts, long initialMs = 500L, double multiplier = 2.0, Closure body) {
    Retry r = Retry.exponential(attempts, initialMs, multiplier)
    return Retry.execute(r, body)
}

return this
