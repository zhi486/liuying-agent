const logKeys = ['message','role','phase','probeId','path','calls','taskId','ownerId',
  'case','mode','actualTokens','exactIds','isolateMarker','instanceMarker','isolateCall','seenCaseBefore'];
export function tailRecord(raw) {
  const logs = [];
  for (const log of raw.logs ?? []) {
    if (!JSON.stringify(log.message).includes('spike-')) continue;
    for (const value of Array.isArray(log.message) ? log.message : [log.message]) {
      try {
        const parsed = typeof value === 'string' ? JSON.parse(value) : value;
        if (!parsed || typeof parsed !== 'object') continue;
        logs.push(Object.fromEntries(logKeys.filter(key=>Object.hasOwn(parsed,key)).map(key=>[key,parsed[key]])));
      } catch { /* 非 JSON 的日志标签不保存正文。 */ }
    }
  }
  return {eventTimestamp:raw.eventTimestamp,outcome:raw.outcome,scriptName:raw.scriptName,
    cpuTime:raw.cpuTime,wallTime:raw.wallTime,entrypoint:raw.entrypoint,executionModel:raw.executionModel,
    rpcMethod:raw.event?.rpcMethod,receivedFields:Object.keys(raw),eventFields:Object.keys(raw.event ?? {}),
    path:raw.event?.request?.url ? new URL(raw.event.request.url).pathname : undefined,
    status:raw.event?.response?.status,exceptionNames:(raw.exceptions ?? []).map(value=>value.name),logs};
}
