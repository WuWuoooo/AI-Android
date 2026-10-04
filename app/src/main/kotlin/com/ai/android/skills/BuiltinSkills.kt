package com.ai.android.skills

/**
 * 4 个内置技能示例。
 */
object BuiltinSkills {

    val all: List<SkillDefinition> get() = SKILLS

    val defaultTriggerWords: List<String> get() = SKILLS.flatMap { it.triggers }

    private val SKILLS = listOf(

        SkillDefinition(
            name = "文件整理",
            description = "扫描目录，按文件类型自动归档整理",
            triggers = listOf("整理文件", "整理目录", "归档文件", "清理下载", "整理下载"),
            source = "builtin",
            content = """
# 文件整理流程

1. 先用 list_dir_tree 查看目标目录（默认 /sdcard/Download），max_depth=2。
2. 规划分类子目录：
   - 图片（jpg/png/gif/webp）→ Pictures
   - 文档（pdf/doc/txt/md）→ Documents
   - 安装包（apk）→ Apk
   - 压缩包（zip/rar/7z）→ Archives
   - 其他 → Others
3. 用 create_dir 建好缺失的分类子目录。
4. 逐个用 move_file 移动文件；遇到同名冲突时给文件名加序号后缀（_1、_2）。
5. 隐藏文件（以 . 开头）不动；不确定类型的不动。
6. 整理完成后用 list_files 复查，然后向用户汇报：
   - 共移动多少个文件
   - 各分类的数量
   - 跳过或失败的文件及原因
""".trimIndent(),
        ),

        SkillDefinition(
            name = "手机自动化",
            description = "通过无障碍服务操控其他 App 完成界面操作",
            triggers = listOf("打开应用", "帮我点", "操控手机", "自动操作", "帮我操作"),
            source = "builtin",
            content = """
# 手机界面自动化流程

1. 先确认无障碍服务：调用 accessibility_control action=screen。
   若报错「服务未连接」，提醒用户到 系统设置 → 无障碍 →「AI Android 手机操控」开启，然后停止操作等待用户。
2. 用 launch_app 打开目标应用（优先用 package 包名，如 com.tencent.mm），再 sleep 1000 等界面加载。
3. 用 screen 读取当前界面控件文本与坐标。
   - 若 screen 返回空（App 限制读取），改用 screenshot 视觉方案。
4. 点击优先级：
   - 先 click_text（按控件文字点击）
   - 找不到文字时用截图 + tap 坐标；坐标必须按截图上的刻度读取。
5. 输入文字：先 tap 输入框 → sleep 800 → 再 input；失败时文本已复制到剪贴板，提示用户长按粘贴。
6. 每次关键操作后用 sleep + screen（或 screenshot）确认界面变化。
7. 敏感操作（支付、删除内容、发送消息、修改密码）必须先用 ask_user 征得用户同意。
8. 全部完成后调用 accessibility_control action=stop_projection 停止屏幕共享。
""".trimIndent(),
        ),

        SkillDefinition(
            name = "网页速读",
            description = "联网搜索多个来源，抓取正文并交叉总结",
            triggers = listOf("搜索一下", "帮我查", "查一下", "最新消息", "网页总结"),
            source = "builtin",
            content = """
# 网页速读流程

1. 用 web_search 搜索关键词，从结果中挑 2~4 条最相关的链接。
2. 用 fetch_url 抓取每条链接的正文（失败就换下一条，最多重试 3 条）。
3. 交叉比对：
   - 多个来源一致的信息 → 作为结论
   - 来源之间有冲突 → 明确标注分歧点
4. 输出结构：
   - 一句话结论
   - 要点列表（每条尽量附来源链接）
   - 备注（未证实 / 抓取失败的部分）
5. 抓不到或不确定的内容要明说，绝对不要编造。
""".trimIndent(),
        ),

        SkillDefinition(
            name = "定时管家",
            description = "创建、查看、取消定时任务（到点自动执行）",
            triggers = listOf("定时", "每天提醒", "定时任务", "提醒我", "每周"),
            source = "builtin",
            content = """
# 定时任务流程

1. 先向用户确认三件事：任务要做什么、多久执行一次、几点执行。
2. 用 create_scheduled_task 创建：
   - 一次性：type=ONCE
   - 间隔重复：type=INTERVAL + interval_minutes（分钟）
   - 每天：type=DAILY + time_of_day=HH:mm
   - 每周：type=WEEKLY + days_of_week=1,2,3（1=周一 … 7=周日）+ time_of_day=HH:mm
   prompt 要写清楚独立的执行指令（到点时 Agent 会按这段话执行，不依赖当前对话上下文）。
3. 创建后用 list_scheduled_tasks 确认登记成功，并把 id 和下次执行时间告诉用户。
4. 用户要取消时用 cancel_scheduled_task（传 id）。
""".trimIndent(),
        ),
    )
}
