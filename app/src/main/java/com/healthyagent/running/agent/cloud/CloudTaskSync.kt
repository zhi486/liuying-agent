package com.healthyagent.running.agent.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 原生调用方持有单例；只做显式前台操作，不扫描旧任务或创建后台重试。 */
class CloudTaskSync(private val dao: CloudTaskIntentDao, private val api: CloudTaskApi, private val now: () -> Long = System::currentTimeMillis) {
    private val operation = Mutex()

    suspend fun submit(identity: CloudTaskIdentity, requestId: String): CloudTaskReceipt? = guarded {
        operation.withLock {
            val intent = dao.find(identity, requestId) ?: throw CloudTaskFailure("not_found")
            val context = if (intent.memoryRefsJson != null) dao.claimSelected(identity, requestId, now()) else null
            val claimed = if (intent.memoryRefsJson != null) context != null else dao.claimSubmission(identity, requestId, now())
            if (claimed) {
                dao.accept(identity, requestId, if (context == null) api.submit(intent) else api.submitSelected(intent, context))
                finish(identity, requestId)
            } else recoverLocked(identity, requestId)
        }
    }

    suspend fun recover(identity: CloudTaskIdentity, requestId: String): CloudTaskReceipt? = guarded {
        operation.withLock { recoverLocked(identity, requestId) }
    }

    suspend fun control(identity: CloudTaskIdentity, requestId: String, action: String): CloudTaskReceipt? = guarded {
        // 在等待网络锁前保存撤回，旧请求返回时也不得重新存入正文。
        dao.requestControl(identity, requestId, action, now()) ?: throw CloudTaskFailure("not_found")
        operation.withLock { recoverLocked(identity, requestId) }
    }

    private suspend fun recoverLocked(identity: CloudTaskIdentity, requestId: String): CloudTaskReceipt? {
        val intent = dao.find(identity, requestId) ?: throw CloudTaskFailure("not_found")
        if (!intent.submissionAttempted) return dao.receipt(identity, requestId)
        val saved = dao.receipt(identity, requestId)
        // 404 或断流直接返回静态错误，绝不重发 POST 或生成新键。
        val response = if (saved == null) api.find(identity, requestId) else api.get(identity, saved.cloudTaskId)
        dao.accept(identity, requestId, response)
        return finish(identity, requestId)
    }

    private suspend fun finish(identity: CloudTaskIdentity, requestId: String): CloudTaskReceipt? {
        var saved = dao.receipt(identity, requestId) ?: return null
        var intent = dao.find(identity, requestId) ?: throw CloudTaskFailure("not_found")
        val control = intent.pendingControl
        if (control != null && control != saved.confirmedControl) {
            saved = dao.accept(identity, requestId, api.control(identity, saved.cloudTaskId, control))
        }
        intent = dao.find(identity, requestId) ?: throw CloudTaskFailure("not_found")
        saved = dao.receipt(identity, requestId) ?: return null
        if (saved.ackPending && saved.resultJson != null && intent.pendingControl != "withdraw") {
            dao.accept(identity, requestId, api.control(identity, saved.cloudTaskId, "acknowledge"))
        }
        return dao.receipt(identity, requestId)
    }

    private suspend fun <T> guarded(block: suspend () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: CloudTaskFailure) { throw failure }
    catch (_: Exception) { throw CloudTaskFailure("storage_unavailable") }
}
