package com.hsbc.treasury.apex.ci.core

import com.hsbc.treasury.apex.ci.errors.ApexCIException

/**
 * 步骤抽象。所有 build / scan / publish 等动作都实现 Step。
 * 步骤可独立运行、串入 Stage / Pipeline。
 */

interface Step<T> extends Serializable {
    String getName()
    /** 是否对 sandbox 安全（无动态 Groovy eval） */
    boolean isSandboxSafe()
    /** 执行步骤，失败时抛 ApexCIException */
    T run(PipelineContext ctx)
}
