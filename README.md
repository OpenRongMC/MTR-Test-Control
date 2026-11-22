# MTR 测试控制

简体中文 · [English](./README.en.md)

供 RongMC 测试世界使用的 Fabric 辅助工具，支持自动化检查和本地游戏控制，便于复现交通与客户端问题。

## 下载与安装

- 版本 · [v1.0.0+26.3](https://github.com/OpenRongMC/MTR-Test-Control/releases/tag/v1.0.0%2B26.3) (已发布)
- 下载文件 · `mtr-test-control-1.0.0.26.3.jar`
- 运行环境 · Minecraft Java 26.3, Fabric, Java 25
- Fabric Loader · `>=0.19.3`
- Fabric 模组 ID · `mtr_test_control`
- JAR 声明的依赖 · [Fabric API](https://modrinth.com/mod/fabric-api)

把发行 JAR 放入对应实例的 `mods/` 目录。只在隔离测试实例使用。保持控制接口仅本机可达，命令按权限要求执行。安装前先读原有命令和控制说明。

## 如何验证

在可丢弃的测试存档运行一条无副作用的状态检查，再执行所需测试。提交错误报告时去掉任何控制访问凭据。

## 从源码开始

```sh
./gradlew tasks
```

先用 Java 25 检查当前源码树提供的任务，再选择对应的构建任务。

## 文档与反馈

- [原 README（旧版指南、贡献者与署名）](https://github.com/OpenRongMC/MTR-Test-Control/blob/addf1a7bff7f948cc0150a3cbdee4f64737bba07/README.md)
- [报告 RongMC 移植问题](https://github.com/OpenRongMC/MTR-Test-Control/issues) · 请附游戏版本、两端 JAR 文件名、复现步骤和相关日志。

## 许可与署名

请参阅本仓库的 [LICENSE](./LICENSE) 及原项目的许可、署名和第三方资源说明。
