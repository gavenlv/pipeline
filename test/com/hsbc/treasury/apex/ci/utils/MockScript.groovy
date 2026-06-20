package com.hsbc.treasury.apex.ci.utils

import com.hsbc.treasury.apex.ci.core.PipelineContext

/**
 * 测试用 mock script。
 * 模拟 Jenkins CPS script.echo / sh / parallel / withCredentials / stage / readFile 等。
 * 行为可被测试方通过 attribute 验证。
 */
class MockScript implements Serializable {
    private static final long serialVersionUID = 1L

    List<String> echos = []
    List<Map> shCalls = []
    List<Map> stages = []
    List<Map> parallels = []
    List<Map> withCredentials = []
    Map<String, Object> env = [:]
    Map<String, Object> params = [:]
    String workspace = '/tmp/mock-ws'
    String isUnix = 'true'
    /** 若设置，则 script.sh 抛错模拟失败 */
    Closure shBehavior = null
    /** stage 调用回填：stage(name) { body } */
    Object currentBuild

    void echo(String msg) { echos << msg?.toString() }

    Object sh(Map args) {
        if (shBehavior) return shBehavior.call(args)
        Map rec = [script: args.script, returnStdout: !!args.returnStdout, returnStatus: !!args.returnStatus]
        shCalls << rec
        if (args.returnStdout) {
            // 模拟输出：返回脚本最后一行（echo 之后）
            String s = (args.script ?: '').toString()
            def lines = s.split('\n')
            return lines[-1] ?: ''
        }
        if (args.returnStatus) return 0
        return null
    }

    Object stage(String name, Closure body) {
        stages << [name: name]
        if (body) return body.call()
        return null
    }

    Object parallel(Map blocks) {
        parallels << [blocks: new ArrayList(blocks.keySet())]
        // 顺序执行，并返回 [name -> result] 形式的 map（与 Jenkins 行为一致）
        Map<String, Object> out = new LinkedHashMap<>()
        blocks.each { k, Closure v -> out[k as String] = v.call() }
        return out
    }

    Object withCredentials(List bindings, Closure body) {
        withCredentials << [bindings: bindings]
        return body.call()
    }

    /**
     * 模拟 Jenkins 原生 timeout() step：包装闭包，超时则抛。
     * 测试中可以传入 timeoutBehavior 改变默认行为。
     */
    Closure timeoutBehavior = null
    Object timeout(Map args, Closure body) {
        if (timeoutBehavior) return timeoutBehavior.call(args, body)
        // 默认：直接执行 body，忽略超时
        return body.call()
    }

    /**
     * 模拟 catchError：执行 body，若有异常则捕获并返回结果。
     * 返回 [code, result]；当提供 buildResult 时按需重写 currentBuild。
     */
    Object catchError(Closure body) {
        try {
            def r = body.call()
            return [code: 'SUCCESS', result: r]
        } catch (Throwable t) {
            return [code: 'FAILURE', result: null, error: t]
        }
    }

    String pwd() { return workspace }
    String readFile(String path) {
        return new File(path).exists() ? new File(path).text : ''
    }

    /** 业务方获取 PipelineContext：测试代码不应通过 binding 拿 */
    PipelineContext apexCtx
}
