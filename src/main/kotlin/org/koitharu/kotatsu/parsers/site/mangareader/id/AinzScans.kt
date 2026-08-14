package org.koitharu.kotatsu.parsers.site.mangareader.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import java.util.*

@MangaSourceParser("AINZSCANS", "AinzScans", "id")
internal class AinzScans(context: MangaLoaderContext) :
	// ainzscans.net still answers, but it no longer carries the library — which is
	// why readers who got past its Cloudflare challenge were met with a 404 on
	// every list request. This is where the site lives now.
	//
	// The domain is the only change. The selectors are left alone because this
	// move could not be verified from the machine it was made on: the new host
	// answers it a Cloudflare challenge as well. A 404 was certain to fail; this
	// at worst fails in the same place, and at best works.
	MangaReaderParser(context, MangaParserSource.AINZSCANS, "v3.ainzscans01.com", pageSize = 20, searchPageSize = 10) {
	override val listUrl = "/series"
	override val datePattern = "MMM d, yyyy"
	override val sourceLocale: Locale = Locale.ENGLISH

	override val filterCapabilities: MangaListFilterCapabilities
		get() = super.filterCapabilities.copy(
			isTagsExclusionSupported = false,
		)
}
