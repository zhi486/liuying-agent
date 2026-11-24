import type {
  AppSnapshot,
  CommandBodyMap,
  CommandName,
  NativeToWebEnvelope,
  ResultPayload,
  WebToNativeEnvelope,
} from './contract';
import { parseInboundEnvelope } from './schemas';

export interface NativeBridgePort {
  postMessage(message: string): void;
  onmessage: ((event: { data: string }) => void) | null;
}

export interface NativeBridgeCallbacks {
  onReady(snapshot: AppSnapshot): void;
  onSnapshot(snapshot: AppSnapshot): void;
  onError(): void;
  onCloudChanged?(): void;
}

export class NativeBridgeClient {
  private helloId: string | null = null;
  private activeHelloId: string | null = null;
  private latestRevision = -1;
  private readonly pendingCommands = new Map<
    string,
    {
      resolve: (result: ResultPayload) => void;
      reject: (error: Error) => void;
      timeout: ReturnType<typeof setTimeout>;
    }
  >();

  constructor(
    private readonly port: NativeBridgePort,
    private readonly callbacks: NativeBridgeCallbacks,
  ) {}

  start(): void {
    this.port.onmessage = (event) => this.handleInbound(event.data);
    this.helloId = crypto.randomUUID();
    this.post({
      contractVersion: 1,
      type: 'hello',
      id: this.helloId,
      sentAt: new Date().toISOString(),
      payload: {
        buildId: 'liuying-web',
        supportedContractVersions: [1],
      },
    });
  }

  sendCommand<TName extends CommandName>(
    name: TName,
    body: CommandBodyMap[TName],
  ): Promise<ResultPayload> {
    if (!this.activeHelloId) {
      return Promise.reject(new Error('原生 Bridge 尚未就绪'));
    }

    const commandId = crypto.randomUUID();
    const envelope = {
      contractVersion: 1,
      type: 'command',
      id: commandId,
      sentAt: new Date().toISOString(),
      payload: {
        helloId: this.activeHelloId,
        name,
        body,
      },
    } as WebToNativeEnvelope;

    return new Promise<ResultPayload>((resolve, reject) => {
      const timeout = setTimeout(
        () => {
          this.pendingCommands.delete(commandId);
          reject(new Error('原生命令响应超时'));
        },
        name === 'mcp.discover' ? 40_000 : 10_000,
      );
      this.pendingCommands.set(commandId, { resolve, reject, timeout });

      try {
        this.post(envelope);
      } catch (error) {
        clearTimeout(timeout);
        this.pendingCommands.delete(commandId);
        reject(error instanceof Error ? error : new Error('原生命令发送失败'));
      }
    });
  }

  dispose(): void {
    this.port.onmessage = null;
    this.pendingCommands.forEach(({ reject, timeout }) => {
      clearTimeout(timeout);
      reject(new Error('原生 Bridge 已关闭'));
    });
    this.pendingCommands.clear();
    this.activeHelloId = null;
  }

  private handleInbound(raw: string): void {
    const parsed = parseInboundEnvelope(raw);
    if (!parsed.ok) {
      this.callbacks.onError();
      return;
    }

    const envelope = parsed.value;
    switch (envelope.type) {
      case 'ready':
        this.acceptReady(envelope);
        break;
      case 'snapshot':
        this.acceptSnapshot(envelope.payload);
        break;
      case 'result':
        this.acceptResult(envelope);
        break;
      case 'event':
        if (this.activeHelloId && envelope.payload.name === 'cloud.changed')
          this.callbacks.onCloudChanged?.();
        break;
    }
  }

  private acceptReady(envelope: Extract<NativeToWebEnvelope, { type: 'ready' }>): void {
    if (envelope.payload.helloId !== this.helloId) return;
    this.activeHelloId = envelope.payload.helloId;
    this.latestRevision = envelope.payload.snapshot.revision;
    this.callbacks.onReady(envelope.payload.snapshot);
  }

  private acceptSnapshot(snapshot: AppSnapshot): void {
    if (!this.activeHelloId || snapshot.revision <= this.latestRevision) return;
    this.latestRevision = snapshot.revision;
    this.callbacks.onSnapshot(snapshot);
  }

  private acceptResult(envelope: Extract<NativeToWebEnvelope, { type: 'result' }>): void {
    const pending = this.pendingCommands.get(envelope.id);
    if (!pending) return;
    clearTimeout(pending.timeout);
    this.pendingCommands.delete(envelope.id);
    pending.resolve(envelope.payload);
  }

  private post(envelope: WebToNativeEnvelope): void {
    this.port.postMessage(JSON.stringify(envelope));
  }
}

declare global {
  interface Window {
    LiuyingNative?: NativeBridgePort;
  }
}
