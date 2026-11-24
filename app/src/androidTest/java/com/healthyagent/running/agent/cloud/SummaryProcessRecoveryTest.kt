package com.healthyagent.running.agent.cloud

import android.content.Context
import android.os.Bundle
import android.os.Process
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.healthyagent.running.agent.local.AgentDatabase
import com.healthyagent.running.agent.memory.MemoryContextState
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 外部驱动必须在种子阶段报告就绪后终止活进程，再启动恢复阶段。 */
@RunWith(AndroidJUnit4::class)
class SummaryProcessRecoveryTest {
    @Test fun 领取落盘后强制结束进程恢复仅查询原请求() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val phase = arguments.getString("summaryPhase")
        assumeTrue("仅供隔离模拟器两阶段驱动", phase in setOf("seed", "recover"))
        val runId = requireNotNull(arguments.getString("summaryRun"))
        UUID.fromString(runId)
        val context: Context = ApplicationProvider.getApplicationContext()
        val file = File(context.noBackupFilesDir, "summary-process-$runId.db")
        val proof = context.getSharedPreferences("summary-process-$runId", Context.MODE_PRIVATE)
        if (phase == "seed") assertFalse(file.exists()) else assertTrue(file.exists())
        val db = Room.databaseBuilder(context, AgentDatabase::class.java, file.absolutePath).build()
        val identity = CloudTaskIdentity("https://agent.example.com", "synthetic-owner", "synthetic-device", "synthetic-credential")
        fun id(value: String): String {
            val hash = UUID.nameUUIDFromBytes("$runId:$value".toByteArray())
            return UUID((hash.mostSignificantBits and -61441L) or 16384L, hash.leastSignificantBits).toString()
        }
        try {
            val conversationId = if (phase == "seed") db.conversationDao().create(identity, 1).id
                else requireNotNull(proof.getString("conversationId", null))
            val turns = (1..5).map { ConversationSummaryPolicy.Turn(id("turn-$it"), "合成问题", "合成回答", MemoryContextState()) }
            val candidate = ConversationSummaryPolicy.plan(ConversationSummaryPolicy.Scope(identity, conversationId, "chat", null), turns, emptySet()).candidate!!
            val request = id("request")
            val dao = db.conversationSummaryRequestDao()
            if (phase == "seed") {
                assertTrue(dao.prepare(identity, conversationId, request, candidate, 2))
                assertEquals(ConversationSummaryProtocol.Action.SUBMIT, dao.next(identity, request, 0, candidate)!!.action)
                assertTrue(proof.edit().putInt("pid", Process.myPid()).putString("conversationId", conversationId).commit())
                InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                    putString("stream", "SUMMARY_READY pid=${Process.myPid()} request=$request\n")
                })
                delay(180000)
                fail("外部驱动未在就绪后终止进程")
            } else {
                assertNotEquals(proof.getInt("pid", -1), Process.myPid())
                val row = dao.find(request)!!
                assertEquals(1L, row.revision); assertTrue(row.attempted)
                assertEquals(ConversationSummaryProtocol.Cost.UNCERTAIN.name, row.cost)
                assertNull(dao.next(identity, request, 0, candidate))
                assertEquals(ConversationSummaryProtocol.Action.QUERY, dao.next(identity, request, 1, candidate)!!.action)
                assertFalse(dao.prepare(identity, conversationId, id("replacement"), candidate, 3))
                assertEquals(request, dao.find(request)!!.requestId)
                InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                    putString("stream", "SUMMARY_RECOVERED previous=${proof.getInt("pid", -1)} current=${Process.myPid()} request=$request\n")
                })
            }
        } finally { db.close() }
    }
}
