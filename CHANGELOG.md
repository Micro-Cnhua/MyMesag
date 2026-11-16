# 更新日志

本文件记录每个版本的更新内容。发布新版本时，GitHub Actions 会自动提取对应 tag（例如 `## v0.2.1`）下方的小节作为 Release 的更新说明。

格式约定：

-   每个版本用二级标题 `## vX.Y.Z`，标题必须与 git tag 完全一致。
-   标题下方用列表写更新点， newest first。
-   未匹配到小节时，Release 仅显示自动生成的提交记录。

## v0.3.1

<div><ul style="font-style: normal; font-variant-ligatures: normal; font-variant-caps: normal; font-weight: 400; font-size: 17px; font-family: MiSans, -apple-system, BlinkMacSystemFont, &quot;Segoe UI Variable&quot;, &quot;Segoe UI&quot;, &quot;Microsoft YaHei UI&quot;, &quot;Microsoft YaHei&quot;, sans-serif; border-color: rgb(228, 228, 231); --ring-inset: ; --ring-offset-width: 0px; --ring-offset-color: #fff; --ring-color: rgba(66, 153, 225, 0.6); --ring-offset-shadow: 0 0 #0000; --ring-shadow: 0 0 #0000; --brightness: ; --contrast: ; --grayscale: ; --hue-rotate: ; --invert: ; --saturate: ; --sepia: ; --drop-shadow: ; --backdrop-blur: ; --backdrop-brightness: ; --backdrop-contrast: ; --backdrop-grayscale: ; --backdrop-hue-rotate: ; --backdrop-invert: ; --backdrop-opacity: ; --backdrop-saturate: ; --backdrop-sepia: ; --global-font-mono: &quot;Cascadia Code&quot;,&quot;Cascadia Mono&quot;,&quot;JetBrains Mono&quot;,Consolas,&quot;Courier New&quot;,monospace; --global-font-body: &quot;MiSans&quot;,&quot;MiSans VF&quot;,-apple-system,BlinkMacSystemFont,&quot;Segoe UI Variable&quot;,&quot;Segoe UI&quot;,&quot;Microsoft YaHei UI&quot;,&quot;Microsoft YaHei&quot;,sans-serif; --global-color-border: #e4e4e7;"><li dir="auto" style="border-color: rgb(228, 228, 231); --ring-inset: ; --ring-offset-width: 0px; --ring-offset-color: #fff; --ring-color: rgba(66, 153, 225, 0.6); --ring-offset-shadow: 0 0 #0000; --ring-shadow: 0 0 #0000; --brightness: ; --contrast: ; --grayscale: ; --hue-rotate: ; --invert: ; --saturate: ; --sepia: ; --drop-shadow: ; --backdrop-blur: ; --backdrop-brightness: ; --backdrop-contrast: ; --backdrop-grayscale: ; --backdrop-hue-rotate: ; --backdrop-invert: ; --backdrop-opacity: ; --backdrop-saturate: ; --backdrop-sepia: ; --global-font-mono: &quot;Cascadia Code&quot;,&quot;Cascadia Mono&quot;,&quot;JetBrains Mono&quot;,Consolas,&quot;Courier New&quot;,monospace; --global-font-body: &quot;MiSans&quot;,&quot;MiSans VF&quot;,-apple-system,BlinkMacSystemFont,&quot;Segoe UI Variable&quot;,&quot;Segoe UI&quot;,&quot;Microsoft YaHei UI&quot;,&quot;Microsoft YaHei&quot;,sans-serif; --global-color-border: #e4e4e7;"><p dir="auto" style="border-color: rgb(228, 228, 231); --ring-inset: ; --ring-offset-width: 0px; --ring-offset-color: #fff; --ring-color: rgba(66, 153, 225, 0.6); --ring-offset-shadow: 0 0 #0000; --ring-shadow: 0 0 #0000; --brightness: ; --contrast: ; --grayscale: ; --hue-rotate: ; --invert: ; --saturate: ; --sepia: ; --drop-shadow: ; --backdrop-blur: ; --backdrop-brightness: ; --backdrop-contrast: ; --backdrop-grayscale: ; --backdrop-hue-rotate: ; --backdrop-invert: ; --backdrop-opacity: ; --backdrop-saturate: ; --backdrop-sepia: ; --global-font-mono: &quot;Cascadia Code&quot;,&quot;Cascadia Mono&quot;,&quot;JetBrains Mono&quot;,Consolas,&quot;Courier New&quot;,monospace; --global-font-body: &quot;MiSans&quot;,&quot;MiSans VF&quot;,-apple-system,BlinkMacSystemFont,&quot;Segoe UI Variable&quot;,&quot;Segoe UI&quot;,&quot;Microsoft YaHei UI&quot;,&quot;Microsoft YaHei&quot;,sans-serif; --global-color-border: #e4e4e7;">1.</p></li><li dir="auto" style="border-color: rgb(228, 228, 231); --ring-inset: ; --ring-offset-width: 0px; --ring-offset-color: #fff; --ring-color: rgba(66, 153, 225, 0.6); --ring-offset-shadow: 0 0 #0000; --ring-shadow: 0 0 #0000; --brightness: ; --contrast: ; --grayscale: ; --hue-rotate: ; --invert: ; --saturate: ; --sepia: ; --drop-shadow: ; --backdrop-blur: ; --backdrop-brightness: ; --backdrop-contrast: ; --backdrop-grayscale: ; --backdrop-hue-rotate: ; --backdrop-invert: ; --backdrop-opacity: ; --backdrop-saturate: ; --backdrop-sepia: ; --global-font-mono: &quot;Cascadia Code&quot;,&quot;Cascadia Mono&quot;,&quot;JetBrains Mono&quot;,Consolas,&quot;Courier New&quot;,monospace; --global-font-body: &quot;MiSans&quot;,&quot;MiSans VF&quot;,-apple-system,BlinkMacSystemFont,&quot;Segoe UI Variable&quot;,&quot;Segoe UI&quot;,&quot;Microsoft YaHei UI&quot;,&quot;Microsoft YaHei&quot;,sans-serif; --global-color-border: #e4e4e7;">2.</li></ul></div>

## v0.3.0

-   1.修复了设备在断网重连后因时间断网事件超出检测周期而停止重连MQTT服务器导致的信息同步不及时
    
-   2.针对上述的断网无法同步情况，增加了重连后接收端自动重新同步发送端的未读消息功能

## v0.2.7

-   1.增加了远程回复短信成功后自动标记已回复短信为已读状态

## v0.2.6

-   1.修复了远程回复短信出现软件userid不匹配导致的发送失败问题

## v0.2.5

-   1.增加通知应用图标

## v0.2.4

-   1.增加省电功能选择
-   2.修复信息存储清除

## v0.2.3

-   1.修改用户自定义字段为不超过8位
-   2.修复聊天软件同步信息群组组合错误和计数器重置失败的Bug
-   3.更改部分页面布局