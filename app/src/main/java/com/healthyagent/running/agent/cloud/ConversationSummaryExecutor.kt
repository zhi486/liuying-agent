package com.healthyagent.running.agent.cloud

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 尚未装配；每次显式调用只领取及执行一个动作，异常与取消均不自动重试。 */
internal class ConversationSummaryExecutor(
    private val host: CloudAgentService,
    private val client: ConversationSummaryHttpClient,
) {
    internal class Dispatch(
        val identity: CloudTaskIdentity,
        val step: ConversationSummaryProtocol.Step,
        val candidate: ConversationSummaryPolicy.Candidate?,
    ) {
        override fun toString() = "Dispatch(身份及来源已隐藏)"
    }

    suspend fun execute(conversationId: String, requestId: String, revision: Long): Boolean {
        require(revision >= 0 && revision < Long.MAX_VALUE)
        currentCoroutineContext().ensureActive()
        val dispatch = host.claimSummaryDispatch(conversationId, requestId, revision) ?: return false
        currentCoroutineContext().ensureActive()
        val receipt = client.execute(dispatch.identity, dispatch.step, dispatch.candidate)
        currentCoroutineContext().ensureActive()
        return host.receiveSummaryReceipt(conversationId, requestId, revision + 1,
            dispatch.step.snapshot.fingerprint, receipt)
    }

    /** 一次清单、每条至多一次 GET；单条失败不重试，取消立即向上传播。 */
    suspend fun recoverOnce(): Int {
        var accepted = 0
        for (row in host.summaryRecoveryRows()) {
            currentCoroutineContext().ensureActive()
            try {
                val dispatch = host.claimSummaryRecovery(row) ?: continue
                currentCoroutineContext().ensureActive()
                val receipt = client.execute(dispatch.identity, dispatch.step, null)
                if (host.receiveSummaryReceipt(row.conversationId, row.requestId, row.revision + 1,
                        row.fingerprint, receipt)) accepted++
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { /* 坏记录或失联只隔离本条，不重建、不重发。 */ }
        }
        return accepted
    }
}
