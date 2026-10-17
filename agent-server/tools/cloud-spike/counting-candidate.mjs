// 隔离性能候选，尚未接入生产计数器。
export function candidateTokens(tokenizer, prompt) {
  return tokenizer.tokenize(prompt, { add_special_tokens:false });
}
