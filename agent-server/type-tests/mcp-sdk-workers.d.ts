// SDK 2.3.1 将未使用的 stdio ReadBuffer 声明并入 HTTP 客户端声明，漏掉 Buffer 类型导入。
// 本项目只支持远程 HTTP：将该 stdio 参数设为不可构造，不伪造 Buffer API，也不加载 Node 全局类型。
// 升级 SDK 修复其声明后应移除此兼容项；这里只影响类型检查，不注入运行时代码。
type Buffer = never;
