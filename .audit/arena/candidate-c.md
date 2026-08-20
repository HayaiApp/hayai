# Candidate C: parallel J2K adapter model

## Usage

```kotlin
val hayai = HayaiJ2K.open(context)
hayai.library.setLewdFilter(LewdFilter.EXCLUDE)
hayai.reader.open(chapterId)
hayai.sidecar.quotes.forChapter(chapterId)
hayai.sources.enabledSources()
```

## Shape

Candidate C proposes eight Hayai-owned adapters for the database, importer, library, reader, sidecar, source registry, settings, and composition facade. It keeps `App.kt`, `ReaderActivity`, `MainActivity`, and `LibraryPresenter` unchanged.

```kotlin
interface HayaiJ2K {
    val library: J2KLibrary
    val reader: J2KReader
    val sidecar: J2KSidecar
    val sources: J2KSourceRegistry
}

enum class LewdFilter { EXCLUDE, INCLUDE, ONLY }

interface NovelSource {
    suspend fun chapters(novelId: String): List<NovelChapter>
    suspend fun fetchChapter(chapterId: String): String
}
```

The migration keeps legacy `tachiyomi.db` read-only and imports into a separate database. It records deterministic legacy IDs and a completion marker in one transaction. Hayai-only quotes, EH metadata, saved searches, and novel settings live in sidecar tables.

The source registry describes EH, ExHentai, 8Muses, HBrowse, MangaDex, NHentai, Puruin, and LANraragi. The dedicated novel activity consumes one text document per chapter.

## Distinctive decision

Candidate C duplicates J2K library, chapter, progress, source, and settings tables behind the facade instead of importing core rows into J2K's existing tables.

## Tradeoff

The duplicated model isolates J2K internals, but every library and progress operation must stay synchronized with J2K. The approach adds a second source of truth and bypasses J2K's mature database queries, backup flow, and library UI.
