> 本次使用版本名 0.2.15、versionCode 19，标签 `v0.2.15`。发布说明见 [记录](releases/v0.2.15.md)。后续升级版本的 versionCode 需大于 19。

# 发布到 GitHub Releases

仓库源码放在 Git，APK、源码压缩包及校验文件放在 Releases 附件中。不要把 SDK、构建缓存、账号数据或签名文件提交到仓库。

1. 确认版本号、变更记录、已知限制及本次实际验证结果。
2. 使用固定且妥善保存的签名构建安装包，执行核心测试、编译及 Lint。确认覆盖安装和目标设备表现；尚未验证的内容写入 Release。
3. 计算 APK 的 SHA-256，创建与版本一致的 Git 标签。
4. 在仓库 **Releases → Draft a new release** 选择标签。当前版本可使用 `v0.2.15` 标签，并复制 [Release 文案](releases/v0.2.15.md)。
5. 上传 APK 和校验文件。当前包为 debug 原型，发布时建议标为 **Pre-release**。

PowerShell 计算校验值：

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath ./YuketangHelper-Android-0.2.15-debug.apk
```

GitHub 自动生成的 Source code ZIP/TAR 来自标签所指的仓库内容，不包含维护者签名私钥。Actions 上传的 APK 是调试产物，未配置自动 Release，也不保证与既有 APK 签名相同。不要把临时 CI 调试包描述为可覆盖所有旧版的正式更新。

项目自有源码许可证尚未指定，发布为公开仓库前应明确所需授权；当前文件没有代替维护者选定许可证。
