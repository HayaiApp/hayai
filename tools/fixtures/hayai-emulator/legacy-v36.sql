PRAGMA user_version = 36;
PRAGMA foreign_keys = ON;

CREATE TABLE categories(_id INTEGER PRIMARY KEY, name TEXT NOT NULL, sort INTEGER NOT NULL, flags INTEGER NOT NULL, manga_order TEXT NOT NULL);
CREATE TABLE mangas(_id INTEGER PRIMARY KEY, source INTEGER NOT NULL, url TEXT NOT NULL, artist TEXT, author TEXT, description TEXT, genre TEXT, title TEXT NOT NULL, status INTEGER NOT NULL, thumbnail_url TEXT, favorite INTEGER NOT NULL, last_update INTEGER NOT NULL, initialized INTEGER NOT NULL, viewer INTEGER NOT NULL, hide_title INTEGER NOT NULL, chapter_flags INTEGER NOT NULL, date_added INTEGER NOT NULL, filtered_scanlators TEXT, update_strategy INTEGER NOT NULL, memo TEXT NOT NULL);
CREATE TABLE chapters(_id INTEGER PRIMARY KEY, manga_id INTEGER NOT NULL, url TEXT NOT NULL, name TEXT NOT NULL, scanlator TEXT, read INTEGER NOT NULL, bookmark INTEGER NOT NULL, last_page_read INTEGER NOT NULL, pages_left INTEGER NOT NULL, chapter_number REAL NOT NULL, source_order INTEGER NOT NULL, date_fetch INTEGER NOT NULL, date_upload INTEGER NOT NULL, memo TEXT NOT NULL);
CREATE TABLE mangas_categories(_id INTEGER PRIMARY KEY, manga_id INTEGER NOT NULL, category_id INTEGER NOT NULL);
CREATE TABLE history(history_id INTEGER PRIMARY KEY, history_chapter_id INTEGER NOT NULL, history_last_read INTEGER NOT NULL, history_time_read INTEGER NOT NULL);
CREATE TABLE search_metadata(manga_id INTEGER PRIMARY KEY, uploader TEXT, extra TEXT NOT NULL, indexed_extra TEXT, extra_version INTEGER NOT NULL);
CREATE TABLE series_quotes(quote_id TEXT PRIMARY KEY, manga_id INTEGER NOT NULL, novel_name TEXT NOT NULL, chapter_name TEXT NOT NULL, displayed_content TEXT NOT NULL, original_content TEXT NOT NULL, translated_content TEXT, language TEXT, timestamp INTEGER NOT NULL);
CREATE TABLE novel_repos(base_url TEXT PRIMARY KEY, name TEXT NOT NULL);
CREATE TABLE novel_chapter_stats(chapter_id INTEGER PRIMARY KEY, word_count INTEGER NOT NULL);
CREATE TABLE eh_favorites(gid TEXT NOT NULL, token TEXT NOT NULL, title TEXT NOT NULL, category INTEGER NOT NULL);

INSERT INTO categories VALUES(1, 'Verification', 0, 0, '');
INSERT INTO mangas VALUES(100, 1, 'Verification Novel', NULL, 'Hayai Verification', 'Controlled local novel fixture', 'novel', 'Verification Novel', 1, NULL, 1, 0, 1, 0, 0, 0, 1700000000000, NULL, 0, '{}');
INSERT INTO mangas VALUES(200, 6901, '{{EH_PATH}}', NULL, NULL, 'Controlled E-Hentai verification gallery', 'adult', 'Verification E-Hentai Gallery', 0, NULL, 1, 0, 1, 0, 0, 0, 1700000000000, NULL, 0, '{}');
INSERT INTO mangas VALUES(201, 6902, '{{EXH_PATH}}', NULL, NULL, 'Controlled ExHentai verification gallery', 'adult', 'Verification ExHentai Gallery', 0, NULL, 1, 0, 1, 0, 0, 0, 1700000000000, NULL, 0, '{}');
INSERT INTO chapters VALUES(1000, 100, 'Verification Novel/chapter-1.html', 'Chapter 1', NULL, 0, 1, 25, 0, 1, 0, 1700000000000, 1700000000000, '{}');
INSERT INTO mangas_categories VALUES(1, 100, 1);
INSERT INTO mangas_categories VALUES(2, 200, 1);
INSERT INTO mangas_categories VALUES(3, 201, 1);
INSERT INTO history VALUES(1, 1000, 1700000000000, 120000);
INSERT INTO search_metadata VALUES(200, 'fixture', '{"gid":"{{EH_GID}}","token":"{{EH_TOKEN}}"}', '{{EH_GID}}', 1);
INSERT INTO search_metadata VALUES(201, 'fixture', '{"gid":"{{EXH_GID}}","token":"{{EXH_TOKEN}}"}', '{{EXH_GID}}', 1);
INSERT INTO series_quotes VALUES('fixture-quote', 100, 'Verification Novel', 'Chapter 1', 'A migrated quote.', 'A migrated quote.', NULL, 'en', 1700000000000);
INSERT INTO novel_repos VALUES('https://fixture.invalid/novels', 'Sanitized fixture');
INSERT INTO novel_chapter_stats VALUES(1000, 42);
INSERT INTO eh_favorites VALUES('{{EH_GID}}', '{{EH_TOKEN}}', 'Verification E-Hentai Gallery', 0);

