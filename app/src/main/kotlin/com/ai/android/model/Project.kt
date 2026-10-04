package com.ai.android.model

import kotlinx.serialization.Serializable

/** 项目文件夹：把相关历史对话归组，并为每个项目保存独立的项目记忆 */
@Serializable
data class Project(
    val id: String,
    var name: String,
    val createdAt: Long = System.currentTimeMillis(),
)
