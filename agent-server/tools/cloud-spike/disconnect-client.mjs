import http from 'node:http';
import https from 'node:https';

// 只关闭本请求的独立 socket，不改系统、VPN 或其他应用网络。
export function disconnectRequest(url, token) {
  return new Promise((resolve, reject) => {
    let cut = false;
    const request = (new URL(url).protocol === 'https:' ? https : http).request(url, {
      method:'POST', agent:false,
      headers:{ authorization:`Bearer ${token}`, 'accept-encoding':'identity' },
    }, response => {
      if (response.statusCode !== 200) {
        response.destroy();
        reject(new Error(`断连探针状态码 ${response.statusCode}`));
        return;
      }
      let text = '';
      response.setEncoding('utf8');
      response.on('data', chunk => {
        if (cut) return;
        text += chunk;
        try {
          if (text.length > 4096) throw new Error('首帧超限');
          const end = text.indexOf('\n');
          if (end < 0) return;
          const first = JSON.parse(text.slice(0, end));
          if (!first.taskId || first.transportEntered !== true || response.complete)
            throw new Error('未取得传输中首帧，不能确认断连');
          const socket = response.socket;
          const responseCompleteBeforeDestroy = response.complete;
          cut = true;
          socket.once('close', () => resolve({ ...first, responseCompleteBeforeDestroy,
            socketDestroyed:socket.destroyed, socketCloseObserved:true }));
          response.destroy();
          socket.destroy();
        } catch (error) { response.destroy(); reject(error); }
      });
      response.on('end', () => { if (!cut) reject(new Error('响应已结束，没有可断开的任务流')); });
      response.on('error', error => { if (!cut) reject(error); });
    });
    const timer = setTimeout(() => { request.destroy(); reject(new Error('断连探针超时')); }, 15000);
    request.on('close', () => clearTimeout(timer));
    request.on('error', error => { if (!cut) reject(error); });
    request.end();
  });
}
