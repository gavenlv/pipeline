package com.hsbc.treasury.apex.ci.utils

import com.hsbc.treasury.apex.ci.core.PipelineContext

/**
 * 工具方法集合。
 */
class Util implements Serializable {
    private static final long serialVersionUID = 1L

    /** 时间单位人化 */
    static String humanizeDuration(long ms) {
        if (ms < 1000) return "${ms}ms"
        long s = (long) (ms / 1000L)
        if (s < 60) return "${s}s"
        long m = (long) (s / 60L)
        long r = s % 60
        if (m < 60) return "${m}m${r}s"
        long h = (long) (m / 60L)
        return "${h}h${m % 60}m"
    }

    /** 平台判断：是否 Windows Jenkins agent */
    static boolean isWindows(PipelineContext ctx) {
        if (ctx?.script == null) return false
        try {
            if (ctx.script.respondsTo('isUnix')) {
                return !ctx.script.isUnix()
            }
        } catch (Throwable ignore) { }
        String os = System.getProperty('os.name', '').toLowerCase()
        return os.contains('win')
    }

    /** 在指定 dir 下的相对路径转绝对（script.workspace 为根） */
    static String resolvePath(PipelineContext ctx, String relative) {
        if (relative == null) return null
        if (relative == '..' || relative.startsWith('/') || relative.matches('^([A-Za-z]:).*')) {
            return relative
        }
        return "${ctx.workDir}/${relative}".toString()
    }
}
