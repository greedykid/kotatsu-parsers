package org.koitharu.kotatsu.parsers.site.wpjson.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.wpjson.WpJsonMangaParser

/**
 * Kiryuu moved twice over: kiryuu02.com is a landing page now, and the site at
 * v7.kiryuu.to has been rebuilt on the same WordPress theme as Natsu — the same
 * `manga` post type, the same status/type/genre/series-author/artist
 * taxonomies, the same `/manga/{slug}/` permalinks.
 *
 * Its records are richer than Natsu's: `metadata.tax` carries whole term
 * objects and `metadata.meta` the cover, alternative titles and score, so a
 * listing needs no second request to be complete.
 *
 * The catalogue was confirmed against the live API. The chapter and page
 * selectors are inherited on the strength of the two themes being the same,
 * which is good evidence and not a measurement — the site refuses the machine
 * this was written on, so no details page of it has been seen.
 */
@MangaSourceParser("KIRYUU", "Kiryuu", "id")
internal class Kiryuu(context: MangaLoaderContext) :
	WpJsonMangaParser(context, MangaParserSource.KIRYUU, "v7.kiryuu.to", pageSize = 50)
