# 备用：ChatGPT WebView Session Cookie 方案

`ChatgptSessionLoginScreen.kt` 保存了 0.5.5 的旧实现：在 WebView 内请求
`https://chatgpt.com/api/auth/session`，再通过 JavascriptInterface 把完整 JSON 交回原生层。

真机反馈该路径会被 Cloudflare 拦截，或取得的会话不被后端接受，因此主路径已改为
OpenAI OAuth + PKCE。本目录不在 Android source set 中，**不参与编译**。

现有「手贴 Session JSON」兼容逻辑仍在 `ChatgptPlusProvider.fetchLegacySession()`，不受此备份影响。
