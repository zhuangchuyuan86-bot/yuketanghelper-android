# 0.2.14 验证记录

日期：2026-10-29。版本名 0.2.14、versionCode 18，Android 8.0+，target SDK 35。

| 检查 | 结果 |
| --- | --- |
| 核心测试 | 217 项，0 失败、0 错误、0 跳过；新增 31 项抢答回归测试 |
| Android 调试构建 | 成功 |
| Android Lint | 0 问题 |
| APK 元数据 | cn.yukehelper.android.prototype / 0.2.14 / 18 / min API 26 / target API 35 |
| 签名 | v2 有效，与此前 0.2.13、0.2.12 安装包同签名 |
| 保留功能 | 登录页、扫码控制器和登录服务、加密存储、作业管理、AI 核心、课程选择、监测引擎逐字节对照 0.2.13 基线保持一致；网络类中的扫码登录与课堂 WebSocket 段也逐字对照一致 |
| 源码包 | 排除构建缓存、本机路径、凭据、签名私钥与 APK；检查内部链接、ZIP CRC 和逐文件内容 |

构建：`:core:test :app:assembleDebug :app:lintDebug --offline`，JDK 17 / Gradle 8.11.1 / SDK 35。

## 新增验证

- 正确分发官方 `jumpinstart`、`jumpinend` 与 `hello.jumpinid`；保留轮次 ID 的数字/字符串类型及全部位数。
- 服务端相对倒计时；重复消息不重置计时；等待课件或 AI 时抢答仍能独立提交。
- 默认关闭、未选课程、运行时关闭开关不提交；提前结束、下一轮和停止监测会取消等待。
- 重连先查询当前轮次，使用更新后的课堂凭据；查询失败或结束时不提交。应用内提交记录防止设置重启导致重复操作，并按账号隔离。
- 成功、已结束、未抢到、超时、拒绝及异常响应分别处理，不将 HTTP 成功等同抢答成功，不展示任意异常原文。
- MockWebServer 检查 GET/POST 路径、原始 ID、会话 Cookie 与课堂 Authorization，以及 HTTP 错误、缺失状态、过期会话、307 跳转与 503 Retry-After。
- 真实本地 WebSocket 测试：多次相同抢答消息经过协议分发、自动化和 HTTP 后，仅产生一次参与请求，读取业务结果确认成功。

开发时发现关闭连接重试仍不能阻止特定 503 的自动重发；已将抢答参与请求体标为 one-shot，并保留该回归测试。其他题目、弹幕和红包请求保留原策略。

这些是本机 JVM、可控时钟和本地模拟服务测试，未使用真实雨课堂账号或真实 AI 密钥。本版没有在 Android 手机或模拟器运行；实际课堂是否抢到、后台通知、语音和覆盖安装仍需实机确认。沿用同签名只表示满足覆盖安装的签名条件，不代表已做手机覆盖安装测试。

规则及公开前端来源见 [自动抢答说明](QUICK_ANSWER.md)。去重记录随进程结束丢失，不能承诺跨进程不重复；网络与平台时序也不能保证抢到。

| 测试组 | 数量 | 失败 |
| --- | --- | --- |
| ActionApiTest | 11 | 0 |
| AiClientTest | 5 | 0 |
| AutomationTest | 26 | 0 |
| ConfigurationTest | 6 | 0 |
| CourseSelectionTest | 5 | 0 |
| DeepSeekTest | 9 | 0 |
| GlobalAiSettingsTest | 9 | 0 |
| GlobalCourseSettingsTest | 8 | 0 |
| HomeworkApiTest | 5 | 0 |
| HomeworkTest | 16 | 0 |
| MonitorEngineTest | 22 | 0 |
| ProtocolTest | 10 | 0 |
| QrLoginApiTest | 9 | 0 |
| QrLoginControllerTest | 10 | 0 |
| QuickAnswerApiTest | 10 | 0 |
| QuickAnswerTest | 21 | 0 |
| YuketangApiTest | 35 | 0 |

APK SHA-256：`e41d7ec39537089b719db97f8ee4ce6e70c7fd3596152e5fb34363c4837cdc6a`

签名证书 SHA-256：`fc09a0329ad82a27175761a136029c4d70cba156ced3facaafa12141ae6eeef4`
