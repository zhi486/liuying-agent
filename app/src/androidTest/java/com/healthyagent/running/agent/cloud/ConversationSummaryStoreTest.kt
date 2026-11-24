package com.healthyagent.running.agent.cloud

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.healthyagent.running.agent.local.AgentDatabase
import com.healthyagent.running.agent.memory.MemoryContextState
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationSummaryStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val identity = CloudTaskIdentity("https://agent.example.com", "owner", "device", "credential")
    private fun uuid() = UUID.randomUUID().toString()

    @Test fun 文件库重开拒绝旧进程待完成结果且删除后不能复活() = runBlocking {
        val file = File(context.noBackupFilesDir, "summary-" + uuid() + ".db")
        fun open() = Room.databaseBuilder(context, AgentDatabase::class.java, file.absolutePath).build()
        var database = open()
        try {
            val conversation = database.conversationDao().create(identity, 1)
            val rows = (1..5).map { ConversationSummaryPolicy.Turn(uuid(), "合成问题", "合成回答", MemoryContextState()) }
            val scope = ConversationSummaryPolicy.Scope(identity, conversation.id, "chat", "memory")
            val candidate = ConversationSummaryPolicy.plan(scope, rows, emptySet()).candidate!!
            val process = uuid()
            val attempt = uuid()
            assertTrue(database.conversationSummaryDao().begin(identity, conversation.id, candidate, attempt, process, 10, 100))
            assertFalse(database.conversationSummaryDao().begin(identity, conversation.id, candidate, uuid(), process, 10, 100))
            database.close()
            database = open()
            assertNull(database.conversationSummaryDao().read(identity, conversation.id, candidate, uuid()))
            assertFalse(database.conversationSummaryDao().accept(identity, conversation.id, candidate, attempt, process, "迟到摘要", 20))
            val changed = ConversationSummaryPolicy.plan(scope.copy(chatGeneration = "new"), rows, emptySet()).candidate!!
            val next = uuid()
            assertTrue(database.conversationSummaryDao().begin(identity, conversation.id, changed, next, process, 20, 100))
            assertFalse(database.conversationSummaryDao().accept(identity, conversation.id, candidate, attempt, process, "旧回调", 21))
            assertTrue(database.conversationSummaryDao().accept(identity, conversation.id, changed, next, process, "合成摘要", 22))
            assertFalse(database.conversationSummaryDao().accept(identity, conversation.id, changed, next, process, "重复", 23))
            assertFalse(database.conversationSummaryDao().accept(identity, conversation.id, changed, next, process, "超时重复", 101))
            assertEquals("合成摘要", database.conversationSummaryDao().read(identity, conversation.id, changed, process))
            database.close()
            database = open()
            assertEquals("合成摘要", database.conversationSummaryDao().read(identity, conversation.id, changed, uuid()))
            database.conversationLifecycleDao().delete(identity, conversation.id, 30)
            assertNull(database.conversationSummaryDao().find(conversation.id))
            assertFalse(database.conversationSummaryDao().accept(identity, conversation.id, changed, next, process, "删除后迟到", 31))
        } finally { database.close() }
    }

    @Test fun 截止回调关闭尝试且并发结果只接纳一个() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).build()
        try {
            val conversation = database.conversationDao().create(identity, 1)
            val rows = (1..5).map { ConversationSummaryPolicy.Turn(uuid(), "问题", "回答", MemoryContextState()) }
            val scope = ConversationSummaryPolicy.Scope(identity, conversation.id, "chat", null)
            val candidate = ConversationSummaryPolicy.plan(scope, rows, emptySet()).candidate!!
            val dao = database.conversationSummaryDao()
            val process = uuid()
            val expired = uuid()
            assertTrue(dao.begin(identity, conversation.id, candidate, expired, process, 10, 100))
            assertFalse(dao.accept(identity, conversation.id, candidate, expired, process, "到期", 100))
            assertFalse(dao.accept(identity, conversation.id, candidate, expired, process, "时钟回退", 99))
            assertFalse(dao.begin(identity, conversation.id, candidate, uuid(), process, 101, 200))
            val changed = ConversationSummaryPolicy.plan(scope.copy(chatGeneration = "new"), rows, emptySet()).candidate!!
            val attempt = uuid()
            assertTrue(dao.begin(identity, conversation.id, changed, attempt, process, 101, 200))
            val start = CompletableDeferred<Unit>()
            val results = (1..2).map { index -> async(Dispatchers.IO) {
                start.await()
                dao.accept(identity, conversation.id, changed, attempt, process, "摘要" + index, 199)
            } }
            start.complete(Unit)
            assertEquals(1, results.awaitAll().count { it })
            assertNotNull(dao.read(identity, conversation.id, changed, process))
        } finally { database.close() }
    }
}
