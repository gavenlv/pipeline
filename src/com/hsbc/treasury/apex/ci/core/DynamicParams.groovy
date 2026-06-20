package com.hsbc.treasury.apex.ci.core

import com.hsbc.treasury.apex.ci.errors.ApexCIException

/**
 * 通用动态参数容器。
 * Builder / Scanner 通过嵌入本对象提供"自由加减"能力：
 *   java { params { flag('--foo'); property('k','v'); positional('clean') } }
 */
class DynamicParams implements Serializable {
    private static final long serialVersionUID = 1L

    /** 长选项：--batch-mode / --update-snapshots */
    List<String> flags = []
    /** 键值对：-Dk=v 或 --opt=v */
    Map<String, String> props = [:]
    /** 位置参数：clean verify ... */
    List<String> positionals = []
    /** 业务方自定义：非命令行直接翻译，作为 extra 信息传递给 Builder */
    Map<String, Object> extras = [:]

    void flag(String f)        { flags << f }
    void property(String k, String v) { props[k] = v }
    void positional(String p)  { positionals << p }
    void extra(String k, Object v) { extras[k] = v }

    void removeFlag(String f)        { flags.removeAll { it == f } }
    void removeProperty(String k)    { props.remove(k) }
    void removePositional(String p)  { positionals.removeAll { it == p } }
    void removeExtra(String k)       { extras.remove(k) }

    /** 转为 [key, value, key, value, ...] 形式供 sh script: 数组使用 */
    List<String> asFlagList() {
        def out = []
        flags.each { out << it }
        props.each { k, v -> out << "${k}=${v}".toString() }
        positionals.each { out << it }
        return out
    }

    /** 拷贝 + 修改 */
    DynamicParams copyWith(Closure body = null) {
        def c = new DynamicParams(
            flags: new ArrayList(this.flags),
            props: new LinkedHashMap(this.props),
            positionals: new ArrayList(this.positionals),
            extras: new LinkedHashMap(this.extras)
        )
        if (body != null) {
            body.delegate = c
            body.resolveStrategy = Closure.DELEGATE_FIRST
            body()
        }
        return c
    }

    /** 校验至少包含一项，避免空对象 */
    void validate() {
        if (flags.isEmpty() && props.isEmpty() && positionals.isEmpty() && extras.isEmpty()) {
            throw new ApexCIException("DynamicParams is empty")
        }
    }

    @Override
    String toString() {
        return "DynamicParams{flags=${flags}, props=${props}, positionals=${positionals}, extras=${extras}}"
    }
}
