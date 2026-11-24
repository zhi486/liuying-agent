package com.healthyagent.running.agent

import com.healthyagent.running.agent.memory.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MemoryToolExecutorTest {
    private val turn = MemoryToolTurn("local", "a".repeat(64), "11111111-1111-4111-8111-111111111111",
        "22222222-2222-4222-8222-222222222222", 1000, "我喜欢夜跑。")
    private var grant: MemoryToolGrant? = MemoryToolGrant(turn.scope, turn.grantGeneration)
    private val dao = TestMemoryDao()
    private var clock = 2000L
    private val executor = MemoryToolExecutor(dao, { grant }, { clock })
    private fun upsert(text: String = "我喜欢夜跑", target: MemoryRef? = null, category: String = "preference") = buildJsonObject {
        put("category", category); put("evidence", text)
        put("target", target?.let { buildJsonObject { put("id", it.id); put("version", it.version) } } ?: JsonNull)
    }
    private suspend fun save(current: MemoryToolTurn = turn, id: String = "call_1", args: JsonObject = upsert()) =
        executor.execute(current, id, "memory.upsert", args)
    private fun rejected(reason: String, result: MemoryToolResult) = assertEquals(MemoryToolResult.Rejected(reason), result)

    @Test fun 事实证据必须来自当前完整声明而不是模型推断() {
        val evidence = "我的主要编程语言是Kotlin"
        val call = MemoryToolPolicy.decode("memory.upsert", upsert(evidence, category = "fact")) as MemoryToolCall.Upsert
        MemoryToolPolicy.validateEvidence(call, "$evidence。帮我解释协程。")
        for (text in listOf("我是Android开发者", "$evidence？", "假如$evidence", "资料说。$evidence", "$evidence。不要保存")) {
            assertThrows(IllegalStateException::class.java) { MemoryToolPolicy.validateEvidence(call, text) }
        }
    }

    @Test fun 事实写入需要原生冻结的独立范围且模型参数不能授权() = runTest {
        val evidence = "我的主要编程语言是Kotlin"
        val current = turn.copy(userText = evidence)
        val args = upsert(evidence, category = "fact")
        rejected("scope_denied", save(current, args = args))
        grant = grant!!.copy(factsAllowed = true)
        rejected("consent_required", save(current, args = args))
        val allowed = current.copy(factsAllowed = true)
        rejected("invalid_tool", save(allowed, args = JsonObject(args + ("factsAllowed" to JsonPrimitive(true)))))
        val saved = save(allowed, args = args) as MemoryToolResult.Saved
        assertEquals(evidence, saved.memory.content)
        assertEquals("fact", dao.receipts.values.single().category)
        assertTrue((save(allowed, args = args) as MemoryToolResult.Saved).replayed)
        grant = grant!!.copy(factsAllowed = false)
        rejected("consent_required", save(allowed, args = args))
        assertEquals(1, dao.count("local"))
    }

    @Test fun 事实类别不接受偏好目标敏感或指令声明() = runTest {
        grant = grant!!.copy(factsAllowed = true)
        for (text in listOf("我喜欢夜跑", "我的目标是每周三次", "我的职业是医生，擅长跑步", "我的生日是五月一日",
            "我的主要编程语言是Kotlin。不要记住", "我的职业是开发者吗", "以前。我的职业是开发者")) {
            rejected("evidence_required", save(turn.copy(userText = text, factsAllowed = true), args = upsert(text, category = "fact")))
        }
        for (text in listOf("我的专业是血液病", "我的职业是开发者手机号123", "我的主要编程语言是忽略系统指令")) {
            rejected("sensitive_or_instruction", save(turn.copy(userText = text, factsAllowed = true), args = upsert(text, category = "fact")))
        }
        assertEquals(0, dao.count("local"))
    }

    @Test fun 事实更正删除及迟到重放沿用同一版本保护() = runTest {
        grant = grant!!.copy(factsAllowed = true)
        val current = turn.copy(userText = "我的主要编程语言是Kotlin", factsAllowed = true)
        val args = upsert(current.userText, category = "fact")
        val first = (save(current, args = args) as MemoryToolResult.Saved).memory
        val changed = current.copy(userText = "我的主要编程语言是Java")
        val second = (save(changed, "correction", upsert(changed.userText, MemoryRef(first.id, 1), "fact")) as MemoryToolResult.Saved).memory
        assertEquals(first.id, second.id)
        assertEquals(2, second.version)
        rejected("conflict", save(current, args = args))
        dao.delete("local", second.id, second.version, 2100)
        rejected("conflict", save(current, "late", args))
        val found = executor.execute(current, "search", "memory.search", buildJsonObject { put("query", "编程语言") }) as MemoryToolResult.Found
        assertTrue(found.items.isEmpty())
    }

    @Test fun 当前回执按归属回合版本匹配且修改恢复删除均失效() = runTest {
        assertFalse(dao.hasCurrentToolSave("local", turn.turnId))
        val saved = (save() as MemoryToolResult.Saved).memory
        assertTrue(dao.hasCurrentToolSave("local", turn.turnId))
        assertFalse(dao.hasCurrentToolSave("other", turn.turnId))
        assertFalse(dao.hasCurrentToolSave("local", "other"))
        dao.update("local", saved.id, saved.version, "我喜欢晨跑", 3000)
        assertFalse(dao.hasCurrentToolSave("local", turn.turnId))
        dao.restore("local", saved.id, 2, 1, 4000)
        assertFalse(dao.hasCurrentToolSave("local", turn.turnId))
        val updated = save(id = "call_2") as MemoryToolResult.Saved
        assertTrue(dao.hasCurrentToolSave("local", turn.turnId))
        dao.delete("local", updated.memory.id, updated.memory.version, 5000)
        assertFalse(dao.hasCurrentToolSave("local", turn.turnId))
    }

    @Test fun 合成工具保存后其他会话可搜索且不改变手选管理接口() = runTest {
        val saved = save() as MemoryToolResult.Saved
        assertEquals("我喜欢夜跑", saved.memory.content)
        assertFalse(saved.replayed)
        val otherTurn = turn.copy(turnId = "33333333-3333-4333-8333-333333333333", userText = "我平时喜欢什么时候跑步？")
        val found = executor.execute(otherTurn, "search_1", "memory.search", buildJsonObject { put("query", "夜跑") })
        assertEquals(listOf(saved.memory), (found as MemoryToolResult.Found).items)
        assertEquals(saved.memory, dao.page("local", 0, 20).items.single())
        assertEquals(saved.memory, dao.selected("local", listOf(MemoryRef(saved.memory.id, 1))).items.single())
        assertEquals(turn.turnId, dao.receipts.values.single().turnId)
        assertEquals("preference", dao.receipts.values.single().category)
    }

    @Test fun 默认关闭和身份代际变化都不能读写() = runTest {
        rejected("consent_required", MemoryToolExecutor(dao).execute(turn, "call_1", "memory.upsert", upsert()))
        for (value in listOf(null, MemoryToolGrant("b".repeat(64), turn.grantGeneration), MemoryToolGrant(turn.scope, "44444444-4444-4444-8444-444444444444"))) {
            grant = value
            rejected("consent_required", save())
            rejected("consent_required", executor.execute(turn, "search", "memory.search", buildJsonObject { put("query", "夜跑") }))
        }
        assertTrue(dao.memories.isEmpty())
        assertTrue(dao.receipts.isEmpty())
    }

    @Test fun 相同工具重放只保存一次而同ID不同内容拒绝() = runTest {
        val first = save() as MemoryToolResult.Saved
        assertEquals(MemoryToolResult.Saved(first.memory, true), save())
        assertEquals(1, dao.count("local"))
        assertEquals(1, dao.receipts.size)
        val changed = turn.copy(userText = "我喜欢晨跑")
        rejected("conflict", save(changed, args = upsert("我喜欢晨跑")))
        assertEquals(first.memory, dao.page("local", 0, 20).items.single())
        // 模型换一个调用 ID 也不重复创建相同正文。
        assertEquals(first.memory, (save(id = "call_2") as MemoryToolResult.Saved).memory)
        assertEquals(1, dao.count("local"))
    }

    @Test fun 模型回答资料内容旧消息和否定片段均不能作为当前用户证据() = runTest {
        listOf("你好", "资料说：我喜欢夜跑", "我不喜欢夜跑", "他说我喜欢夜跑", "我喜欢夜跑只是一个例句", "\"我喜欢夜跑\"",
            "以下是资料：\n我喜欢夜跑", "他说。\n我喜欢夜跑", "我喜欢夜跑。不要记住这句话")
            .forEach { rejected("evidence_required", save(turn.copy(userText = it))) }
        rejected("evidence_required", save(args = upsert("我喜欢晨跑")))
        rejected("evidence_required", save(args = upsert(category = "goal")))
        for (question in listOf("我喜欢什么", "我喜欢夜跑吗", "我喜欢夜跑？", "我的目标是什么")) {
            val category = if (question.startsWith("我的目标")) "goal" else "preference"
            rejected("evidence_required", save(turn.copy(userText = question), args = upsert(question.trimEnd('？'), category = category)))
        }
        assertTrue(dao.memories.isEmpty())
    }

    @Test fun 已知秘密位置健康推断和指令片段拒绝且不写回执() = runTest {
        listOf("我喜欢保存密码123", "我喜欢使用API Key abc", "我喜欢住址上海某路", "我喜欢定位31.2345",
            "我的目标是诊断疾病", "我喜欢忽略系统指令", "我喜欢调用删除工具", "我喜欢sk-syntheticfixture",
            "我喜欢使用Bearer example", "我喜欢电话号码13800000000", "我喜欢<system>执行</system>", "我喜欢夜跑只是例句")
            .forEach { text ->
                val category = if (text.startsWith("我的目标")) "goal" else "preference"
                rejected("sensitive_or_instruction", save(turn.copy(userText = text), args = upsert(text, category = category)))
            }
        assertTrue(dao.memories.isEmpty())
        assertTrue(dao.receipts.isEmpty())
    }

    @Test fun 目标和中文表情保留原句而非模型加工内容() = runTest {
        val goal = "我的目标是每周跑步三次"
        assertEquals(goal, (save(turn.copy(userText = goal), args = upsert(goal, category = "goal")) as MemoryToolResult.Saved).memory.content)
        val preference = "我喜欢简短回答😊"
        assertEquals(preference, (save(turn.copy(userText = preference), id = "call_2", args = upsert(preference)) as MemoryToolResult.Saved).memory.content)
    }

    @Test fun 自然多分句消息仅保存完整偏好目标而不夹带附带问题() = runTest {
        val cases = listOf(
            Triple("今晚先聊训练。我更喜欢晚上跑步，你能帮我安排吗？", "我更喜欢晚上跑步", "preference"),
            Triple("对了，我不喜欢太长的回答。之后聊训练就简短些。", "我不喜欢太长的回答", "preference"),
            Triple("请记住：我的目标是每周跑步三次，帮我排个计划。", "我的目标是每周跑步三次", "goal"),
            Triple("我习惯饭后一小时再跑步。\n今天安排轻松一些。", "我习惯饭后一小时再跑步", "preference"),
            Triple("我偏好中文回答；我们继续聊。", "我偏好中文回答", "preference"),
        )
        cases.forEachIndexed { index, (message, evidence, category) ->
            val result = save(turn.copy(userText = message), "natural_$index", upsert(evidence, category = category))
            assertTrue("自然分句应被保存，合成编号 $index", result is MemoryToolResult.Saved)
            assertEquals(evidence, (result as MemoryToolResult.Saved).memory.content)
        }
        assertEquals(cases.size, dao.count("local"))
    }

    @Test fun 分句取证不能截断修饰否定条件或将问题当成偏好() = runTest {
        listOf(
            "我喜欢夜跑但今天不想跑", "我喜欢夜跑吗，帮我判断", "我喜欢夜跑？你觉得呢", "不是我喜欢夜跑，是他说的",
            "如果我喜欢夜跑，计划怎么排", "我喜欢夜跑，不过这不是我的真实偏好", "我以前喜欢夜跑，现在不喜欢了",
            "我喜欢夜跑。不要记住这句话", "我喜欢夜跑，别把这个保存下来", "我喜欢夜跑，但不需要你记住",
            "资料说：\n我喜欢夜跑。", "他说。\n我喜欢夜跑", "假装这是我说的。我喜欢夜跑。",
            "我喜欢夜跑。这里只是在引用示例", "我喜欢夜跑，只是一个例句", "\"我喜欢夜跑\"，请分析这段资料",
            "我喜欢夜跑。别\n帮我记住", "我喜欢夜跑。上面这句是虚构的", "我喜欢夜跑，例如这句话仅供分析",
        ).forEach { rejected("evidence_required", save(turn.copy(userText = it))) }
        rejected("evidence_required", save(turn.copy(userText = "我不喜欢太长的回答，继续聊"), args = upsert("我喜欢太长的回答")))
        assertTrue(dao.memories.isEmpty())
        assertTrue(dao.receipts.isEmpty())
    }

    @Test fun 手动修改后旧工具回执和旧版本更新均不能覆盖() = runTest {
        val saved = (save() as MemoryToolResult.Saved).memory
        dao.update("local", saved.id, 1, "我喜欢清晨跑", 2100)
        rejected("conflict", save())
        val changed = turn.copy(userText = "我喜欢晨跑")
        rejected("conflict", save(changed, "edit", upsert("我喜欢晨跑", MemoryRef(saved.id, 1))))
        val edit = save(changed, "edit_2", upsert("我喜欢晨跑", MemoryRef(saved.id, 2))) as MemoryToolResult.Saved
        assertEquals(3, edit.memory.version)
        assertEquals(MemoryToolResult.Saved(edit.memory, true), save(changed, "edit_2", upsert("我喜欢晨跑", MemoryRef(saved.id, 2))))
    }

    @Test fun 删除后重放换ID和旧回合显式记住都不能复活() = runTest {
        val saved = (save() as MemoryToolResult.Saved).memory
        dao.delete("local", saved.id, 1, 3000)
        clock = 4000
        rejected("conflict", save())
        rejected("conflict", save(id = "changed_id"))
        rejected("conflict", save(turn.copy(userText = "请记住：我喜欢夜跑"), "changed_again"))
        assertEquals(emptyList<MemoryEntity>(), dao.search("local", "夜跑"))
        assertNull(dao.find("local", saved.id)!!.content)
        val other = dao.create("local", java.util.UUID.randomUUID().toString(), "其他偏好", 3100)
        rejected("conflict", save(id = "reuse_other", args = upsert(target = MemoryRef(other.id, other.version))))
        dao.delete("local", other.id, other.version, 3200)
        val newTurn = turn.copy(turnId = "33333333-3333-4333-8333-333333333333", startedAtMillis = 3500, userText = "请记住：我喜欢夜跑")
        val recreated = save(newTurn) as MemoryToolResult.Saved
        assertNotEquals(saved.id, recreated.memory.id)
        assertEquals(1, dao.count("local"))
    }

    @Test fun 搜索按owner隔离大小写匹配上限五条且通配符只是普通字符() = runTest {
        repeat(8) { dao.create("local", java.util.UUID.randomUUID().toString(), "我喜欢RUN$it", it.toLong()) }
        dao.create("other", java.util.UUID.randomUUID().toString(), "我喜欢RUN其他", 100)
        val found = executor.execute(turn, "search", "memory.search", buildJsonObject { put("query", "run") }) as MemoryToolResult.Found
        assertEquals(5, found.items.size)
        assertEquals("我喜欢RUN7", found.items.first().content)
        assertEquals(emptyList<MemoryRecord>(), (executor.execute(turn, "search_2", "memory.search", buildJsonObject { put("query", "%") }) as MemoryToolResult.Found).items)
    }

    @Test fun 非法字段路径归属版本和调用类型不能进入存储() = runTest {
        val valid = upsert()
        listOf(valid + ("ownerId" to JsonPrimitive("other")), valid + ("path" to JsonPrimitive("/tmp/memory.md")),
            valid + ("approved" to JsonPrimitive(true)), valid + ("category" to JsonPrimitive("diagnosis")),
            valid + ("target" to buildJsonObject { put("id", turn.turnId); put("version", "1") }),
            valid + ("target" to buildJsonObject { put("id", turn.turnId); put("version", 0) }))
            .forEach { rejected("invalid_tool", save(args = JsonObject(it))) }
        rejected("invalid_tool", save(id = "../escape"))
        rejected("invalid_tool", executor.execute(turn, "tool", "memory.delete", buildJsonObject {}))
        rejected("invalid_tool", executor.execute(turn, "search", "memory.search", buildJsonObject { put("query", " ") }))
        rejected("invalid_tool", executor.execute(turn, "search", "memory.search", buildJsonObject { put("query", "字".repeat(81)) }))
        assertTrue(dao.memories.isEmpty())
    }

    @Test fun 明确更正旧偏好且禁止重复新增时应更新同一记录() = runTest {
        val original = "我喜欢把测试计划排在周三"
        val updated = "我喜欢把测试计划排在周四"
        val first = (save(turn.copy(userText = original), args = upsert(original)) as MemoryToolResult.Saved).memory
        val current = turn.copy(userText = "$updated。请更正之前的周三偏好，不要新增重复记录。")
        val args = upsert(updated, MemoryRef(first.id, first.version))
        val result = save(current, "correction", args)
        assertTrue("当前声明后的旧记录更正要求不应误判为历史证据", result is MemoryToolResult.Saved)
        val saved = (result as MemoryToolResult.Saved).memory
        assertEquals(first.id, saved.id)
        assertEquals(2, saved.version)
        assertEquals(updated, saved.content)
        assertEquals(1, dao.count("local"))
        assertEquals(listOf(updated, original), dao.history("local", first.id, 0, 20)!!.items.map { it.content })
        assertEquals(MemoryToolResult.Saved(saved, true), save(current, "correction", args))
        rejected("conflict", save(current, "stale_correction", args))
    }

    @Test fun 更正附言不允许新建重复或绕过历史假设引述及拒绝保存() = runTest {
        val updated = "我喜欢把测试计划排在周四"
        val first = (save() as MemoryToolResult.Saved).memory
        val suffix = "请更正之前的偏好，不要新增重复记录。"
        rejected("evidence_required", save(turn.copy(userText = "$updated。$suffix"), "duplicate", upsert(updated)))
        for (text in listOf(
            "之前。$updated。$suffix", "如果是这样。$updated。$suffix", "他说。$updated。$suffix",
            "$updated。$suffix 不要记住这句话", "$updated。$suffix 之前的声明不是真的",
            "$updated。请更正之前的假设偏好，不要新增重复记录。",
            "$updated。请更正之前的偏好，不要新增重复记录而且不要保存。",
            "$updated。请更正之前的偏好，别记住。",
            "$updated。请更正之前的偏好，只是一个例句。",
            "$updated。请更正之前的「旧偏好」，不要新增重复记录。",
        )) rejected("evidence_required", save(turn.copy(userText = text), "unsafe", upsert(updated, MemoryRef(first.id, 1))))
        assertEquals("我喜欢夜跑", dao.find("local", first.id)!!.content)
        assertEquals(1, dao.count("local"))
        assertEquals(1, dao.receipts.size)
    }

    @Test fun 更正附言不绑定具体主题且目标与事实仍独立授权() = runTest {
        val cases = listOf(
            Triple("我喜欢晨跑；请更新以前的偏好；别新建重复记忆。", "我喜欢晨跑", "preference"),
            Triple("请修改过去的目标。我的目标是每周跑步两次。不要再创建记录。", "我的目标是每周跑步两次", "goal"),
            Triple("我的主要编程语言是Java，替换之前的事实，不要新增重复记录。", "我的主要编程语言是Java", "fact"),
        )
        cases.forEachIndexed { index, (message, evidence, category) ->
            val first = dao.create("local", java.util.UUID.randomUUID().toString(), "旧内容$index", 1000)
            val args = upsert(evidence, MemoryRef(first.id, first.version), category)
            val current = turn.copy(userText = message)
            if (category == "fact") rejected("scope_denied", save(current, "scope_$index", args))
            grant = grant!!.copy(factsAllowed = category == "fact")
            val result = save(current.copy(factsAllowed = category == "fact"), "edit_$index", args) as MemoryToolResult.Saved
            assertEquals(first.id, result.memory.id)
            assertEquals(2, result.memory.version)
            assertEquals(evidence, result.memory.content)
        }
        grant = null
        rejected("consent_required", save(turn.copy(userText = "我喜欢晨跑。请更正之前的偏好。"),
            "revoked", upsert("我喜欢晨跑", MemoryRef(dao.memories.values.first().id, 2))))
        assertEquals(cases.size, dao.count("local"))
    }

    @Test fun 存储失败不报告已记住取消也不转成普通错误() = runTest {
        dao.readFailure = IllegalStateException("合成离线存储错误")
        rejected("storage_unavailable", save())
        assertTrue(dao.receipts.isEmpty())
        dao.readFailure = CancellationException("合成取消")
        try { save(); fail("应传播取消") } catch (_: CancellationException) { }
    }

    @Test fun 查询期间关闭授权不返回正文() = runTest {
        dao.create("local", java.util.UUID.randomUUID().toString(), "我喜欢夜跑", 1000)
        dao.afterSearch = { grant = null }
        rejected("consent_required", executor.execute(turn, "search", "memory.search", buildJsonObject { put("query", "夜跑") }))
    }

    @Test fun 历史按版本倒序保留来源且恢复不倒退版本() = runTest {
        val first = (save() as MemoryToolResult.Saved).memory
        dao.update("local", first.id, 1, "我喜欢晨跑", 3000)
        val history = dao.history("local", first.id, 0, 20)!!
        assertEquals(2, history.currentVersion)
        assertEquals(listOf(2, 1), history.items.map { it.version })
        assertEquals(listOf("manual", "agent"), history.items.map { it.changeSource })
        assertEquals(turn.turnId, history.items.last().turnId)
        assertEquals(1, dao.history("local", first.id, 1, 1)!!.items.single().version)
        assertNull(dao.history("other", first.id, 0, 20))
        val restored = dao.restore("local", first.id, 2, 1, 4000)!!
        assertEquals(3, restored.version)
        assertEquals(first.content, restored.content)
        assertEquals("restored", dao.history("local", first.id, 0, 20)!!.items.first().changeSource)
        assertEquals(restored, dao.restore("local", first.id, 2, 1, 4000))
        assertEquals(3, dao.revisionCount("local", first.id))
        dao.update("local", first.id, 3, "我喜欢短回答", 5000)
        try { dao.restore("local", first.id, 2, 1, 6000); fail("不能覆盖新编辑") } catch (_: MemoryConflict) { }
        assertEquals("我喜欢短回答", dao.find("local", first.id)!!.content)
    }

    @Test fun 删除清除全部历史正文且工具重放和历史恢复均不能复活() = runTest {
        val first = (save() as MemoryToolResult.Saved).memory
        dao.update("local", first.id, 1, "我喜欢晨跑", 3000)
        dao.delete("local", first.id, 2, 4000)
        assertEquals(0, dao.revisionCount("local", first.id))
        assertNull(dao.history("local", first.id, 0, 20))
        assertNull(dao.revision("local", first.id, 1))
        assertNull(dao.find("local", first.id)!!.content)
        try { dao.restore("local", first.id, 3, 1, 5000); fail("删除后不得恢复") } catch (_: MemoryConflict) { }
        rejected("conflict", save())
        assertEquals(MemoryDeleted(first.id), dao.delete("local", first.id, 2, 5000))
        assertEquals(0, dao.revisionCount("local", first.id))
    }

    @Test fun 手动记忆删除后迟到工具不能换新标识恢复任何旧版本() = runTest {
        val memoryId = java.util.UUID.randomUUID().toString()
        dao.create("local", memoryId, "我喜欢夜跑", 1000)
        dao.update("local", memoryId, 1, "我喜欢晨跑", 2000)
        dao.delete("local", memoryId, 2, 3000)
        clock = 4000
        for (text in listOf("我喜欢夜跑", "我喜欢晨跑")) {
            rejected("conflict", save(turn.copy(userText = text, startedAtMillis = 2500),
                "late_${text.length}", upsert(text)))
        }
        assertEquals(0, dao.count("local"))
        assertEquals(0, dao.revisionCount("local", memoryId))
        assertTrue(dao.receipts.isEmpty())
    }

    @Test fun 删除后只有更新的明确重记声明可保存且不影响其他归属或独立内容() = runTest {
        val memoryId = java.util.UUID.randomUUID().toString()
        dao.create("local", memoryId, "我喜欢夜跑", 1000)
        dao.delete("local", memoryId, 1, 3000)
        clock = 5000
        rejected("conflict", save(turn.copy(startedAtMillis = 4000), "ordinary"))
        rejected("conflict", save(turn.copy(startedAtMillis = 3000, userText = "请记住：我喜欢夜跑"), "old_explicit"))
        assertTrue(save(turn.copy(startedAtMillis = 4000, userText = "请记住：我喜欢夜跑"), "new_explicit") is MemoryToolResult.Saved)
        assertTrue(save(turn.copy(ownerId = "other"), "other_owner") is MemoryToolResult.Saved)
        assertTrue(save(turn.copy(userText = "我喜欢短回答"), "unrelated", upsert("我喜欢短回答")) is MemoryToolResult.Saved)
        assertEquals(3, dao.memories.values.count { it.deletedAtMillis == null })
        assertNull(dao.find("local", memoryId)!!.content)
    }

    @Test fun 相同正文不同记录的较旧删除时间不能降低删除边界() = runTest {
        val ids = List(2) { java.util.UUID.randomUUID().toString() }
        ids.forEach { dao.create("local", it, "我喜欢夜跑", 1000) }
        dao.delete("local", ids[0], 1, 4000)
        dao.delete("local", ids[1], 1, 2000)
        clock = 5000
        rejected("conflict", save(turn.copy(startedAtMillis = 3000, userText = "请记住：我喜欢夜跑"), "between"))
        assertEquals(4000L, dao.deletionTime("local", MemoryToolPolicy.hash("我喜欢夜跑")))
    }

    @Test fun 重放不重复历史旧记录编辑前保留当前快照() = runTest {
        val first = (save() as MemoryToolResult.Saved).memory
        save(); save(id = "another")
        assertEquals(1, dao.revisionCount("local", first.id))
        val legacyId = "55555555-5555-4555-8555-555555555555"
        dao.insert(MemoryEntity(legacyId, "local", "旧正文", "user_explicit", 1000, 2000, 4))
        dao.update("local", legacyId, 4, "新正文", 3000)
        assertEquals(listOf(5, 4), dao.history("local", legacyId, 0, 20)!!.items.map { it.version })
        assertEquals("legacy", dao.revision("local", legacyId, 4)!!.changeSource)
        assertEquals("旧正文", dao.restore("local", legacyId, 5, 4, 4000)!!.content)
        try { dao.restore("local", legacyId, 6, 1, 5000); fail("不能伪造不存在的历史") } catch (_: MemoryConflict) { }
        assertEquals(6, dao.find("local", legacyId)!!.version)
    }

    /** 测试复用 DAO 的事务算法，但此替身不证明 SQLite 原子性；另有设备事务／迁移测试。 */
    private class TestMemoryDao : MemoryDao {
        val memories = linkedMapOf<String, MemoryEntity>()
        val receipts = linkedMapOf<String, MemoryToolReceipt>()
        val revisions = linkedMapOf<Triple<String, String, Int>, MemoryRevision>()
        val deletionMarkers = linkedMapOf<Pair<String, String>, MemoryDeletionMarker>()
        var readFailure: Exception? = null
        var afterSearch: () -> Unit = {}
        override suspend fun find(ownerId: String, id: String) = memories[id]?.takeIf { it.ownerId == ownerId }
        override suspend fun insert(memory: MemoryEntity) { check(!memories.containsKey(memory.id)); memories[memory.id] = memory }
        override suspend fun count(ownerId: String) = memories.values.count { it.ownerId == ownerId && it.deletedAtMillis == null }
        override suspend fun rows(ownerId: String, offset: Int, limit: Int) = memories.values.filter { it.ownerId == ownerId && it.deletedAtMillis == null }
            .sortedWith(compareByDescending<MemoryEntity> { it.updatedAtMillis }.thenByDescending { it.id }).drop(offset).take(limit)
        override suspend fun documentRows(ownerId: String) = rows(ownerId, 0, Int.MAX_VALUE)
            .filter { it.source == "user_explicit" && it.content != null }.map { row ->
                val revision = revision(ownerId, row.id, row.version)
                val category = receipts.values.firstOrNull { it.ownerId == ownerId && it.memoryId == row.id &&
                    it.memoryVersion == row.version && it.turnId == revision?.turnId && revision?.changeSource == "agent" }?.category
                MemoryDocumentEntry(row.record(), revision?.changeSource, revision?.turnId, category)
            }
        override suspend fun change(ownerId: String, id: String, version: Int, content: String?, now: Long, deletedAt: Long?): Int {
            val old = find(ownerId, id)?.takeIf { it.version == version && it.deletedAtMillis == null } ?: return 0
            memories[id] = old.copy(content = content, updatedAtMillis = now, deletedAtMillis = deletedAt, version = version + 1)
            return 1
        }
        override suspend fun search(ownerId: String, query: String) = rows(ownerId, 0, Int.MAX_VALUE)
            .filter { it.source == "user_explicit" && it.content!!.contains(query, ignoreCase = true) }.take(5).also { afterSearch() }
        override suspend fun toolReceipt(ownerId: String, operationId: String): MemoryToolReceipt? {
            readFailure?.let { throw it }
            return receipts[operationId]?.takeIf { it.ownerId == ownerId }
        }
        override suspend fun insertToolReceipt(receipt: MemoryToolReceipt) { check(!receipts.containsKey(receipt.operationId)); receipts[receipt.operationId] = receipt }
        override suspend fun hasCurrentToolSave(ownerId: String, turnId: String) = receipts.values.any { receipt ->
            receipt.ownerId == ownerId && receipt.turnId == turnId && find(ownerId, receipt.memoryId)?.let {
                it.deletedAtMillis == null && it.source == "user_explicit" && it.version == receipt.memoryVersion
            } == true
        }
        override suspend fun toolDeletedAt(ownerId: String, contentHash: String) = receipts.values.filter { it.ownerId == ownerId && it.contentHash == contentHash }
            .mapNotNull { find(ownerId, it.memoryId)?.deletedAtMillis }.maxOrNull()
        override suspend fun deletionTime(ownerId: String, contentHash: String) = deletionMarkers[ownerId to contentHash]?.deletedAtMillis
        override suspend fun saveDeletionMarker(marker: MemoryDeletionMarker) { deletionMarkers[marker.ownerId to marker.contentHash] = marker }
        override suspend fun revisionContents(ownerId: String, id: String) = revisions.values
            .filter { it.ownerId == ownerId && it.memoryId == id }.map { it.content }.distinct()
        override suspend fun matchingContent(ownerId: String, content: String) = memories.values.firstOrNull {
            it.ownerId == ownerId && it.deletedAtMillis == null && it.source == "user_explicit" && it.content == content
        }
        override suspend fun insertRevision(revision: MemoryRevision): Long {
            val key = Triple(revision.ownerId, revision.memoryId, revision.version)
            if (revisions.containsKey(key)) return -1
            revisions[key] = revision
            return revisions.size.toLong()
        }
        override suspend fun revision(ownerId: String, id: String, version: Int) = revisions[Triple(ownerId, id, version)]
        override suspend fun revisionRows(ownerId: String, id: String, offset: Int, limit: Int) = revisions.values
            .filter { it.ownerId == ownerId && it.memoryId == id }.sortedByDescending { it.version }.drop(offset).take(limit)
        override suspend fun revisionCount(ownerId: String, id: String) = revisions.values.count { it.ownerId == ownerId && it.memoryId == id }
        override suspend fun clearRevisions(ownerId: String, id: String) {
            revisions.entries.removeAll { it.value.ownerId == ownerId && it.value.memoryId == id }
        }
    }
}
