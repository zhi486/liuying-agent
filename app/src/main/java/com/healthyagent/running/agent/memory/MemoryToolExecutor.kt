package com.healthyagent.running.agent.memory

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** 只由原生宿主提供；工具参数不能设置身份、授权或本轮用户原文。尚未注册到 Bridge。 */
data class MemoryToolTurn(
    val ownerId: String,
    val scope: String,
    val grantGeneration: String,
    val turnId: String,
    val startedAtMillis: Long,
    val userText: String,
    val factsAllowed: Boolean = false,
) {
    override fun toString() = "MemoryToolTurn(内容已隐藏)"
}

data class MemoryToolGrant(val scope: String, val generation: String, val factsAllowed: Boolean = false)

sealed interface MemoryToolCall {
    data class Search(val query: String) : MemoryToolCall {
        override fun toString() = "Search(内容已隐藏)"
    }
    data class Upsert(val category: String, val evidence: String, val target: MemoryRef?) : MemoryToolCall {
        override fun toString() = "Upsert(内容已隐藏)"
    }
}

sealed interface MemoryToolResult {
    data class Found(val items: List<MemoryRecord>) : MemoryToolResult
    data class Saved(val memory: MemoryRecord, val replayed: Boolean) : MemoryToolResult
    data class Rejected(val reason: String) : MemoryToolResult
}

/** 不存正文快照；删除记忆后重放只返回冲突，不能从回执重新取回正文。 */
@Entity(tableName = "agent_memory_tool_receipts")
data class MemoryToolReceipt(
    @PrimaryKey val operationId: String,
    val ownerId: String,
    val requestHash: String,
    val contentHash: String,
    val turnId: String,
    val category: String,
    val memoryId: String,
    val memoryVersion: Int,
)

internal class MemoryToolRejected(val reason: String) : IllegalStateException("记忆工具未执行")

/** 保存当前用户明确表达的完整声明，不让模型改写后伪造证据。事实先限职业、专业及主要编程语言。 */
object MemoryToolPolicy {
    fun decode(name: String, args: JsonObject): MemoryToolCall = when (name) {
        "memory.search" -> {
            require(args.keys == setOf("query"))
            val query = args.string("query")
            require(query.length in 1..80 && query == query.trim() && query.none { it.isISOControl() })
            MemoryToolCall.Search(query)
        }
        "memory.upsert" -> {
            require(args.keys == setOf("category", "evidence", "target"))
            val category = args.string("category")
            require(category in setOf("preference", "goal", "fact"))
            val evidence = args.string("evidence")
            require(evidence == MemoryCommands.content(evidence))
            val target = if (args["target"] == JsonNull) null else {
                val ref = args["target"] as? JsonObject ?: throw IllegalArgumentException()
                require(ref.keys == setOf("id", "version"))
                val version = ref["version"] as? JsonPrimitive
                require(version != null && !version.isString)
                MemoryRef(MemoryCommands.id(ref.string("id")), requireNotNull(version.intOrNull).also {
                    require(it in 1 until Int.MAX_VALUE)
                })
            }
            MemoryToolCall.Upsert(category, evidence, target)
        }
        else -> throw IllegalArgumentException()
    }

