package dev.ahmedmohamed.hayai.novel.reader

internal object NovelHtmlDocumentBuilder {
    fun build(
        content: ProcessedNovelContent,
        chapterTitle: String,
        style: NovelReaderStyle,
    ): String {
        val readerCss = buildReaderCss(style)
        val styleBlocks =
            if (style.sourceCssPriority) {
                "<style>$readerCss</style><style>${style.customCss}</style>"
            } else {
                "<style>${style.customCss}</style><style>$readerCss</style>"
            }
        val heading = if (style.hideChapterTitle) "" else "<h1 class=\"hayai-chapter-title\">${escape(chapterTitle)}</h1>"
        return """
            <!doctype html>
            <html><head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=5,user-scalable=yes">
              $styleBlocks
            </head><body>
              <main id="hayai-reader">$heading${content.html}</main>
              <script>${BRIDGE_SCRIPT}</script>
              <script>${style.customJs}</script>
            </body></html>
            """.trimIndent()
    }

    private fun buildReaderCss(style: NovelReaderStyle): String {
        val fontFamily = if (style.useOriginalFonts) "inherit" else cssString(style.fontFamily)
        val align = style.textAlign.takeIf { it in setOf("left", "right", "center", "justify", "start", "end") } ?: "left"
        val selection = if (style.textSelectable) "text" else "none"
        val backgroundColor = color(style.backgroundColor)
        val textColor = color(style.textColor)
        val highlightColor = color(style.ttsHighlightColor)
        val highlightTextColor = color(style.ttsHighlightTextColor)
        return """
            :root { color-scheme: ${if (isDark(style.backgroundColor)) "dark" else "light"}; }
            html, body { margin:0; padding:0; min-height:100%; background:$backgroundColor; color:$textColor; }
            body {
              box-sizing:border-box;
              padding:${style.marginTop.coerceIn(0, 200)}px ${style.marginRight.coerceIn(0, 200)}px
                ${style.marginBottom.coerceIn(0, 200)}px ${style.marginLeft.coerceIn(0, 200)}px;
              font-family:$fontFamily; font-size:${style.fontSize.coerceIn(8, 72)}px;
              line-height:${style.lineHeight.coerceIn(0.8f, 3f)}; text-align:$align;
              overflow-wrap:anywhere; -webkit-user-select:$selection; user-select:$selection;
            }
            *, *::before, *::after { box-sizing:border-box; max-width:100%; }
            p { margin:${style.paragraphSpacing.coerceIn(0f, 5f)}em 0; text-indent:${style.paragraphIndent.coerceIn(0f, 10f)}em; }
            img, image, svg, video { height:auto; max-width:100%; }
            pre, code { white-space:pre-wrap; word-break:break-word; text-indent:0; }
            table { display:block; overflow-x:auto; border-collapse:collapse; }
            a { color:${color(style.linkColor)}; }
            .hayai-chapter-title { text-indent:0; line-height:1.25; margin:0 0 1em; font-size:1.5em; }
            .hayai-tts-active { background:$highlightColor; color:$highlightTextColor; border-radius:.2em; }
        """.trimIndent()
    }

    internal fun color(value: Int): String =
        "#%02X%02X%02X%02X".format(
            value ushr 16 and 0xFF,
            value ushr 8 and 0xFF,
            value and 0xFF,
            value ushr 24 and 0xFF,
        )

    private fun cssString(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private fun escape(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private fun isDark(color: Int): Boolean =
        (color ushr 16 and 0xFF) * 0.299 +
            (color ushr 8 and 0xFF) * 0.587 +
            (color and 0xFF) * 0.114 < 128

    private const val BRIDGE_SCRIPT = """
        (() => {
          const progress = () => {
            const max = Math.max(1, document.documentElement.scrollHeight - innerHeight);
            return Math.max(0, Math.min(100, Math.round(scrollY * 100 / max)));
          };
          let scheduled = false;
          addEventListener('scroll', () => {
            if (scheduled) return;
            scheduled = true;
            requestAnimationFrame(() => {
              scheduled = false;
              if (window.HayaiReader) HayaiReader.onProgress(progress());
            });
          }, {passive:true});
          window.hayaiReader = {
            progress,
            scrollToPercent(value) {
              const max = Math.max(0, document.documentElement.scrollHeight - innerHeight);
              scrollTo({top:max * Math.max(0, Math.min(100, value)) / 100, behavior:'auto'});
            },
            paragraphs() {
              return [...document.querySelectorAll('h1,h2,h3,h4,h5,h6,p,li,blockquote')]
                .map((node, index) => ({index, text:(node.innerText || '').trim()})).filter(it => it.text);
            },
            highlight(index) {
              document.querySelectorAll('.hayai-tts-active').forEach(it => it.classList.remove('hayai-tts-active'));
              const nodes = [...document.querySelectorAll('h1,h2,h3,h4,h5,h6,p,li,blockquote')];
              const node = nodes[index];
              if (node) { node.classList.add('hayai-tts-active'); node.scrollIntoView({block:'center', behavior:'smooth'}); }
            },
            clearHighlight() { document.querySelectorAll('.hayai-tts-active').forEach(it => it.classList.remove('hayai-tts-active')); }
          };
          addEventListener('load', () => { if (window.HayaiReader) HayaiReader.onReady(progress()); });
        })();
    """
}

internal data class NovelReaderStyle(
    val fontSize: Int,
    val fontFamily: String,
    val lineHeight: Float,
    val textAlign: String,
    val textColor: Int,
    val backgroundColor: Int,
    val linkColor: Int,
    val paragraphIndent: Float,
    val paragraphSpacing: Float,
    val marginLeft: Int,
    val marginRight: Int,
    val marginTop: Int,
    val marginBottom: Int,
    val useOriginalFonts: Boolean,
    val textSelectable: Boolean,
    val hideChapterTitle: Boolean,
    val sourceCssPriority: Boolean,
    val customCss: String,
    val customJs: String,
    val ttsHighlightColor: Int,
    val ttsHighlightTextColor: Int,
)
