import type { BudgetDatabase } from './d1.ts';
import { AgentError, type AgentTask, type BudgetPort } from './contracts.ts';
import { createCloudTasks } from './cloud-tasks.ts';
import {
  CloudError,
  cloudOperation,
  parseSubmission,
  timestamp,
  type CloudTaskView,
} from './cloud-task-contracts.ts';
import { createExecutionLedger } from './execution-ledger.ts';
import { createD1Budget, type RateCard } from './budget.ts';
import { createUsageReceipts } from './usage-receipts.ts';
import { createDeepseekModel } from './deepseek.ts';
import { runAgent } from './loop.ts';
import { createKnowledgeIndex } from './knowledge.ts';
import { CURATED_KNOWLEDGE } from './knowledge-corpus.ts';
import { taskCostEstimate } from './cost-estimate.ts';
import { retainBackground, type WaitUntil } from './background.ts';

export function createAgentService(options: {
  db: BudgetDatabase;
  rate: RateCard;
  apiKey: string;
  countInputTokens: (serializedRequest: string) => Promise<number>;
  fetch?: typeof globalThis.fetch;
  now?: () => number;
}) {
  const { db, apiKey, countInputTokens } = options;
  const now = options.now ?? Date.now;
  const rate = { ...options.rate };
  const transport = options.fetch;
  const tasks = createCloudTasks({ db, now });
  const running = new Map<string, AbortController>();

  async function execute(
    authorization: unknown,
    input: AgentTask,
    task: CloudTaskView,
    preparedAt: number,
    waitUntil?: WaitUntil,
  ) {
    const controller = new AbortController();
    const remaining = Math.min(
      task.deadlineAtMillis - timestamp(now),
      30000 - (performance.now() - preparedAt),
    );
    if (remaining <= 0) throw new CloudError('unavailable');
    running.set(task.id, controller);
    let timer: ReturnType<typeof setTimeout> | undefined;
    const deadline = new Promise<never>((_, reject) => {
      timer = setTimeout(
        () => {
          controller.abort(new AgentError('deadline'));
          reject(new CloudError('unavailable'));
        },
        Math.max(0, remaining),
      );
    });
    const operation = async () => {
      controller.signal.throwIfAborted();
      const execution = await tasks.claim(authorization, task.id);
      controller.signal.throwIfAborted();
      const ledger = await createExecutionLedger({ db, execution, now });
      const timeoutMs = Math.min(remaining, await ledger.check(controller.signal));
      const persistent = createD1Budget({
        db,
        ownerId: ledger.ownerId,
        execution: ledger.binding,
        rate,
        now,
      });
      const receipts = createUsageReceipts({
        db,
        ownerId: ledger.ownerId,
        execution: ledger.binding,
      });
      const reservations = new Map<string, string>();
      const budget: BudgetPort = {
        async reserve(request, signal) {
          const reserved = await persistent.reserve(request, signal);
          if (reserved) reservations.set(request.requestId, reserved.reservationId);
          return reserved;
        },
        settle: persistent.settle,
      };
      const reservationFor = (requestId: string) => {
        const id = reservations.get(requestId);
        if (!id) throw new AgentError('budget_unavailable');
        return id;
      };
      const model = createDeepseekModel({
        apiKey,
        countInputTokens,
        ...(transport ? { fetch: transport } : {}),
        beforeDispatch: (requestId, signal) =>
          ledger.beginDispatch(requestId, reservationFor(requestId), signal),
        onUsage: (requestId, usage) => receipts.record(reservationFor(requestId), usage),
        ...(waitUntil ? { waitUntil } : {}),
      });
      let checkFailed = false;
      const result = await runAgent(
        { ...input, taskId: task.id },
        { model, budget },
        {
          signal: controller.signal,
          timeoutMs,
          ...(input.knowledge ? { knowledge: { index: createKnowledgeIndex(CURATED_KNOWLEDGE),
            library: input.knowledge.library, allowSpoilers: input.knowledge.allowSpoilers } } : {}),
          ...(waitUntil ? { waitUntil } : {}),
          async checkExecution(signal) {
            try {
              await ledger.check(signal);
            } catch (error) {
              if (error instanceof AgentError) throw error;
              checkFailed = true;
              throw new AgentError('budget_unavailable');
            }
          },
        },
      );
      if (checkFailed) throw new CloudError('unavailable');
      const cost = await taskCostEstimate(db, ledger.ownerId, task.id, rate, timestamp(now));
      await tasks.finish(execution, { ...result, cost });
    };
    try {
      const pending = operation();
      retainBackground(pending, waitUntil);
      await Promise.race([pending, deadline]);
    } catch (error) {
      // 保活注册等失败必须先停止新请求，不能等终态回读完成才取消。
      controller.abort(new AgentError('cancelled'));
      // 取消/截止的竞争只能接受数据库已保存的终态；不能直接交付内存答案。
      if (
        (error instanceof CloudError && error.code === 'inactive') ||
        error instanceof AgentError
      ) {
        try {
          const stored = await Promise.race([tasks.get(authorization, task.id), deadline]);
          if (stored.status !== 'queued' && stored.status !== 'running') return;
        } catch {
          /* 无法可靠读回时统一失败，保留任务供原截止收敛。 */
        }
      }
      throw new CloudError('unavailable');
    } finally {
      if (timer !== undefined) clearTimeout(timer);
      controller.abort(new AgentError('cancelled'));
      if (running.get(task.id) === controller) running.delete(task.id);
    }
  }

  return {
    prepare(authorization: unknown, input: unknown) {
      return cloudOperation(async () => {
        // 在第一次 await 之前深度校验并冻结，不能捕获调用方事后可变摘要。
        const parsed = parseSubmission(input);
        const preparedAt = performance.now();
        const accepted = await tasks.accept(authorization, {
          clientRequestId: parsed.clientRequestId,
          localTaskId: parsed.task.taskId,
          prompt: parsed.task.prompt,
          consent: parsed.task.consent,
          summaries: parsed.task.summaries,
          ...(parsed.task.memoryContext ? { memoryContext: parsed.task.memoryContext } : {}),
          ...(parsed.task.knowledge ? { knowledge: parsed.task.knowledge } : {}),
          ...(parsed.task.conversation ? { conversation: parsed.task.conversation } : {}),
        });
        let used = false;
        return {
          task: structuredClone(accepted.task),
          run: !accepted.created
            ? null
            : async ({ waitUntil }: { waitUntil?: WaitUntil } = {}): Promise<void> => {
                if (used) throw new CloudError('conflict');
                used = true;
                await execute(authorization, parsed.task, accepted.task, preparedAt, waitUntil);
              },
        };
      });
    },
    get: tasks.get,
    findByRequest: tasks.findByRequest,
    acknowledge: tasks.acknowledge,
    async cancel(authorization: unknown, taskId: unknown) {
      const task = await tasks.cancel(authorization, taskId);
      if (task.status === 'cancelled') running.get(task.id)?.abort(new AgentError('cancelled'));
      return task;
    },
    async withdraw(authorization: unknown, taskId: unknown) {
      const task = await tasks.withdraw(authorization, taskId);
      running.get(task.id)?.abort(new AgentError('cancelled'));
      return task;
    },
  };
}
