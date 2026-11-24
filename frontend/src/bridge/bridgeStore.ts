import { create } from 'zustand';

import type { AppSnapshot, CommandBodyMap, CommandName, ResultPayload } from './contract';
import { NativeBridgeClient } from './NativeBridgeClient';

type BridgeStatus = 'unavailable' | 'connecting' | 'ready' | 'error';

interface BridgeState {
  status: BridgeStatus;
  snapshot: AppSnapshot | null;
  cloudRevision: number;
  setState: (state: Pick<BridgeState, 'status' | 'snapshot'>) => void;
}

const initialStatus: BridgeStatus =
  typeof window !== 'undefined' && window.LiuyingNative ? 'connecting' : 'unavailable';

export const useBridgeStore = create<BridgeState>((set) => ({
  status: initialStatus,
  snapshot: null,
  cloudRevision: 0,
  setState: (state) => set(state),
}));

let activeClient: NativeBridgeClient | null = null;

export function connectNativeBridge(): () => void {
  const port = window.LiuyingNative;
  if (!port) {
    useBridgeStore.getState().setState({ status: 'unavailable', snapshot: null });
    return () => undefined;
  }

  activeClient?.dispose();
  useBridgeStore.getState().setState({ status: 'connecting', snapshot: null });
  const client = new NativeBridgeClient(port, {
    onReady: (snapshot) => {
      useBridgeStore.getState().setState({ status: 'ready', snapshot });
    },
    onSnapshot: (snapshot) => {
      useBridgeStore.getState().setState({ status: 'ready', snapshot });
    },
    onError: () => {
      useBridgeStore.getState().setState({ status: 'error', snapshot: null });
    },
    onCloudChanged: () => {
      useBridgeStore.setState((state) => ({ cloudRevision: state.cloudRevision + 1 }));
    },
  });
  activeClient = client;
  client.start();

  return () => {
    if (activeClient === client) activeClient = null;
    client.dispose();
  };
}

export function sendNativeCommand<TName extends CommandName>(
  name: TName,
  body: CommandBodyMap[TName],
): Promise<ResultPayload> {
  if (!activeClient) return Promise.reject(new Error('原生 Bridge 不可用'));
  return activeClient.sendCommand(name, body);
}
