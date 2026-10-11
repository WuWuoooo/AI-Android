package com.ai.android.agent.tools

import com.ai.android.MainApp
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * 第五轮 Bug 3：多模态 Agent 工具（文生图 / 语音生成 / 识图）。
 * 经 MainApp.instance.providerManager.multimodal 调用，能力门控：未配置时返回友好提示。
 */

// ---------- 文生图 ----------
class GenerateImageTool : ToolExecutor {
    override val name = "generate_image"

    override suspend fun execute(args: JsonObject): String {
        val multimodal = MainApp.instance.providerManager.multimodal
        if (!multimodal.hasImage) {
            return "⚠️ 未配置文生图 Provider，请到 设置 → 模型与能力 → 多模态绑定 IMAGE 能力后再试。"
        }
        val prompt = args.requireStr("prompt", "例如：一只在草地上奔跑的橘猫")
        val size = args.str("size", "1024x1024")
        val result = multimodal.image(prompt, size)
        if (!result.success) return "❌ 文生图失败：${result.error}"

        // 保存 base64 到 /sdcard/AiAndroidImages/（minSdk 24 用 android.util.Base64）
        val dir = File("/sdcard/AiAndroidImages")
        dir.mkdirs()
        val f = File(dir, "img_${System.currentTimeMillis()}.png")
        val bytes = android.util.Base64.decode(result.base64, android.util.Base64.NO_WRAP)
        f.writeBytes(bytes)
        return "✅ 图片已生成，路径：${f.absolutePath}（${bytes.size / 1024} KB）"
    }
}

// ---------- 语音生成（TTS）----------
class GenerateSpeechTool : ToolExecutor {
    override val name = "generate_speech"

    override suspend fun execute(args: JsonObject): String {
        val multimodal = MainApp.instance.providerManager.multimodal
        if (!multimodal.hasTts) {
            return "⚠️ 未配置 TTS Provider，请到 设置 → 模型与能力 → 多模态绑定 TTS 能力后再试。"
        }
                val text = args.requireStr("text", "要合成的正文")
        val voice = args.str("voice", "冰糖")
        val bytes = multimodal.tts(text, voice)
        if (bytes.isEmpty()) return "❌ TTS 未返回音频"
        val dir = File("/sdcard/AiAndroidAudio")
        dir.mkdirs()
        val f = File(dir, "tts_${System.currentTimeMillis()}.wav")
        f.writeBytes(bytes)
        return "✅ 语音已生成，路径：${f.absolutePath}（${bytes.size / 1024} KB）"
    }
}

// ---------- 识图 ----------
class AnalyzeImageTool : ToolExecutor {
    override val name = "analyze_image"

    override suspend fun execute(args: JsonObject): String {
        val multimodal = MainApp.instance.providerManager.multimodal
        if (!multimodal.hasVision) {
            return "⚠️ 未配置识图（VISION）Provider，请到 设置 → 模型与能力 → 多模态绑定 VISION 能力后再试。"
        }
        // 支持两种输入：base64 或文件路径（二选一）
        val b64Input = args.str("image_base64")
        val pathInput = args.str("image_path")
        val b64 = if (b64Input.isNotBlank()) {
            b64Input
        } else {
            if (pathInput.isBlank()) {
                return "❌ 缺少参数：image_base64 或 image_path 至少填一个"
            }
            val f = File(pathInput)
            if (!f.exists()) return "❌ 图片不存在：$pathInput"
            android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
        }
        val question = args.str("question", "请描述这张图片的内容")
        val answer = multimodal.vision(b64, question)
        return answer.ifBlank { "（识图服务返回空）" }
    }
}
