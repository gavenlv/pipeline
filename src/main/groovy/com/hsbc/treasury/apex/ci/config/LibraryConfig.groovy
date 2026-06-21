package com.hsbc.treasury.apex.ci.config

import com.hsbc.treasury.apex.ci.errors.ConfigException
import groovy.json.JsonSlurper

/**
 * 静态库配置（YAML / Properties / JSON）。
 * 实际 I/O 在脚本里完成（沙箱安全），本类提供解析与取值工具。
 */

class LibraryConfig implements Serializable {
    private static final long serialVersionUID = 1L

    private final Map<String, Object> root

    LibraryConfig(Map<String, Object> root) {
        this.root = new LinkedHashMap<>(root ?: [:])
    }

    static LibraryConfig empty() { return new LibraryConfig([:]) }

    /** 嵌套取值：'docker.registry' → root.docker.registry */
    Object get(String dottedKey) {
        if (dottedKey == null) return null
        Object cur = root
        for (String seg : dottedKey.split(/\./)) {
            if (cur instanceof Map && ((Map) cur).containsKey(seg)) {
                cur = ((Map) cur).get(seg)
            } else {
                return null
            }
        }
        return cur
    }

    String getString(String key, String defaultValue = null) {
        Object v = get(key)
        return v == null ? defaultValue : v.toString()
    }

    int getInt(String key, int defaultValue = 0) {
        Object v = get(key)
        if (v == null) return defaultValue
        if (v instanceof Number) return ((Number) v).intValue()
        try { return Integer.parseInt(v.toString()) } catch (NumberFormatException ignore) { return defaultValue }
    }

    boolean getBoolean(String key, boolean defaultValue = false) {
        Object v = get(key)
        if (v == null) return defaultValue
        if (v instanceof Boolean) return ((Boolean) v).booleanValue()
        return Boolean.parseBoolean(v.toString())
    }

    List<String> getList(String key) {
        Object v = get(key)
        if (v == null) return []
        if (v instanceof List) {
            return ((List<?>) v).collect { it == null ? '' : it.toString() }
        }
        return [v.toString()]
    }

    /** 解析 Properties 文本（key=value, 注释 #） */
    static LibraryConfig fromProperties(String text) {
        if (text == null) return empty()
        Properties p = new Properties()
        p.load(new StringReader(text))
        Map<String, Object> m = [:]
        p.stringPropertyNames().each { String k -> m.put(k, p.getProperty(k)) }
        return new LibraryConfig(m)
    }

    /** 解析简易 YAML：仅支持缩进 key:value 与 - item 结构 */
    static LibraryConfig fromYamlLite(String text) {
        if (text == null) return empty()
        // 解析每一行：{indent, type: 'kv'|'item', key, value}
        List<Map> rows = []
        List<String> lines = text.split(/\r?\n/) as List<String>
        for (String raw : lines) {
            if (!raw || raw.trim().startsWith('#')) continue
            int indent = raw.length() - raw.replaceAll('^\\s+', '').length()
            String line = raw.trim()
            if (line.startsWith('- ')) {
                String v = line.substring(2).trim()
                Object parsed = parseScalar(v)
                rows << [indent: indent, type: 'item', value: parsed]
            } else {
                int colon = line.indexOf(':')
                if (colon < 0) continue
                String k = line.substring(0, colon).trim()
                String v = line.substring(colon + 1).trim()
                Object parsed = v ? parseScalar(v) : null
                rows << [indent: indent, type: 'kv', key: k, value: parsed]
            }
        }
        // 构建嵌套结构
        Map<String, Object> root = [:]
        // 栈元素：{indent, kind: 'map'|'list', ref}
        List<Map> stack = [[indent: -1, kind: 'map', ref: root]]
        for (Map row : rows) {
            // 弹栈直到栈顶 indent < 当前行 indent（同级 list 保留）
            while (stack.size() > 1) {
                int topIndent = stack[-1].indent as int
                if (topIndent < row.indent) break
                if (topIndent == row.indent && stack[-1].kind == 'list') break
                stack.remove(stack.size() - 1)
            }
            Map top = stack[-1]
            if (row.type == 'kv') {
                if (row.value == null) {
                    if (top.kind == 'map') {
                        Map child = [:]
                        ((Map) top.ref)[row.key as String] = child
                        stack << [indent: row.indent, kind: 'map', ref: child]
                    }
                } else {
                    if (top.kind == 'map') {
                        ((Map) top.ref)[row.key as String] = row.value
                    }
                }
            } else { // item
                // 若 top 是空 map（刚 push 的 kv 子节点），把它弹掉并把父 key 替换为 list
                while (top.kind == 'map' && ((Map) top.ref).isEmpty() && stack.size() > 1
                       && (stack[-2].indent as int) < row.indent) {
                    stack.remove(stack.size() - 1)
                    top = stack[-1]
                }
                if (top.kind == 'list') {
                    ((List) top.ref) << row.value
                } else if (top.kind == 'map') {
                    // 找最近一个 value 为空 map 的 key → 把它替换为 list
                    String lastKey = findLastOpenKey((Map) top.ref)
                    if (lastKey != null) {
                        List list = []
                        ((Map) top.ref)[lastKey] = list
                        list << row.value
                        stack << [indent: row.indent, kind: 'list', ref: list]
                    } else {
                        List list = ((Map) top.ref)['_list_'] as List
                        if (list == null) {
                            list = []
                            ((Map) top.ref)['_list_'] = list
                        }
                        list << row.value
                    }
                }
            }
        }
        return new LibraryConfig(root)
    }

    private static Object parseScalar(String v) {
        if (v == 'true') return Boolean.TRUE
        if (v == 'false') return Boolean.FALSE
        if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith('\'') && v.endsWith('\''))) {
            return v.substring(1, v.length() - 1)
        }
        try { return Integer.parseInt(v) } catch (NumberFormatException ignore) { return v }
    }

    /** 在 map 中找最近一个值为空 map 的 key（按插入顺序） */
    private static String findLastOpenKey(Map m) {
        String found = null
        for (Object e : m.entrySet()) {
            Map.Entry entry = (Map.Entry) e
            if (entry.value instanceof Map && ((Map) entry.value).isEmpty()) {
                found = (String) entry.key
            }
        }
        return found
    }

    /** 解析 JSON（轻量：用 Groovy JsonSlurper） */
    static LibraryConfig fromJson(String text) {
        if (text == null) return empty()
        try {
            Object obj = new groovy.json.JsonSlurper().parseText(text)
            if (obj instanceof Map) return new LibraryConfig((Map) obj)
            return empty()
        } catch (Throwable t) {
            throw new ConfigException("Invalid JSON: ${t.message}".toString(), t)
        }
    }
}
