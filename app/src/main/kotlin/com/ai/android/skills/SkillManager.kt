package com.ai.android.skills

import java.io.File

/**
 * 技能管理器：加载内置技能 + 外部目录技能（/sdcard/AiAndroid/skills 下的 .md 文件）。
 * 用户可以用文本编辑器写 SKILL.md 放进目录，重启后自动加载。
 */
class SkillManager {

    private val skills = mutableListOf<SkillDefinition>()

    /** 默认技能目录 */
    fun defaultDir(): File = File("/sdcard/AiAndroid/skills")

    /** 重新加载：内置 + 目录 */
    fun loadAll(): Int {
        skills.clear()
        skills.addAll(BuiltinSkills.all)
        var loaded = 0
        val dir = defaultDir()
        if (dir.isDirectory) {
            dir.listFiles { f -> f.isFile && f.name.endsWith(".md", ignoreCase = true) }
                ?.sortedBy { it.name.lowercase() }
                ?.forEach { f ->
                    runCatching {
                        SkillParser.parse(f.readText(), "dir:${f.name}")?.let {
                            skills.add(it)
                            loaded++
                        }
                    }
                }
        }
        return loaded
    }

    fun all(): List<SkillDefinition> = skills.toList()

    fun byName(name: String): SkillDefinition? = skills.firstOrNull { it.name.equals(name, true) }

    /** 按触发词匹配技能 */
    fun match(text: String): List<SkillDefinition> =
        skills.filter { s -> s.triggers.any { text.contains(it, ignoreCase = true) } }

    /** 根据用户输入生成注入系统提示词的内容；无匹配返回空串 */
    fun promptFor(userText: String): String {
        val m = match(userText)
        if (m.isEmpty()) return ""
        return m.joinToString("\n\n") { "【技能：${it.name}】\n${it.content}" }
    }

    /** 导出技能为 SKILL.md（便于分享） */
    fun export(skill: SkillDefinition): String {
        val dir = defaultDir().apply { mkdirs() }
        val safeName = skill.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val f = File(dir, "$safeName.md")
        f.writeText(skill.toMarkdown())
        return f.absolutePath
    }

    /** 删除目录中的技能文件（内置技能不可删） */
    fun deleteFile(skill: SkillDefinition): Boolean {
        if (!skill.source.startsWith("dir:")) return false
        val name = skill.source.removePrefix("dir:")
        return File(defaultDir(), name).delete()
    }
}
