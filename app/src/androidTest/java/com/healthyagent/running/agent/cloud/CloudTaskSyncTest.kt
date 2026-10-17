package com.healthyagent.running.agent.cloud

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.healthyagent.running.agent.local.AgentDatabase
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudTaskSyncTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: AgentDatabase
    private val identity = CloudTaskIdentity("https://agent.example.com", "owner", "device", "generation")
    private val cloudId = "33333333-3333-4333-8333-333333333333"
    private fun intent() = CloudTaskIntent.confirm(identity, "仅合成测试内容", true, 1000)
    private fun view(state: String = "available") = CloudTaskResponse(cloudId, "completed", null, 1000, 31000, 2000, state,
        if (state == "available") buildJsonObject { put("status", "completed"); put("answer", "合成回答"); put("actions", JsonArray(emptyList())) } else null)
    private val dao get() = database.cloudIntentDao()
    @Before fun setUp() { database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).build() }
    @After fun tearDown() { database.close() }
    private suspend fun failure(block: suspend () -> Unit): CloudTaskFailure {
        try { block() } catch (failure: CloudTaskFailure) { return failure }
        error("应该失败")
    }

    @Test fun 正文事务完成以后才ack且空响应保留本机结果() = runBlocking {
        val value = dao.save(intent())
        val api = FakeApi { action ->
            if (action == "acknowledge") {
                val persisted = dao.receipt(identity, value.clientRequestId)!!
                assertNotNull(persisted.resultJson)
                assertTrue(persisted.ackPending)
                view("acknowledged")
            } else view()
        }
        val result = CloudTaskSync(dao, api) { 2000 }.submit(identity, value.clientRequestId)!!
        assertNotNull(result.resultJson)
        assertFalse(result.ackPending)
        assertEquals(listOf("submit", "acknowledge"), api.calls)
    }

    @Test fun 收据事务失败绝不发送ack() = runBlocking {
        val value = dao.save(intent())
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_receipt BEFORE INSERT ON agent_cloud_receipts BEGIN SELECT RAISE(ABORT, '合成写入故障'); END")
        val api = FakeApi { view() }
        assertEquals("storage_unavailable", failure { CloudTaskSync(dao, api).submit(identity, value.clientRequestId) }.code)
        assertEquals(listOf("submit"), api.calls)
        assertNull(dao.receipt(identity, value.clientRequestId))
        assertTrue(dao.find(identity, value.clientRequestId)!!.submissionAttempted)
    }

    @Test fun POST失联之后即使404也只查询同键而不重新提交() = runBlocking {
        val value = dao.save(intent())
        val api = FakeApi { action -> throw CloudTaskFailure(if (action == "submit") "network_unavailable" else "not_found") }
        val sync = CloudTaskSync(dao, api)
        failure { sync.submit(identity, value.clientRequestId) }
        assertEquals("not_found", failure { sync.submit(identity, value.clientRequestId) }.code)
        assertEquals("not_found", failure { CloudTaskSync(dao, api).recover(identity, value.clientRequestId) }.code)
        assertEquals(listOf("submit", "find", "find"), api.calls)
        assertEquals(listOf(value.clientRequestId, value.clientRequestId), api.lookups)
    }

    @Test fun ack失联后关闭重开库仍保留正文且只重试幂等操作() = runBlocking {
        val file = File(context.noBackupFilesDir, "cloud-sync-${UUID.randomUUID()}.db")
        val first = open(file)
        val value = first.cloudIntentDao().save(intent())
        val firstApi = FakeApi { action -> if (action == "acknowledge") throw CloudTaskFailure("network_unavailable") else view() }
        try {
            failure { CloudTaskSync(first.cloudIntentDao(), firstApi).submit(identity, value.clientRequestId) }
            assertNotNull(first.cloudIntentDao().receipt(identity, value.clientRequestId)!!.resultJson)
        } finally { first.close() }
        val reopened = open(file)
        try {
            val api = FakeApi { action -> if (action == "acknowledge") view("acknowledged") else view() }
            val recovered = CloudTaskSync(reopened.cloudIntentDao(), api).recover(identity, value.clientRequestId)!!
            assertNotNull(recovered.resultJson)
            assertFalse(recovered.ackPending)
            assertEquals(listOf("get", "acknowledge"), api.calls)
        } finally { reopened.close() }
    }

    @Test fun 撤回在等待查询时先落盘且迟到正文不复活也不ack() = runBlocking {
        val value = dao.save(intent())
        dao.claimSubmission(identity, value.clientRequestId, 2000)
        dao.accept(identity, value.clientRequestId, view())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var first = true
        val api = FakeApi { action ->
            if (action == "get" && first) { first = false; entered.complete(Unit); release.await() }
            if (action == "withdraw") view("withdrawn") else view()
        }
        val sync = CloudTaskSync(dao, api)
        val query = async { sync.recover(identity, value.clientRequestId) }
        entered.await()
        val control = async { sync.control(identity, value.clientRequestId, "withdraw") }
        withTimeout(5000) { while (dao.find(identity, value.clientRequestId)!!.pendingControl != "withdraw") delay(10) }
        assertNull(dao.receipt(identity, value.clientRequestId)!!.resultJson)
        release.complete(Unit)
        query.await(); control.await()
        val result = dao.receipt(identity, value.clientRequestId)!!
        assertNull(result.resultJson)
        assertEquals("withdraw", result.confirmedControl)
        assertFalse(api.calls.contains("acknowledge"))
    }

    @Test fun 未提交的取消不发网并且恢复不能触发新POST() = runBlocking {
        val value = dao.save(intent())
        val api = FakeApi { error("不应发网") }
        val sync = CloudTaskSync(dao, api)
        assertNull(sync.recover(identity, value.clientRequestId))
        assertNull(sync.control(identity, value.clientRequestId, "cancel"))
        assertNull(sync.submit(identity, value.clientRequestId))
        assertTrue(api.calls.isEmpty())
    }

    @Test fun 不同请求的同云ID冲突必须拒绝而不是假报落库() = runBlocking {
        val first = dao.save(intent()); val second = dao.save(intent())
        dao.claimSubmission(identity, first.clientRequestId, 2000)
        dao.claimSubmission(identity, second.clientRequestId, 2000)
        dao.accept(identity, first.clientRequestId, view())
        var failed = false
        try { dao.accept(identity, second.clientRequestId, view()) } catch (_: Exception) { failed = true }
        assertTrue(failed)
        assertNull(dao.receipt(identity, second.clientRequestId))
        assertNotNull(dao.receipt(identity, first.clientRequestId))
    }

    private fun open(file: File) = Room.databaseBuilder(context, AgentDatabase::class.java, file.absolutePath)
        .addMigrations(AgentDatabase.MIGRATION_1_2, AgentDatabase.MIGRATION_2_3, AgentDatabase.MIGRATION_3_4, AgentDatabase.MIGRATION_4_5, AgentDatabase.MIGRATION_5_6, AgentDatabase.MIGRATION_6_7, AgentDatabase.MIGRATION_7_8).build()

    private class FakeApi(private val respond: suspend (String) -> CloudTaskResponse) : CloudTaskApi {
        val calls = mutableListOf<String>()
        val lookups = mutableListOf<String>()
        private suspend fun call(action: String): CloudTaskResponse { calls += action; return respond(action) }
        override suspend fun submit(intent: CloudTaskIntent) = call("submit")
        override suspend fun find(identity: CloudTaskIdentity, requestId: String): CloudTaskResponse { lookups += requestId; return call("find") }
        override suspend fun get(identity: CloudTaskIdentity, taskId: String) = call("get")
        override suspend fun control(identity: CloudTaskIdentity, taskId: String, action: String) = call(action)
    }
}
