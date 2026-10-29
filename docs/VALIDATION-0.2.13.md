# 0.2.13 验证记录

日期：2026-10-28。版本名 0.2.13，versionCode 17，Android 8.0+，target SDK 35。

## 本地结果

| 检查 | 结果 |
| --- | --- |
| 核心测试 | 186 项，0 失败、0 错误、0 跳过；本次新增 19 项登录回归测试 |
| Android 调试构建 | 成功 |
| Android Lint | 0 问题 |
| APK 元数据 | cn.yukehelper.android.prototype / 0.2.13 / 17 / min API 26 / target API 35 |
| 签名 | v2 有效，与原交付 0.2.12 和 0.2.10 界面调整包一致 |
| 配置与业务范围 | 加密存储、配置结构、AI 核心、通用设置页、课堂监测与作业管理文件逐字节对照基线保持一致 |
| 源码包 | 排除构建缓存、本机路径、账号数据及签名私钥，验证内部链接、ZIP CRC 和逐文件内容 |

构建：`:core:test :app:assembleDebug :app:lintDebug --offline`，JDK 17 / Gradle 8.11.1 / SDK 35。本次曾出现一条文字拼接 Lint 提示，已改用字符串资源并重新检查，最终无问题。

## 新增回归范围

- 可控时钟下等待 120 秒后收到确认，原图与同一次会话仍保留，先保存再报告成功。
- 模拟页面观察者离开和重新加入不会取消后台会话；同站点重新观察不重复创建连接。
- 主动刷新关闭上一连接，旧通知取消动作不会取消新尝试；取消验证不会处理后续结果。
- 5 分钟等待到期关闭原连接并清除原图；切站点丢弃旧尝试；存储失败不报告成功或泄漏异常原文。
- 模拟 WebSocket 跨越两个旧刷新周期后，只发送一次 requestlogin。重复确认只交换一次会话。
- 服务器在确认后关闭 WebSocket 仍可完成独立账号查询。会话缺失、明确拒绝、空 Auth 均停止并输出安全阶段提示。
- 账号查询临时 503 可重试，最多 3 次且始终使用同一个 session；一次性 Auth POST 不重试。

这些是本机 JVM 测试及 MockWebServer 测试，不是小米设备自动化测试。未在手机或 Android 模拟器运行本版，也未使用真实账号进行本版扫码；前台登录通知、ROM 后台策略、旋转恢复、进程被杀、微信扫码和覆盖安装仍需实际设备确认。

应用等待上限不能延长服务器二维码有效期；服务器可提前使二维码失效。系统强制停止后不能恢复原 WebSocket，需重新扫码。详见 [登录说明](LOGIN.md)。0.2.12 作业功能的用户成功反馈保留在 [历史验证](VALIDATION-0.2.12.md)，不视为本版登录的实测结果。

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
| YuketangApiTest | 35 | 0 |

APK SHA-256：`f662ffb7b5e664c6548b52926b5f5bf1654356ee160384e9221897b560b935a5`

签名证书 SHA-256：`fc09a0329ad82a27175761a136029c4d70cba156ced3facaafa12141ae6eeef4`
