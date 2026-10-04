package com.ai.android.skills

/**
 * 技能定义（从 SKILL.md 解析）。
 * 格式：
 * ---
 * name: 技能名
 * description: 一句话说明
 * triggers: 触发词1, 触发词2
 * ---
 * 正文（给模型的提示词/执行步骤）
 */
data class SkillDefinition(
    val name: String,
    val description: String,
    val triggers: List<String>,
    val content: String,
    val source: String = "builtin",
) {
    /** 转成可展示的完整 SKILL.md 文本（用于分享/导出） */
    fun toMarkdown(): String = buildString {
        appendLine("---")
        appendLine("name: $name")
        appendLine("description: $description")
        appendLine("triggers: ${triggers.joinToString(", ")}")
        appendLine("---")
        appendLine()
        appendLine(content)
    }
}

/** SKILL.md 解析器（轻量手写 front matter 解析，不引入 YAML 库） */
object SkillParser {

    fun parse(raw: String, source: String = "unknown"): SkillDefinition? {
        val text = raw.trim()
        if (!text.startsWith("---")) return null
        val end = text.indexOf("\n---", 3)
        if (end < 0) return null

        val header = text.substring(3, end).trim()
        val body = text.substring(end + 4).trim()
        if (body.isEmpty()) return null

        val fields = mutableMapOf<String, String>()
        header.lines().forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                fields[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
        }

        val name = fields["name"]?.takeIf { it.isNotBlank() } ?: return null
        val triggers = (fields["triggers"] ?: "").split(",", "，")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        return SkillDefinition(
            name = name,
            description = fields["description"].orEmpty(),
            triggers = triggers,
            content = body,
            source = source,
        )
    }
}
