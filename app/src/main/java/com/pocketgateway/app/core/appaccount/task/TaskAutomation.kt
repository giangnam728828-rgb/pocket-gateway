package com.pocketgateway.app.core.appaccount.task

import com.pocketgateway.app.core.appaccount.WorkBuddyApi
import com.pocketgateway.app.core.model.Channel
import kotlinx.coroutines.delay
import kotlin.random.Random

/** 单个任务的自动执行结果。 */
data class AutoTaskResult(
    val code: String,
    val title: String,
    val ok: Boolean,
    val message: String,
)

/** 一个可自动执行的任务动作。 */
interface TaskAction {
    val codes: Set<String>
    suspend fun execute(ch: Channel): Pair<Boolean, String>
}

/** 通过真实对话点亮“对话类”任务（会自动消耗少量积分）。 */
private class ChatAction(
    override val codes: Set<String>,
    private val times: Int,
) : TaskAction {
    override suspend fun execute(ch: Channel): Pair<Boolean, String> {
        val model = ch.models.firstOrNull { it.isNotBlank() }?.replace("\"", "") ?: "glm-5.2"
        var okCount = 0
        for (i in 1..times) {
            if (i > 1) delay(Random.nextLong(3000, 7000))
            val body = """{"model":"$model","messages":[{"role":"user","content":"你好"}],"stream":true}"""
            val (code, _) = WorkBuddyApi.chatStream(ch, body, 120_000)
            if (code !in 200..299) return false to "对话失败：HTTP $code"
            okCount++
        }
        return true to "已完成 $okCount 次对话"
    }
}

/** 指定模型对话（Model_chat_* 任务）。 */
private object ModelChatAction : TaskAction {
    override val codes: Set<String> = setOf("Model_chat_GLM5.2")

    override suspend fun execute(ch: Channel): Pair<Boolean, String> {
        WorkBuddyApi.reportChatActivity(ch, "glm-5.2", "GLM-5.2")
        val body = """{"model":"glm-5.2","messages":[{"role":"user","content":"你好"}],"stream":true}"""
        val (code, _) = WorkBuddyApi.chatStream(ch, body, 120_000)
        return if (code in 200..299) true to "已使用 GLM-5.2 对话" else false to "对话失败：HTTP $code"
    }
}

/** 首只 Buddy：上报活跃 → 同意协议 → 领取。 */
private object FirstBuddyAction : TaskAction {
    override val codes: Set<String> = setOf("first_buddy")

    override suspend fun execute(ch: Channel): Pair<Boolean, String> {
        WorkBuddyApi.reportChatActivity(ch)
        val (agreeOk, agreeMsg) = WorkBuddyApi.buddyAgreement(ch)
        if (!agreeOk) return false to "协议确认失败：$agreeMsg"
        val (firstOk, firstMsg) = WorkBuddyApi.buddyFirst(ch)
        return if (firstOk) true to "已领养首只 Buddy" else false to "领养失败：$firstMsg"
    }
}

/** 任务 code → 动作注册表；未命中视为“需手动”。 */
object TaskActionRegistry {

    private val chat5 = ChatAction(setOf("chat_5"), 5)
    private val chat1 = ChatAction(
        setOf(
            "Sequential_Tasks_1", "Sequential_Tasks_2", "Sequential_Tasks_3",
            "Sequential_Tasks_4", "Sequential_Tasks_5", "Sequential_Tasks_6",
            "Sequential_Tasks_7", "automation_1", "template_5",
        ),
        1,
    )

    private val actions: List<TaskAction> = listOf(chat5, chat1, ModelChatAction, FirstBuddyAction)

    fun actionFor(code: String): TaskAction? {
        val c = code.trim()
        return actions.firstOrNull { c in it.codes }
    }

    fun canAuto(code: String): Boolean = actionFor(code) != null

    /** 无法自动完成时的原因说明。 */
    fun manualReason(code: String): String = when (code.trim()) {
        "RichMeow_Chat" -> "需要桌面端事件链，当前版本暂不支持自动完成"
        "Buddy_App", "Buddy_App_QQ" -> "需要 App 端事件链，请手动完成"
        "Library_read", "create_canvas", "Hp_Appearance",
        "Expert_lighthouse", "school_season", "black_cat",
        -> "需要客户端交互才能完成，请手动操作"
        else -> "需要真实使用行为，请先在客户端完成再回来领取"
    }
}

/**
 * 任务自动化引擎。
 *
 * 流程：接取未接取任务 → 对可自动完成的任务执行真实动作 → 重新领奖。
 * 限制：单并发、动作间 3–8 秒抖动、失败即停（429/11102），避免触发风控。
 */
class TaskAutomationEngine {

    suspend fun run(
        ch: Channel,
        onProgress: (String) -> Unit = {},
    ): List<AutoTaskResult> {
        val results = mutableListOf<AutoTaskResult>()

        val initial = WorkBuddyApi.fetchTasks(ch).first
        val pending = initial.filter { !it.claimed && !it.locked && !it.accepted }.map { it.code }
        if (pending.isNotEmpty()) {
            onProgress("接取 ${pending.size} 个任务…")
            WorkBuddyApi.acceptTasks(ch, pending)
            delay(1500)
        }

        val tasks = WorkBuddyApi.fetchTasks(ch).first
        for (t in tasks) {
            if (t.claimed || t.locked) continue

            if (t.claimable) {
                val (ok, msg) = WorkBuddyApi.claimTask(ch, t.code)
                results += AutoTaskResult(t.code, t.title, ok, msg)
                delay(Random.nextLong(1200, 2400))
                continue
            }

            val action = TaskActionRegistry.actionFor(t.code) ?: continue
            onProgress("执行：${t.title}")
            val (ok, msg) = action.execute(ch)
            if (!ok) {
                results += AutoTaskResult(t.code, t.title, false, msg)
                if (msg.contains("429") || msg.contains("11102") || msg.contains("403")) break
                continue
            }
            delay(Random.nextLong(2000, 4000))
            val (claimed, claimMsg) = WorkBuddyApi.claimTask(ch, t.code)
            results += AutoTaskResult(
                t.code,
                t.title,
                claimed,
                if (claimed) "已完成并领取" else "动作完成，奖励未达标：$claimMsg",
            )
            delay(Random.nextLong(3000, 7000))
        }
        return results
    }
}
