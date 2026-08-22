package dev.ahmedmohamed.hayai.novel.source.builder

import dev.ahmedmohamed.hayai.novel.plugin.NovelPluginDescriptor
import dev.ahmedmohamed.hayai.novel.plugin.sha256Hex
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import java.net.URI

@Serializable
data class NovelListSelectors(val item: String, val title: String, val link: String, val cover: String? = null, val next: String? = null)
@Serializable
data class NovelDetailsSelectors(val title: String, val author: String? = null, val description: String? = null, val cover: String? = null, val genres: String? = null, val status: String? = null)
@Serializable
data class NovelChapterSelectors(val item: String, val title: String, val link: String, val date: String? = null)
@Serializable
data class NovelContentSelectors(val body: String, val remove: List<String> = emptyList())

@Serializable
data class NovelCustomSourceDefinition(
    val id: String,
    val name: String,
    val language: String,
    val baseUrl: String,
    val popularPath: String,
    val searchPath: String,
    val list: NovelListSelectors,
    val details: NovelDetailsSelectors,
    val chapters: NovelChapterSelectors,
    val content: NovelContentSelectors,
    val headers: Map<String, String> = emptyMap(),
) {
    fun validate(): List<NovelSourceValidationIssue> = buildList {
        if (!ID.matches(id)) add(issue("id", NovelSourceValidationCode.InvalidId))
        if (name.isBlank() || name.length > 128) add(issue("name", NovelSourceValidationCode.InvalidName))
        if (!LANG.matches(language)) add(issue("language", NovelSourceValidationCode.InvalidLanguage))
        val uri = runCatching { URI(baseUrl) }.getOrNull()
        if (uri?.scheme !in setOf("https", "http") || uri?.host.isNullOrBlank() || uri?.userInfo != null) add(issue("baseUrl", NovelSourceValidationCode.InvalidBaseUrl))
        listOf("popularPath" to popularPath, "searchPath" to searchPath).forEach { (field, value) ->
            if (!value.startsWith('/') || value.length > 2_048) add(issue(field, NovelSourceValidationCode.InvalidPath))
        }
        if (!searchPath.contains("{query}")) add(issue("searchPath", NovelSourceValidationCode.MissingQueryPlaceholder))
        val selectors = mapOf("list.item" to list.item, "list.title" to list.title, "list.link" to list.link, "details.title" to details.title, "chapters.item" to chapters.item, "chapters.title" to chapters.title, "chapters.link" to chapters.link, "content.body" to content.body)
        selectors.forEach { (field, selector) -> if (!validSelector(selector)) add(issue(field, NovelSourceValidationCode.InvalidSelector)) }
        if (content.remove.size > 32 || content.remove.any { !validSelector(it) }) add(issue("content.remove", NovelSourceValidationCode.InvalidRemovalSelectors))
        if (headers.size > 32 || headers.any { (name, value) -> !validPortableHeader(name, value) }) {
            add(issue("headers", NovelSourceValidationCode.InvalidHeaders))
        }
    }

    fun requireValid(): NovelCustomSourceDefinition { val issues = validate(); require(issues.isEmpty()) { issues.joinToString("; ") { "${it.field}:${it.code}" } }; return this }

    companion object {
        private val ID = Regex("[A-Za-z0-9._-]{1,128}"); private val LANG = Regex("[a-z]{2,3}")
        private val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}")
        private val PORTABLE_HEADERS = setOf(
            "accept", "accept-language", "cache-control", "pragma", "user-agent", "dnt", "sec-gpc",
        )
        private fun issue(field: String, code: NovelSourceValidationCode) = NovelSourceValidationIssue(field, code)
        private fun validSelector(value: String): Boolean = value.isNotBlank() && value.length <= 512 && runCatching { Jsoup.parse("").select(value); true }.getOrDefault(false)
        private fun validPortableHeader(name: String, value: String): Boolean {
            val lowerName = name.lowercase()
            return HEADER_NAME.matches(name) &&
                lowerName in PORTABLE_HEADERS &&
                value.length <= 4_096 &&
                '\r' !in value &&
                '\n' !in value
        }
    }
}

enum class NovelSourceValidationCode {
    InvalidId,
    InvalidName,
    InvalidLanguage,
    InvalidBaseUrl,
    InvalidPath,
    MissingQueryPlaceholder,
    InvalidSelector,
    InvalidRemovalSelectors,
    InvalidHeaders,
    PreviewItemMissing,
    PreviewTitleMissing,
    PreviewLinkMissing,
}
data class NovelSourceValidationIssue(val field: String, val code: NovelSourceValidationCode)
data class NovelSourcePreview(val title: String?, val url: String?, val coverUrl: String?, val issues: List<NovelSourceValidationIssue>)

