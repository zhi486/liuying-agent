package com.healthyagent.running.agent.cloud

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.healthyagent.running.agent.local.AgentDatabase
import com.healthyagent.running.agent.memory.MemoryContextState
import java.util.UUID
import java.io.File
import android.content.Context
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationSummaryRequestTest {
    private val identity = CloudTaskIdentity("https://agent.example.com", "owner", "device", "credential")
    private fun uuid() = UUID.randomUUID().toString()

    @Test fun 撤回未结束重开只查询并阻挡替代请求且迟到用量不恢复正文() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val file = File(context.noBackupFilesDir, "summary-withdraw-${uuid()}.db")
        fun open() = Room.databaseBuilder(context, AgentDatabase::class.java, file.absolutePath).build()
        var db = open()
        try {
            val conversation = db.conversationDao().create(identity, 1)
            val turns = (1..5).map { ConversationSummaryPolicy.Turn(uuid(), "合成问题", "合成回答", MemoryContextState()) }
            val scope = ConversationSummaryPolicy.Scope(identity, conversation.id, "chat", null)
            val candidate = ConversationSummaryPolicy.plan(scope, turns, emptySet()).candidate!!
            val changed = ConversationSummaryPolicy.plan(scope.copy(chatGeneration = "new"), turns, emptySet()).candidate!!
            val request = uuid()
            var dao = db.conversationSummaryRequestDao()
            assertTrue(dao.prepare(identity, conversation.id, request, candidate, 2))
            assertEquals(ConversationSummaryProtocol.Action.SUBMIT, dao.next(identity, request, 0, candidate)!!.action)
            assertTrue(dao.receive(identity, request, 1, candidate.fingerprint, candidate,
                ConversationSummaryProtocol.Result.Withdrawn(false)))
            assertFalse(dao.find(request)!!.remoteFinished)
            assertFalse(dao.prepare(identity, conversation.id, uuid(), changed, 3))
            db.close(); db = open(); dao = db.conversationSummaryRequestDao()
            assertEquals(ConversationSummaryProtocol.Action.QUERY, dao.next(identity, request, 2, candidate)!!.action)
            val usage = ConversationSummaryProtocol.Usage(7, 2)
            assertTrue(dao.receive(identity, request, 3, candidate.fingerprint, candidate,
                ConversationSummaryProtocol.Result.Withdrawn(true, usage)))
            assertFalse(dao.receive(identity, request, 3, candidate.fingerprint, candidate,
                ConversationSummaryProtocol.Result.Completed("不得恢复", usage)))
            val row = dao.find(request)!!
            assertTrue(row.remoteFinished)
            assertEquals(7L, row.inputTokens)
            assertEquals(ConversationSummaryProtocol.State.CANCELLED, ConversationSummaryProtocol.decode(row.snapshotJson!!).state)
            assertNull(ConversationSummaryProtocol.decode(row.snapshotJson!!).summary)
            assertNull(dao.next(identity, request, row.revision, candidate)!!.action)
            assertTrue(dao.prepare(identity, conversation.id, uuid(), changed, 4))
        } finally { db.close() }
    }

    @Test fun 完成正文文件重开读取失效后不复活且删除保留用量() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val file = File(context.noBackupFilesDir, "summary-read-${uuid()}.db")
        fun open() = Room.databaseBuilder(context, AgentDatabase::class.java, file.absolutePath).build()
        var db = open()
        try {
            val conversation = db.conversationDao().create(identity, 1)
            val turns = (1..5).map { ConversationSummaryPolicy.Turn(uuid(), "问题", "回答", MemoryContextState()) }
            val candidate = ConversationSummaryPolicy.plan(ConversationSummaryPolicy.Scope(identity, conversation.id, "chat", null), turns, emptySet()).candidate!!
            val request = uuid()
            var dao = db.conversationSummaryRequestDao()
            assertTrue(dao.prepare(identity, conversation.id, request, candidate, 2))
            dao.next(identity, request, 0, candidate)
            assertTrue(dao.receive(identity, request, 1, candidate.fingerprint, candidate,
                ConversationSummaryProtocol.Result.Completed("合成账本正文", ConversationSummaryProtocol.Usage(7, 2))))
            db.close(); db = open(); dao = db.conversationSummaryRequestDao()
            assertNull(dao.readCompleted(identity.copy(credentialRef = "other"), conversation.id, candidate))
            assertEquals("合成账本正文", dao.readCompleted(identity, conversation.id, candidate))
            db.openHelper.writableDatabase.execSQL("UPDATE agent_summary_requests SET sourceIdsJson = '[]' WHERE requestId = ?", arrayOf(request))
            assertTrue(runCatching { dao.readCompleted(identity, conversation.id, candidate) }.isFailure)
            db.openHelper.writableDatabase.execSQL("UPDATE agent_summary_requests SET sourceIdsJson = ? WHERE requestId = ?",
                arrayOf(kotlinx.serialization.json.JsonArray(candidate.sourceIds.map { kotlinx.serialization.json.JsonPrimitive(it) }).toString(), request))
            assertEquals("合成账本正文", dao.readCompleted(identity, conversation.id, candidate))
            db.conversationLifecycleDao().archive(identity, conversation.id, true, 3)
            assertNull(dao.readCompleted(identity, conversation.id, candidate))
            db.conversationLifecycleDao().archive(identity, conversation.id, false, 4)
            db.conversationDao().select(identity, conversation.id)
            assertNull(dao.readCompleted(identity, conversation.id, candidate))
            val row = dao.find(request)!!
            assertNull(ConversationSummaryProtocol.decode(row.snapshotJson!!).summary)
            assertEquals(7L, row.inputTokens); assertTrue(row.remoteFinished)
            db.conversationLifecycleDao().delete(identity, conversation.id, 3)
            assertNull(dao.readCompleted(identity, conversation.id, candidate))
            assertEquals(7L, dao.find(request)!!.inputTokens)
        } finally { db.close() }
    }

    @Test fun 远端失败可结束但费用仍未知且其他身份不能领取() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AgentDatabase::class.java).build()
        try {
            val conversation = db.conversationDao().create(identity, 1)
            val turns = (1..5).map { ConversationSummaryPolicy.Turn(uuid(), "问题", "回答", MemoryContextState()) }
            val scope = ConversationSummaryPolicy.Scope(identity, conversation.id, "chat", null)
            val candidate = ConversationSummaryPolicy.plan(scope, turns, emptySet()).candidate!!
            val changed = ConversationSummaryPolicy.plan(scope.copy(chatGeneration = "new"), turns, emptySet()).candidate!!
            val request = uuid()
            val dao = db.conversationSummaryRequestDao()
            assertTrue(dao.prepare(identity, conversation.id, request, candidate, 2))
            assertNull(dao.next(identity.copy(ownerId = "other"), request, 0, candidate))
            assertEquals(0L, dao.find(request)!!.revision)
            assertEquals(ConversationSummaryProtocol.Action.SUBMIT, dao.next(identity, request, 0, candidate)!!.action)
            assertFalse(dao.prepare(identity, conversation.id, uuid(), changed, 3))
            assertTrue(dao.receive(identity, request, 1, candidate.fingerprint, candidate, ConversationSummaryProtocol.Result.Failed()))
            val row = dao.find(request)!!
            assertTrue(row.remoteFinished)
            assertEquals(ConversationSummaryProtocol.Cost.UNCERTAIN.name, row.cost)
            assertNull(row.inputTokens)
            assertTrue(dao.prepare(identity, conversation.id, uuid(), changed, 4))
            assertFalse(dao.prepare(identity, conversation.id, uuid(), candidate, 4))
        } finally { db.close() }
    }

    @Test fun 保存失败回滚且损坏快照不创建替代请求() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AgentDatabase::class.java).build()
        try {
            val conversation = db.conversationDao().create(identity, 1)
            val turns = (1..5).map { ConversationSummaryPolicy.Turn(uuid(), "问题", "回答", MemoryContextState()) }
            val candidate = ConversationSummaryPolicy.plan(ConversationSummaryPolicy.Scope(identity, conversation.id, "chat", null), turns, emptySet()).candidate!!
            val dao = db.conversationSummaryRequestDao()
            val request = uuid()
            assertTrue(dao.prepare(identity, conversation.id, request, candidate, 2))
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER synthetic_save_failure BEFORE UPDATE ON agent_summary_requests BEGIN SELECT RAISE(ABORT, 'synthetic'); END")
            assertTrue(runCatching { dao.next(identity, request, 0, candidate) }.isFailure)
            assertEquals(0L, dao.find(request)!!.revision)
            assertFalse(dao.find(request)!!.attempted)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER synthetic_save_failure")
            db.openHelper.writableDatabase.execSQL("UPDATE agent_summary_requests SET snapshotJson = 'broken' WHERE requestId = ?", arrayOf(request))
            assertTrue(runCatching { dao.next(identity, request, 0, candidate) }.isFailure)
            assertTrue(runCatching { dao.prepare(identity, conversation.id, uuid(), candidate, 3) }.isFailure)
            assertEquals(0L, dao.find(request)!!.revision)
        } finally { db.close() }
    }

    @Test fun 文件重开仅查询且失效后不恢复正文() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val file = File(context.noBackupFilesDir, "summary-request-${uuid()}.db")
        fun open() = Room.databaseBuilder(context, AgentDatabase::class.java, file.absolutePath).build()
        var db = open()
        try {
            val conversation = db.conversationDao().create(identity, 1)
            val turns = (1..5).map { ConversationSummaryPolicy.Turn(uuid(), "问题", "回答", MemoryContextState()) }
            val candidate = ConversationSummaryPolicy.plan(ConversationSummaryPolicy.Scope(identity, conversation.id, "chat", null), turns, emptySet()).candidate!!
            val request = uuid()
            assertTrue(db.conversationSummaryRequestDao().prepare(identity, conversation.id, request, candidate, 2))
            assertEquals(ConversationSummaryProtocol.Action.SUBMIT, db.conversationSummaryRequestDao().next(identity, request, 0, candidate)!!.action)
            db.close(); db = open()
            val dao = db.conversationSummaryRequestDao()
            val pending = dao.recoverable(identity.origin, identity.ownerId, identity.deviceId, identity.credentialRef).single()
            assertEquals(request, pending.requestId)
            assertEquals(1L, pending.revision)
            val start = CompletableDeferred<Unit>()
            val contenders = (1..2).map { async(Dispatchers.IO) {
                start.await(); dao.claimRecovery(identity, pending.requestId, pending.revision, candidate)
            } }
            start.complete(Unit)
            assertEquals(1, contenders.awaitAll().count { it?.action == ConversationSummaryProtocol.Action.QUERY })
            assertEquals(ConversationSummaryProtocol.Action.QUERY, dao.claimRecovery(identity, request, 2, null)!!.action)
            assertTrue(dao.receive(identity, request, 3, candidate.fingerprint, candidate,
                ConversationSummaryProtocol.Result.Completed("迟到摘要", ConversationSummaryProtocol.Usage(4, 1))))
            val row = dao.find(request)!!
            val snapshot = ConversationSummaryProtocol.decode(row.snapshotJson!!)
            assertEquals(ConversationSummaryProtocol.State.INVALID, snapshot.state)
            assertNull(snapshot.summary); assertTrue(row.remoteFinished)
            assertNull(dao.next(identity, request, 4, candidate)!!.action)
        } finally { db.close() }
    }

    @Test fun 并发领取一次且删除后仅补记用量() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AgentDatabase::class.java).build()
        try {
            val conversation = db.conversationDao().create(identity, 1)
            val turns = (1..5).map { ConversationSummaryPolicy.Turn(uuid(), "合成问题", "合成回答", MemoryContextState()) }
            val scope = ConversationSummaryPolicy.Scope(identity, conversation.id, "chat", null)
            val candidate = ConversationSummaryPolicy.plan(scope, turns, emptySet()).candidate!!
            val dao = db.conversationSummaryRequestDao()
            val request = uuid()
            assertTrue(dao.prepare(identity, conversation.id, request, candidate, 10))
            assertFalse(dao.prepare(identity, conversation.id, uuid(), candidate, 11))
            val start = CompletableDeferred<Unit>()
            val contenders = (1..2).map { async(Dispatchers.IO) { start.await(); dao.next(identity, request, 0, candidate) } }
            start.complete(Unit)
            assertEquals(1, contenders.awaitAll().count { it?.action == ConversationSummaryProtocol.Action.SUBMIT })
            assertEquals(ConversationSummaryProtocol.Action.QUERY, dao.next(identity, request, 1, candidate)!!.action)
            val changed = ConversationSummaryPolicy.plan(scope.copy(chatGeneration = "new"), turns, emptySet()).candidate!!
            assertFalse(dao.prepare(identity, conversation.id, uuid(), changed, 12))
            db.conversationLifecycleDao().delete(identity, conversation.id, 13)
            val deleted = dao.find(request)!!
            assertNull(deleted.conversationId); assertNull(deleted.snapshotJson)
            assertNull(deleted.sourceIdsJson); assertNull(deleted.referencesJson)
            assertFalse(deleted.remoteFinished)
            assertNull(dao.next(identity, request, deleted.revision, candidate))
            assertEquals(1, dao.recoverable(identity.origin, identity.ownerId, identity.deviceId, identity.credentialRef).size)
            assertEquals(ConversationSummaryProtocol.Action.QUERY, dao.claimRecovery(identity, request, deleted.revision, null)!!.action)
            assertNull(dao.claimRecovery(identity, request, deleted.revision, null))
            assertTrue(dao.receive(identity, request, deleted.revision + 1, candidate.fingerprint, null,
                ConversationSummaryProtocol.Result.Completed("不得恢复的正文", ConversationSummaryProtocol.Usage(10, 2))))
            val settled = dao.find(request)!!
            assertNull(settled.snapshotJson); assertTrue(settled.remoteFinished)
            assertEquals(10L, settled.inputTokens); assertEquals(2L, settled.outputTokens)
            assertFalse(dao.receive(identity, request, deleted.revision, candidate.fingerprint, candidate,
                ConversationSummaryProtocol.Result.Unknown))
        } finally { db.close() }
    }
}
