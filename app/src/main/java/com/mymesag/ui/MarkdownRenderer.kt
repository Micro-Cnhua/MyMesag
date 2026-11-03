package com.mymesag.ui

/**
 * 轻量 Markdown → HTML 转换器。
 * 支持：标题、粗体、斜体、链接、无序列表、有序列表、行内代码、代码块、分隔线、段落。
 */
object MarkdownRenderer {

    fun markdownToHtml(md: String): String {
        val lines = md.lines()
        val out = StringBuilder()
        var inCodeBlock = false
        var inUl = false
        var inOl = false
        var codeContent = StringBuilder()

        for (line in lines) {
            // 代码块 ```
            if (line.trimStart().startsWith("```")) {
                if (inCodeBlock) {
                    out.append("<pre><code>").append(escapeHtml(codeContent.toString())).append("</code></pre>\n")
                    codeContent = StringBuilder()
                    inCodeBlock = false
                } else {
                    closeList(out, inUl, inOl)
                    inUl = false; inOl = false
                    inCodeBlock = true
                }
                continue
            }
            if (inCodeBlock) {
                if (codeContent.isNotEmpty()) codeContent.append('\n')
                codeContent.append(line)
                continue
            }

            val trimmed = line.trim()

            // 空行 → 关闭列表
            if (trimmed.isEmpty()) {
                closeList(out, inUl, inOl)
                inUl = false; inOl = false
                continue
            }

            // 分隔线 ---
            if (trimmed.matches(Regex("^-{3,}$")) || trimmed.matches(Regex("^\\*{3,}$"))) {
                closeList(out, inUl, inOl)
                inUl = false; inOl = false
                out.append("<hr>\n")
                continue
            }

            // 标题 # ## ###
            val headingMatch = Regex("^(#{1,6})\\s+(.+)$").find(trimmed)
            if (headingMatch != null) {
                closeList(out, inUl, inOl)
                inUl = false; inOl = false
                val level = headingMatch.groupValues[1].length
                val text = inlineFormat(headingMatch.groupValues[2])
                out.append("<h$level>$text</h$level>\n")
                continue
            }

            // 无序列表 - *
            if (trimmed.matches(Regex("^[-*]\\s+.+"))) {
                if (inOl) { out.append("</ol>\n"); inOl = false }
                if (!inUl) { out.append("<ul>\n"); inUl = true }
                val text = inlineFormat(trimmed.replaceFirst(Regex("^[-*]\\s+"), ""))
                out.append("<li>$text</li>\n")
                continue
            }

            // 有序列表 1.
            if (trimmed.matches(Regex("^\\d+\\.\\s+.+"))) {
                if (inUl) { out.append("</ul>\n"); inUl = false }
                if (!inOl) { out.append("<ol>\n"); inOl = true }
                val text = inlineFormat(trimmed.replaceFirst(Regex("^\\d+\\.\\s+"), ""))
                out.append("<li>$text</li>\n")
                continue
            }

            // 普通段落
            closeList(out, inUl, inOl)
            inUl = false; inOl = false
            out.append("<p>").append(inlineFormat(trimmed)).append("</p>\n")
        }

        closeList(out, inUl, inOl)
        if (inCodeBlock && codeContent.isNotEmpty()) {
            out.append("<pre><code>").append(escapeHtml(codeContent.toString())).append("</code></pre>\n")
        }
        return out.toString()
    }

    private fun closeList(out: StringBuilder, inUl: Boolean, inOl: Boolean) {
        if (inUl) out.append("</ul>\n")
        if (inOl) out.append("</ol>\n")
    }

    /** 行内格式：粗体、斜体、行内代码、链接 */
    private fun inlineFormat(text: String): String {
        var result = escapeHtml(text)
        // 行内代码 `code`
        result = Regex("`([^`]+)`").replace(result) { "<code>${it.groupValues[1]}</code>" }
        // 粗体 **text** 或 __text__
        result = Regex("\\*\\*(.+?)\\*\\*").replace(result) { "<strong>${it.groupValues[1]}</strong>" }
        result = Regex("__(.+?)__").replace(result) { "<strong>${it.groupValues[1]}</strong>" }
        // 斜体 *text* 或 _text_
        result = Regex("\\*(.+?)\\*").replace(result) { "<em>${it.groupValues[1]}</em>" }
        result = Regex("_(.+?)_").replace(result) { "<em>${it.groupValues[1]}</em>" }
        // 链接 [text](url)
        result = Regex("\\[([^]]+)]\\(([^)]+)\\)").replace(result) {
            "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>"
        }
        return result
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    fun wrapHtml(body: String): String = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
  :root {
    --bg: #FAFAFA;
    --text: #1C1B1F;
    --text-secondary: #49454F;
    --primary: #2196F3;
    --card-bg: #FFFFFF;
    --code-bg: #F0F0F0;
    --border: #E0E0E0;
  }
  @media (prefers-color-scheme: dark) {
    :root {
      --bg: #1C1B1F;
      --text: #E6E1E5;
      --text-secondary: #CAC4D0;
      --primary: #90CAF9;
      --card-bg: #2B2930;
      --code-bg: #36343B;
      --border: #49454F;
    }
  }
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body {
    font-family: -apple-system, 'HarmonyOS Sans', 'Noto Sans SC', sans-serif;
    background: var(--bg);
    color: var(--text);
    padding: 16px;
    line-height: 1.7;
    font-size: 15px;
  }
  h1 { font-size: 1.6em; margin: 24px 0 12px; color: var(--primary); }
  h2 { font-size: 1.3em; margin: 20px 0 10px; }
  h3 { font-size: 1.1em; margin: 16px 0 8px; }
  p { margin: 8px 0; color: var(--text-secondary); }
  ul, ol { margin: 8px 0 8px 24px; color: var(--text-secondary); }
  li { margin: 4px 0; }
  a { color: var(--primary); text-decoration: none; }
  hr { border: none; border-top: 1px solid var(--border); margin: 16px 0; }
  pre {
    background: var(--code-bg);
    border-radius: 8px;
    padding: 12px;
    overflow-x: auto;
    margin: 8px 0;
    font-size: 13px;
  }
  code {
    background: var(--code-bg);
    border-radius: 4px;
    padding: 2px 6px;
    font-size: 0.9em;
  }
  pre code { background: none; padding: 0; }
  strong { color: var(--text); font-weight: 600; }
</style>
</head>
<body>
$body
</body>
</html>
""".trimIndent()
}
