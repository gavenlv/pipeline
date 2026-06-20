package com.hsbc.treasury.apex.ci.builders

import com.hsbc.treasury.apex.ci.core.PipelineContext
import com.hsbc.treasury.apex.ci.core.DynamicParams
import com.hsbc.treasury.apex.ci.utils.Util

/**
 * Builder 抽象基类（轻量版）。
 * - 不再实现 Step 接口
 * - 抽象 execute(ctx, body, opts)，子类负责把闭包解析为 cfg 并执行
 */
abstract class AbstractBuilder implements Serializable {
    private static final long serialVersionUID = 1L

    abstract String getLanguage()
    abstract boolean detect(File projectDir)
    abstract Object parseConfig(Closure body)
    /** 主流程：子类的 Config 解析 + shell 执行 */
    abstract Object execute(PipelineContext ctx, Closure body, Map opts = [:])

    /** 解析的 cmd 数组辅助：把 DynamicParams 注入到基础 cmd 末尾 */
    protected List<String> mergeDynamicParams(List<String> base, DynamicParams params) {
        if (params == null) return base
        List<String> out = new ArrayList<>(base)
        params.flags.each { out << it }
        params.props.each { k, v -> out << "-D${k}=${v}".toString() }
        params.positionals.each { out << it }
        return out
    }

    /** 兼容 windows 平台，自动添加 .bat / .cmd 后缀 */
    protected List<String> platformAdapt(List<String> cmd, PipelineContext ctx) {
        if (cmd == null || cmd.isEmpty()) return cmd
        if (!Util.isWindows(ctx)) return cmd
        def head = cmd[0]
        if (head in ['mvn', 'gradle', 'npm', 'yarn', 'pnpm', 'go', 'python', 'pip', 'docker', 'sonar-scanner']) {
            def newCmd = new ArrayList<String>()
            newCmd << (head + '.cmd')
            newCmd.addAll(cmd.subList(1, cmd.size()))
            return newCmd
        }
        return cmd
    }
}
