package org.koitharu.kotatsu.parsers.site.wpjson.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.wpjson.WpJsonMangaParser

/**
 * natsu.id is a splash page; the library moved to natsu.one and was rebuilt on
 * the WordPress theme [WpJsonMangaParser] describes. Verified against the live
 * site: 6375 titles over 319 pages, search, genre filtering and ordering, a
 * 48-chapter title with ISO dates, and a chapter of 26 images.
 */
@MangaSourceParser("NATSU", "Natsu", "id")
internal class Natsu(context: MangaLoaderContext) :
	WpJsonMangaParser(context, MangaParserSource.NATSU, "natsu.one")