object NovelCustomSourcePreviewer {
    fun list(html: String, pageUrl: String, definition: NovelCustomSourceDefinition): NovelSourcePreview {
        val issues = definition.validate(); if (issues.isNotEmpty()) return NovelSourcePreview(null, null, null, issues)
        require(html.length <= 4_000_000)
        val document = Jsoup.parse(html, pageUrl); val item = document.selectFirst(definition.list.item)
            ?: return NovelSourcePreview(null, null, null, listOf(NovelSourceValidationIssue("list.item", NovelSourceValidationCode.PreviewItemMissing)))
        val title = item.selectFirst(definition.list.title)?.text()?.trim()
        val link = item.selectFirst(definition.list.link)?.absUrl("href")?.ifBlank { null }
        val cover = definition.list.cover?.let { item.selectFirst(it)?.let { node -> node.absUrl("src").ifBlank { node.absUrl("data-src") }.ifBlank { null } } }
        val foundIssues = buildList { if (title.isNullOrBlank()) add(NovelSourceValidationIssue("list.title", NovelSourceValidationCode.PreviewTitleMissing)); if (link == null) add(NovelSourceValidationIssue("list.link", NovelSourceValidationCode.PreviewLinkMissing)) }
        return NovelSourcePreview(title, link, cover, foundIssues)
    }
}

data class CompiledNovelCustomSource(val descriptor: NovelPluginDescriptor, val code: ByteArray, val checksum: String)

object NovelCustomSourceCompiler {
    private val json = Json { encodeDefaults = true }
    fun compile(definition: NovelCustomSourceDefinition): CompiledNovelCustomSource {
        val source = definition.requireValid(); val config = json.encodeToString(source)
        val code = SCRIPT.replace("__CONFIG__", config).toByteArray()
        val hash = sha256Hex(code)
        return CompiledNovelCustomSource(NovelPluginDescriptor(source.id, source.name, source.baseUrl, source.language, "1.0.${hash.take(8)}", "inline.js", sha256 = hash), code, hash)
    }

    private const val SCRIPT = """
const cheerio = require('cheerio');
module.exports = class HayaiCustomNovelSource {
  constructor() { this.c = __CONFIG__; this.site = this.c.baseUrl; this.filters = {}; this.pluginSettings = {}; }
  absolute(path) { return new URL(path, this.site).toString(); }
  async page(path) { const url=this.absolute(path); const sameOrigin=new URL(url).origin===new URL(this.site).origin; const r=await fetch(url, sameOrigin ? {headers:this.c.headers} : {}); if (!r.ok) throw new Error('HTTP '+r.status); return cheerio.load(await r.text()); }
  pick(root, selector) { const n = root.find(selector).first(); return n.length ? n : root.filter(selector).first(); }
  url(root, selector) { const v = this.pick(root, selector).attr('href'); return v ? this.absolute(v) : ''; }
  image(root, selector) { if (!selector) return ''; const n=this.pick(root,selector); const v=n.attr('data-src')||n.attr('data-lazy-src')||n.attr('src')||''; return v ? this.absolute(v) : ''; }
  async listing(path) { const $=await this.page(path); const novels=[]; $(this.c.list.item).each((_,el)=>{ const root=$(el); const title=this.pick(root,this.c.list.title).text().trim(); const url=this.url(root,this.c.list.link); if(title&&url) novels.push({name:title,path:url,cover:this.image(root,this.c.list.cover)}); }); const hasNextPage=!!this.c.list.next && $(this.c.list.next).length>0; return {novels,hasNextPage}; }
  popularNovels(page, options) { return this.listing(this.c.popularPath.replaceAll('{page}',String(page))); }
  searchNovels(query,page) { return this.listing(this.c.searchPath.replaceAll('{query}',encodeURIComponent(query)).replaceAll('{page}',String(page))); }
  async parseNovel(path) { const $=await this.page(path); const d=this.c.details; const text=s=>s?$(s).first().text().trim():''; const image=s=>{if(!s)return '';const n=$(s).first();const v=n.attr('data-src')||n.attr('src')||'';return v?this.absolute(v):''}; const chapters=[]; $(this.c.chapters.item).each((_,el)=>{const root=$(el);const name=this.pick(root,this.c.chapters.title).text().trim();const url=this.url(root,this.c.chapters.link);if(name&&url)chapters.push({name,path:url,releaseTime:this.c.chapters.date?this.pick(root,this.c.chapters.date).text().trim():null});}); return {name:text(d.title),author:text(d.author),summary:d.description?$(d.description).first().text().trim():'',cover:image(d.cover),genres:d.genres?$(d.genres).map((_,e)=>$(e).text().trim()).get().join(', '):'',status:text(d.status),chapters}; }
  async parseChapter(path) { const $=await this.page(path); this.c.content.remove.forEach(s=>$(s).remove()); const node=$(this.c.content.body).first(); if(!node.length) throw new Error('Chapter content selector matched nothing'); return {chapterText:node.html()||node.text()}; }
  resolveUrl(path) { return this.absolute(path); }
};
"""
}
