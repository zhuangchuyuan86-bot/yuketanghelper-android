**来源与依赖说明**

接口行为分析参考 Yuketang-Helper-Web v7.1.0：
<https://github.com/dvdsanyi/Yuketang-Helper-Web/tree/v7.1.0>

上游 README 同时标注其基于 RainClassroomAssitant：
<https://github.com/TrickyDeath/RainClassroomAssitant>

本项目包含新编写的 Kotlin 代码，未附带桌面程序、Python 源码或上游网页资源。本次查阅到的 v7.1.0 仓库根目录未列出 LICENSE 文件；此说明不推定上游授予任何额外分发权利。

运行依赖：

| 依赖 | 版本 | 许可证 |
| --- | --- | --- |
| Kotlin 标准库 | 2.1.20 | Apache License 2.0 |
| kotlinx.coroutines | 1.10.1 | Apache License 2.0 |
| kotlinx.serialization | 1.8.0 | Apache License 2.0 |
| OkHttp | 4.12.0 | Apache License 2.0 |
| Okio（传递依赖） | 由 OkHttp 引入 | Apache License 2.0 |

本地测试另外使用 JUnit 4.13.2（Eclipse Public License 1.0）及 MockWebServer 4.12.0（Apache License 2.0）。构建使用 Gradle 8.11.1 和 Android Gradle Plugin 8.9.2；它们不随 App 运行。

DeepSeek 适配通过 OkHttp 调用官方 REST API，未引入额外 SDK 或本地 OCR 模型。接口和图片输入格式参考 2026-10-16 查阅的官方文档：

- <https://api-docs.deepseek.com/zh-cn/>
- <https://api-docs.deepseek.com/zh-cn/guides/vision>
- <https://api-docs.deepseek.com/zh-cn/api/create-chat-completion>

AI 服务需使用各服务商有效的 API Key；模型可用性和用量计费由服务商决定。

0.2.14 抢答协议依据官方公开学生端 JavaScript 核实，来源、消息字段及接口见 [自动抢答说明](docs/QUICK_ANSWER.md)。仅参考接口行为，未分发这些网页脚本、图片或音视频资源。


Gradle Wrapper 的声明文件见 [licenses/gradle-LICENSE.txt](licenses/gradle-LICENSE.txt) 和 [licenses/gradle-NOTICE.txt](licenses/gradle-NOTICE.txt)。项目自有源码的授权状态见 [LICENSE_STATUS.md](LICENSE_STATUS.md)。
