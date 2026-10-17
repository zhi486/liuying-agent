import { parseCountingRequest, type WireCall, type WireTool } from './request.ts';

// 按锁定的 DeepSeek V4.1 encoding.py 移植纯文本/chat 分支。
// 官方工具说明须逐字保留；MIT 归属见 tools/token-count/LICENSE.deepseek。
const toolInstructions = `## Tools

You have access to a set of tools to help answer the user's question. You can invoke tools by writing a "<｜DSML｜ calls>" block like the following:

<｜DSML｜ calls>
<｜DSML｜ invoke name="$TOOL_NAME">
<｜DSML｜ parameter name="$PARAMETER_NAME" string="true|false">$PARAMETER_VALUE</｜DSML｜ parameter>
...
</｜DSML｜ invoke>
<｜DSML｜ invoke name="$TOOL_NAME2">
...
</｜DSML｜ invoke>
</｜DSML｜ calls>

String parameters should be specified as is and set \`string="true"\`. For all other types (numbers, booleans, arrays, objects), pass the value in JSON format and set \`string="false"\`.

If thinking_mode is enabled (triggered by <think>), you MUST output your complete reasoning inside <think>...</think> BEFORE any tool calls or final response.

Otherwise, output directly after </think> with tool calls or final response.

### Available Tool Schemas

`;

// 请求验证器只允许固定 schema 的字符串/布尔/对象/数组，保留 Python 的空格规则。
function pythonJson(value: unknown): string {
  if (typeof value === 'string' || typeof value === 'boolean') return JSON.stringify(value);
  if (Array.isArray(value)) return '[' + value.map(pythonJson).join(', ') + ']';
  if (value !== null && typeof value === 'object') return '{' +
    Object.entries(value).map(([key, item]) => `${JSON.stringify(key)}: ${pythonJson(item)}`).join(', ') + '}';
  throw new Error('输入计数不可用');
}

function renderTools(tools: WireTool[]): string {
  return toolInstructions + tools.map(tool => pythonJson(tool.function)).join('\n') +
    '\n\nYou MUST strictly follow the above defined tool name and parameter schemas to invoke tool calls.\n';
}

function renderCalls(calls: WireCall[]): string {
  return '\n\n<｜DSML｜ calls>\n' + calls.map(call => {
    const args: Record<string, string> = JSON.parse(call.function.arguments);
    const parameters = Object.entries(args).map(([key, value]) =>
      `<｜DSML｜ parameter name="${key}" string="true">${value}</｜DSML｜ parameter>`).join('\n');
    return `<｜DSML｜ invoke name="${call.function.name}">\n${parameters}\n</｜DSML｜ invoke>`;
  }).join('\n') + '\n</｜DSML｜ calls><｜end▁of▁sentence｜>';
}

export function renderInputPrompt(raw: string): string {
  const { messages, tools } = parseCountingRequest(raw);
  let prompt = '<｜begin▁of▁sentence｜><｜System｜>' + messages[0]!.content;
  if (tools.length) prompt += '\n\n' + renderTools(tools);
  let order = new Map<string, number>();
  for (let index = 1; index < messages.length;) {
    const message = messages[index]!;
    if (message.role === 'assistant') {
      if (!('tool_calls' in message)) {
        prompt += message.content + '<｜end▁of▁sentence｜>';
        index++;
        continue;
      }
      order = new Map(message.tool_calls.map((call, position) => [call.id, position]));
      prompt += renderCalls(message.tool_calls);
      index++;
      continue;
    }
    const blocks: { content: string; callId?: string }[] = [];
    while (index < messages.length) {
      const item = messages[index]!;
      if (item.role === 'assistant') break;
      blocks.push(item.role === 'tool'
        ? { content: `<tool_result>${item.content}</tool_result>`, callId: item.tool_call_id }
        : { content: item.content });
      index++;
    }
    const sortedTools = blocks.filter(block => block.callId !== undefined)
      .sort((a, b) => order.get(a.callId!)! - order.get(b.callId!)!);
    let toolIndex = 0;
    prompt += '<｜User｜>' + blocks.map(block =>
      block.callId === undefined ? block.content : sortedTools[toolIndex++]!.content).join('\n\n') +
      '<｜Assistant｜></think>';
  }
  return prompt;
}