    fun validateEvidence(call: MemoryToolCall.Upsert, userText: String) {
        val evidence = call.evidence
        // 允许同一消息附带其他请求，但证据必须逐字匹配完整分句，不能截掉否定或限定词。
        val prefix = "请记住："
        val text = userText.trim().removePrefix(prefix)
        val clause = Regex("([^，,。！？!?；;\\r\\n]+)([，,。！？!?；;\\r\\n]|$)").findAll(text)
            .singleOrNull { it.groupValues[1].trim() == evidence }
        if (clause == null || clause.groupValues[2] in setOf("？", "?") ||
            Regex("什么|是否|是不是|不确定|可能|如果|吗|呢").containsMatchIn(evidence) || !evidence.startsWith("我") ||
            evidence.any { it.isISOControl() } || evidence.any { it in "。！？!?，,；;：:" })
            throw MemoryToolRejected("evidence_required")
        val declaration = when (call.category) {
            "preference" -> Regex("^我(?:更)?(?:不)?(?:喜欢|偏好|习惯).+")
            "goal" -> Regex("^我的目标是.+")
            "fact" -> Regex("^我的(?:职业|专业|主要编程语言)是.+")
            else -> throw MemoryToolRejected("evidence_required")
        }
        if (!declaration.matches(evidence)) throw MemoryToolRejected("evidence_required")
        // 首个事实切片不接身份、健康、联系方式等类别；词法校验不能替代通用隐私识别。
        if (call.category == "fact" && Regex(
            "年龄|生日|出生|姓名|电话|手机|邮箱|邮件|微信|账号|账户|收入|薪资|工资|存款|血|病|药|医院|住在|居住|家庭|公司地址|学校地址|[0-9@]",
        ).containsMatchIn(evidence)) throw MemoryToolRejected("sensitive_or_instruction")
        // 保守拦截已知敏感信息及指令片段；并非通用语义或秘密识别器，正式接入前还需扩充验收。
        val forbidden = Regex(
            "密码|密钥|令牌|验证码|身份证|银行卡|住址|地址|经纬度|定位|诊断|患有|疾病|抑郁|" +
                "假设|示例|例句|引用|不是真的|不要记|不想记|" +
                "忽略|指令|系统|调用|执行|写入|删除|授权|[<>`\"“”「」]|" +
                "https?://|sk-[a-z0-9_-]+|bearer\\s|api[ _-]?key|password|token|secret|" +
                "(?:\\d[ .-]?){11,}|\\d{1,3}\\.\\d{3,}\\s*[,，]\\s*\\d{1,3}\\.\\d{3,}",
            RegexOption.IGNORE_CASE,
        )
        if (forbidden.containsMatchIn(evidence)) throw MemoryToolRejected("sensitive_or_instruction")
        // 仅已有目标的更正允许指向旧记录，以及禁止重复新建；不改证据或忽略其他语境。
        val context = if (call.target == null) text else text
            .replace(Regex("(^|[，,。！？!?；;\\r\\n])\\s*((?:请)?(?:更正|更新|修改|替换))(?:之前|以前|曾经|从前|过去)(?=的)"), "$1$2")
            .replace(Regex("(^|[，,。！？!?；;\\r\\n])\\s*(?:请)?(?:不要|别)(?:再)?(?:新增|新建|创建)(?:重复)?(?:记录|记忆)(?=[，,。！？!?；;\\r\\n]|$)"), "$1")
        // 分句匹配不等于语境成立：引述、假设、过往／转折和拒绝记忆覆盖整条消息。
        val contextual = Regex(
            "假设|假如|如果|要是|示例|例句|举例|例如|比如|引用|资料|文档|转述|摘录|他说|她说|他们说|别人说|" +
                "假装|角色扮演|虚构|不是真的|不是我的|不代表|与我无关|以前|之前|曾经|从前|过去|" +
                "(?:^|[，,。；;])\\s*(?:但|不过|然而|却|而是)|" +
                "(?:不要|不想|不需要|不用|无需|不必|不能|别|请勿|不许|禁止).{0,12}(?:记|保存|存储)|" +
                "[<>`\"“”「」]", RegexOption.DOT_MATCHES_ALL,
        )
        if (contextual.containsMatchIn(context)) throw MemoryToolRejected("evidence_required")
    }

    internal fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun JsonObject.string(key: String): String {
        val value = get(key) as? JsonPrimitive
        require(value != null && value.isString)
        return value.content
    }
}

/** 默认没有授权；本地测试直接注入合成授权，不改变用户的实际开关。 */
class MemoryToolExecutor(
    private val dao: MemoryDao,
    private val readGrant: () -> MemoryToolGrant? = { null },
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun execute(turn: MemoryToolTurn, callId: String, name: String, args: JsonObject): MemoryToolResult = try {
        require(turn.ownerId.isNotBlank() && turn.scope.matches(Regex("[0-9a-f]{64}")))
        MemoryCommands.id(turn.turnId)
        MemoryCommands.id(turn.grantGeneration)
        require(callId.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        require(turn.userText.length in 1..2000 && turn.startedAtMillis in 0..now())
        val authorize = {
            if (readGrant() != MemoryToolGrant(turn.scope, turn.grantGeneration, turn.factsAllowed))
                throw MemoryToolRejected("consent_required")
        }
        authorize()
        when (val call = MemoryToolPolicy.decode(name, args)) {
            is MemoryToolCall.Search -> {
                val rows = dao.search(turn.ownerId, call.query)
                authorize()
                MemoryToolResult.Found(rows.map { it.record() })
            }
            is MemoryToolCall.Upsert -> {
                if (call.category == "fact" && !turn.factsAllowed) throw MemoryToolRejected("scope_denied")
                MemoryToolPolicy.validateEvidence(call, turn.userText)
                val key = JsonArray(listOf(turn.ownerId, turn.scope, turn.grantGeneration, turn.turnId, callId).map(::JsonPrimitive)).toString()
                val operationId = MemoryToolPolicy.hash(key)
                // 创建标识由原生生成；请求摘要同时绑定不可变的本轮来源。
                val requestHash = MemoryToolPolicy.hash(JsonArray(listOf(
                    JsonPrimitive(turn.startedAtMillis), JsonPrimitive(turn.userText),
                    JsonPrimitive(call.category), JsonPrimitive(call.evidence),
                    call.target?.let { JsonArray(listOf(JsonPrimitive(it.id), JsonPrimitive(it.version))) } ?: JsonNull,
                )).toString())
                dao.upsertFromTool(turn, operationId, requestHash, call, now(), authorize)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (rejected: MemoryToolRejected) {
        MemoryToolResult.Rejected(rejected.reason)
    } catch (_: MemoryConflict) {
        MemoryToolResult.Rejected("conflict")
    } catch (_: IllegalArgumentException) {
        MemoryToolResult.Rejected("invalid_tool")
    } catch (_: Exception) {
        // 只有持久化事务成功返回后才报告 Saved；不自动重试，也不调用模型补做。
        MemoryToolResult.Rejected("storage_unavailable")
    }
}
