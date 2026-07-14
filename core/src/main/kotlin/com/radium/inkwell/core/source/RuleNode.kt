package com.radium.inkwell.core.source

/** 书源用到了当前版本不支持的能力（如 js 引擎） */
class UnsupportedRuleException(message: String) : Exception(message)

/**
 * 规则求值中间表示（IR）。由 [LegadoRuleAnalyzer] 从原生 Legado 规则串编译得到，
 * 交给 [RuleEvaluator] 求值。这是引擎内部结构，不是对外的规则语言。
 */
sealed interface RuleNode {
    /** jayway JSONPath */
    data class JsonPath(val path: String) : RuleNode

    /** Legado 默认语法，原样交给 [LegadoSelector] 求值（索引作用于匹配集，CSS 表达不了） */
    data class Legado(val rule: String) : RuleNode

    /** XPath（JsoupXpath）；Legado 里写作 `@XPath:` 或以 `//` 开头 */
    data class XPath(val path: String) : RuleNode

    /** 对上下文（元素取 outerHtml，可匹配属性/脚本；否则取文本）做正则提取，$1 优先 */
    data class RegexRule(val pattern: String) : RuleNode

    /** 字面量/模板，支持 {{var}} */
    data class Literal(val template: String) : RuleNode

    /** JS 脚本规则；script 为解码后的源码，需注入 ScriptRuntime 才能求值 */
    data class Js(val script: String) : RuleNode

    /** 后处理管道 */
    data class Pipe(val source: RuleNode, val ops: List<PipeOp>) : RuleNode

    /** 回退：依次尝试，取第一个非空结果 */
    data class Fallback(val options: List<RuleNode>) : RuleNode

    /** 拼接：各部分结果顺序合并 */
    data class Concat(val parts: List<RuleNode>) : RuleNode
}

sealed interface PipeOp {
    /** `##正则##替换`（Legado replaceRegex）；replacement 为空表示删除匹配 */
    data class RegexReplace(val pattern: String, val replacement: String) : PipeOp

    /** 对每个结果执行 JS（绑定 result），返回值替换该结果 */
    data class Js(val script: String) : PipeOp

    /**
     * `@put:{"变量名":"规则"}`：把规则在当前内容上求值的结果存进变量表，供后续 `@get:{变量名}` 取用。
     * 只有副作用，不改变管道里的值。目录→正文传参极常用。
     */
    data class Put(val spec: String) : PipeOp

    /**
     * 把上一步的字符串结果当作**新内容**，再用这条 Legado 规则求值一次。
     * Legado 把规则串按 `<js>` 切成若干段顺序流水执行，前一段的输出即后一段的输入。
     */
    data class Rule(val rule: String) : PipeOp
}
