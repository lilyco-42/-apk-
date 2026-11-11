# 跑都不跑

当前版本：**v2.4.2**（versionCode 20402）

独立包名：`com.caixukun.paodoubp`

绘制路线、沿路线模拟定位、检查更新。

源码与发行页：https://github.com/lilyco-42/-apk-

在原项目基础上加了账号与积分（路线社区、畅跑卡、站内充值、邀请码推荐分成），
服务端在 lain42.top，与上游不是同一套签名，**请勿与上游 APK 混装**。

## 检查更新怎么工作

App 会请求：

https://api.github.com/repos/lilyco-42/-apk-/releases/latest

把 GitHub Release 的 `tag_name` 和安装包里的 `versionName` 比较：

- tag 更高 → 弹出更新，点下载打开该 Release 里的 `.apk`
- 没有 apk 附件 → 打开 https://github.com/lilyco-42/-apk-/releases
- tag 相同或更低 → 提示已是最新

所以 **GitHub Release 的 tag 必须和 `app/build.gradle` 里的 versionName 对齐**。

| 操作 | 要改的 |
|---|---|
| 发当前这一版 | 推 tag `v2.4.2`，CI 自动构建签名包并发布到这个 Release |
| 以后发新版 | 先递增 versionName、versionCode 和根目录 `update.json` 的 version，再打同名 tag |

Release 上的资产名固定为 `PaDouBuPao_arm64-v8a_release.apk`（ASCII，便于
`releases/latest/download/...` 直接下载），由 CI 的 Normalize APK name 步骤从
中文产物名复制而来。

## 出站 User-Agent

App 所有网络请求都走 `config/HttpUserAgent` 拦截器，统一发送
`PaDouBuPao/<versionName>`。**不要**让请求落到 OkHttp 默认的 `okhttp/4.12.0`：
lain42.top 的反代用 `block_script_bots` 拦截 `okhttp/`、`curl/`、`python-requests/`
等 UA，命中会回 403 纯文本，客户端就只会显示「服务器返回数据解析失败」。

## 打开工程

Android Studio 打开本目录，不要只打开 `app`。

`local.properties` 不要提交。里面填 SDK 路径和百度地图 AK。

包名：`com.caixukun.paodoubp`（和原项目独立，签名证书在本地 `keystore/`，不要提交）

## 运行前

1. 开发者选项 → 模拟位置应用 → 跑都不跑
2. 授予定位权限
3. 先绘制并保存路线，再去路线模拟
