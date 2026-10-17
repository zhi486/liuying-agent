# 手机聊天抽屉与底部输入区修复

日期：2026-09-29。用户反馈真机点击左上菜单只出现遮罩／重叠标题，找不到跑步记录，并要求输入框一直保持屏幕底部，参考 GitHub 上 DeepSeek 手机端前端。

## 调查与参考

- GitHub 搜索与源码核对得到社区项目 [DeepSeek-Chat-Clone](https://github.com/GrowWithMehran/DeepSeek-Chat-Clone/blob/main/style.css) 及 [dsh-mobile-app](https://github.com/chenwj1957/dsh-mobile-app/blob/master/public/app.css)。前者 MIT 授权，但移动菜单功能有限；后者展示了独立聊天滚动区和底部输入区。这些不是已核实的 DeepSeek 官方 App 源码。
- 只借鉴布局关系，未复制第三方源码、资产或引入依赖；保留流萤白底青绿色、深色主题、右上跑步切换、语音及单次授权。
- 正式打包资源配合十条合成长回答复现：输入框完全离开视口；菜单里的记录入口被挤到 y≈767px。开发空态不能覆盖这些问题。
- 真机 WebView 为 151.0.7922.199，未发现旧内核不支持动态视口的证据。本轮临时 WebView 调试连接超时后已移除，因此不声称已直接确认截图里抽屉未绘制的唯一原因。

## 修复

- 菜单通过 Portal 放到聊天容器之外，显式设置遮罩／抽屉层级和不透明底色；打开后主页面 inert，关闭还原并恢复焦点。
- “跑步记录／管理长期记忆／运动与语音设置”固定在菜单顶部，最近对话与任务独立滚动，不再挤走功能入口。
- 聊天外壳使用当前可视高度；顶栏和输入区不收缩，中间对话独立滚动。监听 VisualViewport 与窗口 resize，键盘缩小时调整容器，不再把整页 scrollIntoView。
- 退出聊天页清理高度监听并还原 body 滚动；保留跑步状态返回入口。不改 Android 数据库、BLE、GPS、ASR、Fish 或 Agent 提交协议。

## 验证

- 前端 Lint、格式、类型、237 项测试与生产构建通过，日志 `build/chat-layout-check.log`。
- 新增 `frontend/e2e/agent-layout.spec.ts`，只访问本地预览、使用合成 Bridge，拒绝真实网络及未允许命令。4 组浏览器回归连续两次通过：浅色／深色抽屉点击记录、长回答独立滚动、393×800／320×568／393×420／800×393 视口、运行中横条、大字体、背景不可截获点击及关闭恢复焦点。
- 浏览器截图已检查，位于忽略目录 `frontend/build/`；不含真实健康记录。新增测试文件另做 TypeScript 类型检查通过。
- 视口缩小是键盘布局的模拟，不代替真机键盘验收；未录音、开始跑步、播放语音或调用真实模型。

## 真机状态

- 原签名构建成功，日志 `build/chat-layout-phone-build.log`，构建过程再次通过 237 项前端测试。APK SHA256：`883EE8FA6DCB7687D745B198137D183DDE654140529DEDFA12F2A6919ED7530E`。
- 第一次安装被手机返回 `INSTALL_FAILED_ABORTED: User rejected permissions`，没有自动重试。用户明确同意解锁配合后，第二次保留数据覆盖安装返回 Success；正常启动 Status: ok，更新时间 2026-09-29 19:35:34。
- 安装包回读哈希一致。安装前无未结束运动；安装后两库完整性通过、表结构及原行全部字段逐一相同，跑步库 v3／Agent 库 v7 未变化。保留 4 条跑步、7295 个轨迹点、7 个分段及原有 Agent 数据。
- 安装前后两库与 APK 位于 `build/device-tests/chat-layout-release-1ed06c13280343528a213fb7d56501d4`，仅当前 Windows 用户可读且被 Git 忽略，不含手机凭据目录。未卸载、清数据、提交或推送 Git。
- 本轮本地 Vite 开发服务已停止，浏览器回归服务随测试退出；临时 ADB WebView 转发已移除。
- 用户已确认菜单可见、能找到跑步记录、键盘弹起后输入框正常；真实摘要问答仍待另行确认。
- 用户随后截图指出输入时内部绿色直角框多余。本次仅将 textarea 聚焦 outline 改为 none，保留外层圆角容器及所有布局；新增聚焦无 outline 断言，4 项浏览器回归通过，已检查缩小视口截图。
