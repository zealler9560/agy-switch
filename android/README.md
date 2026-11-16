# Agy Switch（Android 端）

桌面端 agy-switch（Tauri 版，管理多个 Antigravity / Google AI Pro 账号、查额度、一键切换 agy CLI 当前账号）的 Android 对应版。纯原生 Java 单文件实现，不引任何第三方库，UI 全中文。

包名：`io.zealler.agyswitch`，最低 Android 8.0（API 26）。

## 功能

- **模型明细**（v1.1）：每个账号可调 fetchAvailableModels 列出每个模型的剩余额度与重置时间；订阅档改走上游同款 loadCodeAssist 端点顺序与请求体

- 账号列表：每行显示邮箱、备注、订阅档、Gemini 周额度剩余 %、Claude/GPT 周额度剩余 %、5 小时额度、重置时间，以及「当前」标记
- 添加账号（三种方式，见下）
- 单个「刷新额度」/ 顶部「全部刷新」：refresh token 换 access token → loadCodeAssist 拿 project_id 和订阅档（失败不阻塞）→ retrieveUserQuotaSummary 查两个额度分组（Gemini、Claude and GPT models），结果缓存到本地
- 「设为当前 / 切换」：生成 agy CLI 凭据文件，有 root 直写 Termux，无 root 导出（见「切换原理」）
- 编辑备注、删除账号（带确认）
- 导出全部账号为桌面端兼容 JSON，可与桌面端互通

## 与桌面端的对应关系

| 能力 | 桌面端（Tauri） | Android 端 |
| --- | --- | --- |
| 账号存储 | 桌面端本地数据 | 应用私有文件 `accounts.json` |
| 查额度 | loadCodeAssist + retrieveUserQuotaSummary | 同样的官方接口、同样的请求格式 |
| 切换账号 | 写 `~/.gemini/antigravity-cli/antigravity-oauth-token` | 有 root 时直写 Termux 同路径；无 root 时导出文件手动放 |
| 导入 / 导出 | 桌面端导出 JSON（schema_version 1） | 完全兼容，可双向互通 |
| Google OAuth 网页登录加号 | 有 | v1 没有，靠 refresh token / 导入加号 |

## 三种添加账号的方式

点「添加账号」后三选一：

1. **粘贴 refresh token**：把已有账号的 refresh token 粘进输入框，App 用它换 access token，再拉 Google userinfo 补上邮箱后入库。同邮箱重复添加会更新原账号的 token。
2. **导入桌面端导出 JSON 文件**：选桌面端导出的文件（`{"schema_version":1,"accounts":[{"email","refresh_token","name","project_id","custom_label"}]}`），批量导入，逐个验证。
3. **导入 agy 的 antigravity-oauth-token 文件**：选 agy CLI 已登录生成的凭据文件，解析其中 `token.refresh_token` 入库。

## 切换原理

agy CLI 的当前账号由凭据文件决定：

```
~/.gemini/antigravity-cli/antigravity-oauth-token
```

内容是 JSON：`{"token":{"access_token","token_type":"Bearer","refresh_token","expiry"},"auth_method":"consumer","id_token":...}`（id_token 没有时省略）。

Android 端「设为当前」时：

- **有 root**：先执行 `su -c id` 检测，确认 uid=0 后用 su 把凭据直接写到 Termux 的路径 `/data/data/com.termux/files/home/.gemini/antigravity-cli/antigravity-oauth-token`（自动建目录、权限 600）。写完需要在 Termux 里**重开 agy** 才生效。
- **无 root**：弹出凭据内容预览，提供「复制到剪贴板」和「保存到文件」（系统文件选择器，文件名 antigravity-oauth-token），自己把文件放进 Termux 的 `~/.gemini/antigravity-cli/` 即可。

两种方式都会在 App 内把该账号标记为「当前」。

## 数据只存本地

- 账号、token、额度缓存只存应用私有目录的 `accounts.json`，不做任何网络上传。
- 网络请求只有 Google 官方接口：oauth2 token 刷新、userinfo、cloudcode-pa 的 loadCodeAssist 与 retrieveUserQuotaSummary。
- 卸载 App 即删掉全部本地数据。

## 构建

不用 Gradle，手动工具链脚本（依赖本机 `~/workspace/android-tools/env.sh` 提供的 SDK/JDK 环境）：

```sh
cd android
./build.sh
```

流程：aapt2 link（纯代码 UI，无 res）→ javac → d8 → zip 组装 → zipalign → apksigner（muse 测试签名）。产物：`android/dist/agy-switch-android.apk`，直接安装即可。

注意：源码里 OAuth 的 client_secret 是占位符 `__CLIENT_SECRET__`，构建前需由脚本从上游 `src-tauri/src/modules/oauth.rs` 提取真实值替换，否则刷新 token 会失败。

## v1 限制

- 没有 Google OAuth 网页登录流程，加号靠 refresh token 粘贴或文件导入。
- 桌面端的智能自动切换（额度耗尽自动换号）未移植。
- loadCodeAssist 的订阅档 / project_id 拿不到时不阻塞，额度查询会退回用 `aicode-consumers` 项目查。
