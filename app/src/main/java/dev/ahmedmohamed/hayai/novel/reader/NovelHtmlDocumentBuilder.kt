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
                "<style>$readerCss</style><style>${style.customCss.safeStyleText()}</style>"
            } else {
                "<style>${style.customCss.safeStyleText()}</style><style>$readerCss</style>"
            }
        val heading = if (style.hideChapterTitle) "" else "<h1 class=\"hayai-chapter-title\">${escape(chapterTitle)}</h1>"
        val renderingMode = style.renderingMode.takeIf { it in setOf("default", "continuous", "paged") } ?: "default"
        return """
            <!doctype html>
            <html class="hayai-$renderingMode" data-keep-highlight="${style.keepTtsHighlightInView}"><head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=5,user-scalable=yes">
              $styleBlocks
            </head><body>
              <main id="hayai-reader">$heading${content.html}</main>
              <script>${BRIDGE_SCRIPT}</script>
              <script>${style.customJs.safeScriptText()}</script>
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
            html.hayai-paged, html.hayai-paged body { height:100%; overflow-y:hidden; }
            html.hayai-paged body { column-width:calc(100vw - ${style.marginLeft.coerceIn(0, 200) + style.marginRight.coerceIn(0, 200)}px); column-gap:32px; overflow-x:auto; }
            *, *::before, *::after { box-sizing:border-box; max-width:100%; }
            p { margin:${style.paragraphSpacing.coerceIn(0f, 5f)}em 0; text-indent:${style.paragraphIndent.coerceIn(0f, 10f)}em; }
            img, image, svg, video { height:auto; max-width:100%; }
            pre, code { white-space:pre-wrap; word-break:break-word; text-indent:0; }
            table { display:block; overflow-x:auto; border-collapse:collapse; }
            a { color:${color(style.linkColor)}; }
            .hayai-chapter-title { text-indent:0; line-height:1.25; margin:0 0 1em; font-size:1.5em; }
            ${ttsHighlightCss(style.ttsHighlightStyle, highlightColor, highlightTextColor)}
        """.trimIndent()
    }

    private fun ttsHighlightCss(
        style: String,
        background: String,
        foreground: String,
    ): String =
        when (style) {
            "underline" -> ".hayai-tts-active { text-decoration:underline 3px $background; text-underline-offset:.18em; }"
            "text" -> ".hayai-tts-active { color:$background; }"
            else -> ".hayai-tts-active { background:$background; color:$foreground; border-radius:.2em; }"
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

    private fun String.safeStyleText(): String = replace("</style", "<\\/style", ignoreCase = true)

    private fun String.safeScriptText(): String = replace("</script", "<\\/script", ignoreCase = true)

    private fun isDark(color: Int): Boolean =
        (color ushr 16 and 0xFF) * 0.299 +
            (color ushr 8 and 0xFF) * 0.587 +
            (color and 0xFF) * 0.114 < 128

    private const val BRIDGE_SCRIPT = """
        (() => {
          const paged = () => document.documentElement.classList.contains('hayai-paged');
          const progress = () => {
            const max = paged()
              ? Math.max(1, document.documentElement.scrollWidth - innerWidth)
              : Math.max(1, document.documentElement.scrollHeight - innerHeight);
            const current = paged() ? scrollX : scrollY;
            return Math.max(0, Math.min(100, Math.round(current * 100 / max)));
          };
          let scheduled = false;
          let lastSelection = '';
          document.addEventListener('selectionchange', () => {
            const selected = (getSelection() ? getSelection().toString() : '').trim();
            if (selected) lastSelection = selected;
          });
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
              const ratio = Math.max(0, Math.min(100, value)) / 100;
              if (paged()) {
                const max = Math.max(0, document.documentElement.scrollWidth - innerWidth);
                scrollTo({left:max * ratio, top:0, behavior:'auto'});
              } else {
                const max = Math.max(0, document.documentElement.scrollHeight - innerHeight);
                scrollTo({top:max * ratio, left:0, behavior:'auto'});
              }
            },
            step(direction, fraction = .85) {
              if (paged()) scrollBy({left:innerWidth * fraction * direction, behavior:'smooth'});
              else scrollBy({top:innerHeight * fraction * direction, behavior:'smooth'});
            },
            stepPixels(pixels) {
              if (paged()) scrollBy({left:pixels, behavior:'auto'});
              else scrollBy({top:pixels, behavior:'auto'});
            },
            paragraphs() {
              return [...document.querySelectorAll('h1,h2,h3,h4,h5,h6,p,li,blockquote')]
                .map((node, index) => ({index, text:(node.innerText || '').trim()})).filter(it => it.text);
            },
            documentText() { const root=document.querySelector('#hayai-reader'); return root ? root.innerText : ''; },
            showTranslation(text) { const root=document.querySelector('#hayai-reader'); if(!root)return; if(!root.dataset.originalHtml)root.dataset.originalHtml=root.innerHTML; root.replaceChildren(...text.split(/\n{2,}/).filter(Boolean).map(value=>{const p=document.createElement('p');p.textContent=value;return p;})); },
            showOriginal() { const root=document.querySelector('#hayai-reader'); if(root&&root.dataset.originalHtml){root.innerHTML=root.dataset.originalHtml;delete root.dataset.originalHtml;} },
            takeSelection() {
              const selected = (getSelection() ? getSelection().toString() : '').trim() || lastSelection;
              lastSelection = '';
              return selected;
            },
            takeSelectionAnchor() {
              const selection = getSelection();
              if (!selection || selection.rangeCount === 0 || selection.isCollapsed) return null;
              const range = selection.getRangeAt(0);
              const main = document.querySelector('#hayai-reader');
              if (!main || !main.contains(range.commonAncestorContainer)) return null;
              const before = document.createRange(); before.selectNodeContents(main); before.setEnd(range.startContainer, range.startOffset);
              const after = document.createRange(); after.selectNodeContents(main); after.setStart(range.endContainer, range.endOffset);
              const exact = range.toString().replace(/\s+/g,' ').trim();
              const prefix = before.toString().replace(/\s+/g,' ').trim();
              const suffix = after.toString().replace(/\s+/g,' ').trim();
              const prior = exact ? prefix.split(exact).length - 1 : 0;
              return {documentText:main.innerText, selectedText:exact, prefix:prefix.slice(-64), suffix:suffix.slice(0,64), occurrence:prior};
            },
            applyPersistentHighlights(items) {
              document.querySelectorAll('mark.hayai-persistent-highlight').forEach(mark => mark.replaceWith(...mark.childNodes));
              const root = document.querySelector('#hayai-reader'); if (!root) return;
              root.normalize(); const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
              const nodes=[]; let node; while ((node=walker.nextNode())) nodes.push(node);
              let text='', map=[], whitespace=false;
              for (const textNode of nodes) for(let offset=0;offset<textNode.nodeValue.length;offset++) {
                const ch=textNode.nodeValue[offset];
                if (/\s/.test(ch)) { if(!whitespace && text.length){text+=' ';map.push({node:textNode,offset});} whitespace=true; }
                else { text+=ch;map.push({node:textNode,offset});whitespace=false; }
              }
              text=text.trim(); const occupied=[],planned=[]; let applied=0, orphaned=0, overlaps=0;
              for (const item of items) {
                let matches=[],from=0,index;
                while(matches.length<256 && (index=text.indexOf(item.exact,from))>=0){matches.push(index);from=index+1;}
                if(!matches.length && item.exact.length>=12){
                  const seed=item.exact.slice(0,Math.min(16,Math.floor(item.exact.length/3)));from=0;
                  while(matches.length<128 && (index=text.indexOf(seed,from))>=0){const suffixSeed=item.suffix.slice(0,16);const suffixStart=suffixSeed?text.indexOf(suffixSeed,index+seed.length):-1;const end=suffixStart>=0?suffixStart:Math.min(text.length,index+Math.floor(item.exact.length*1.25));const a=new Set(item.exact.toLowerCase().split(/[^\p{L}\p{N}]+/u).filter(Boolean));const b=new Set(text.slice(index,end).toLowerCase().split(/[^\p{L}\p{N}]+/u).filter(Boolean));let common=0;a.forEach(word=>{if(b.has(word))common++;});if(a.size&&b.size&&2*common/(a.size+b.size)>=.84)matches.push(index);from=index+1;}
                }
                if(!matches.length){orphaned++;continue;}
                const scored=matches.map((start,ordinal)=>{const before=text.slice(Math.max(0,start-item.prefix.length),start);const after=text.slice(start+item.exact.length,start+item.exact.length+item.suffix.length);let score=-Math.min(16,Math.abs(ordinal-item.occurrence));for(let i=1;i<=Math.min(before.length,item.prefix.length)&&before[before.length-i]===item.prefix[item.prefix.length-i];i++)score+=4;for(let i=0;i<Math.min(after.length,item.suffix.length)&&after[i]===item.suffix[i];i++)score+=4;return{start,score};}).sort((a,b)=>b.score-a.score);
                const start=scored[0].start;let end=start+item.exact.length;if(text.slice(start,end)!==item.exact){const suffixSeed=item.suffix.slice(0,16);const found=suffixSeed?text.indexOf(suffixSeed,start+1):-1;end=found>=0?found:Math.min(text.length,start+Math.floor(item.exact.length*1.25));}
                if(occupied.some(range=>start<range.end&&end>range.start)){overlaps++;continue;}
                const first=map[start],last=map[end-1]; if(!first||!last){orphaned++;continue;}
                occupied.push({start,end}); planned.push({item,start,end,first,last});
              }
              planned.sort((a,b)=>b.start-a.start).forEach(entry=>{try{const range=document.createRange();range.setStart(entry.first.node,entry.first.offset);range.setEnd(entry.last.node,entry.last.offset+1);const mark=document.createElement('mark');mark.className='hayai-persistent-highlight';mark.dataset.highlightId=entry.item.id;mark.style.backgroundColor=entry.item.color;mark.appendChild(range.extractContents());range.insertNode(mark);applied++;}catch(_){orphaned++;}});
              if(window.HayaiReader&&HayaiReader.onHighlightReport)HayaiReader.onHighlightReport(applied,orphaned,overlaps);
            },
            navigateHighlight(id) { const mark=[...document.querySelectorAll('mark.hayai-persistent-highlight')].find(node=>node.dataset.highlightId===id); if(mark){mark.scrollIntoView({block:'center',behavior:'smooth'});return true;}return false; },
            highlight(index) {
              document.querySelectorAll('.hayai-tts-active').forEach(it => it.classList.remove('hayai-tts-active'));
              const nodes = [...document.querySelectorAll('h1,h2,h3,h4,h5,h6,p,li,blockquote')];
              const node = nodes[index];
              if (node) {
                node.classList.add('hayai-tts-active');
                if (document.documentElement.dataset.keepHighlight === 'true') node.scrollIntoView({block:'center', inline:'center', behavior:'smooth'});
              }
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
    val renderingMode: String,
    val customCss: String,
    val customJs: String,
    val ttsHighlightColor: Int,
    val ttsHighlightTextColor: Int,
    val ttsHighlightStyle: String,
    val keepTtsHighlightInView: Boolean,
)
