package org.koitharu.kotatsu.parsers.site.id

import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

/**
 * Sansekai's komik API — JSON throughout, so nothing here is scraped.
 *
 * Every response is an envelope: `retcode` 0 means the `data` alongside it is
 * real, anything else means the `message` is. `meta` carries the paging, which
 * is how the end of a list is known rather than by asking for one page too many.
 *
 * **Written against a specification rather than against the service.** The API
 * refuses this author's network outright — a flat 403 from its own application
 * layer, not a challenge that could be solved — so not one response was ever
 * seen. The field names, paths and the status numbering below come from a
 * working client the app's owner wrote against it, which is good evidence but
 * is not the same as having watched it answer. If this source lists nothing,
 * suspect the shapes here before suspecting the network.
 *
 * The service is documented as allowing 10 requests a minute. That is per
 * reader rather than per server, and browsing costs one call per list page, one
 * per manga and one per chapter, so it is not close.
 */
@MangaSourceParser("SANSEKAI", "Sansekai", "id")
internal class SansekaiParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.SANSEKAI, pageSize = 20) {

	override val configKeyDomain = ConfigKey.Domain("api.sansekai.my.id")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	// The API documents no way to filter, only to sort and to search, so nothing
	// is offered here rather than offering a control that would do nothing.
	override suspend fun getFilterOptions() = MangaListFilterOptions()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val path = when {
			!filter.query.isNullOrEmpty() -> "/search?query=${filter.query.urlEncoded()}"
			order == SortOrder.POPULARITY -> "/popular"
			// "project" is the site's own work; the alternative is a republished
			// feed, and mixing the two makes for a list full of duplicates.
			else -> "/latest?type=project"
		}
		val separator = if (path.contains('?')) '&' else '?'
		val body = webClient.httpGet("$api$path${separator}page=$page").parseJson()
		return body.unwrapArray().mapJSONNotNull { it.toManga() }
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val id = manga.mangaId()
		val details = webClient.httpGet("$api/detail?manga_id=$id").parseJson().unwrapObject()
		val chapters = webClient.httpGet("$api/chapterlist?manga_id=$id").parseJson().unwrapArray()
		val taxonomy = details.optJSONObject("taxonomy")

		return details.toManga()?.copy(
			description = details.getStringOrNull("description"),
			altTitles = setOfNotNull(details.getStringOrNull("alternative_title")),
			authors = taxonomy?.optJSONArray("Author").names(),
			tags = taxonomy?.optJSONArray("Genre").names().mapToSet { name ->
				MangaTag(key = name.lowercase(Locale.ROOT), title = name, source = source)
			},
			// Newest first, which is the order the endpoint answers in.
			chapters = chapters.mapJSONNotNull { it }.mapChapters(reversed = true) { index, jo ->
				val chapterId = jo.getStringOrNull("chapter_id") ?: return@mapChapters null
				val number = jo.optDouble("chapter_number").toFloat()
				MangaChapter(
					id = generateUid(chapterId),
					title = jo.getStringOrNull("chapter_title"),
					url = "/api/komik/getimage?chapter_id=$chapterId",
					number = if (number.isNaN() || number <= 0f) index + 1f else number,
					volume = 0,
					scanlator = null,
					uploadDate = jo.getStringOrNull("release_date").toEpochMillis(),
					branch = null,
					source = source,
				)
			},
		) ?: manga
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val body = webClient.httpGet("$api/getimage?chapter_id=${chapter.chapterId().urlEncoded()}").parseJson()
		val images = body.unwrapObject().optJSONObject("chapter")?.optJSONArray("data") ?: return emptyList()
		return (0 until images.length()).mapNotNull { i ->
			val url = images.optString(i).takeIf { it.startsWith("http") } ?: return@mapNotNull null
			MangaPage(id = generateUid(url), url = url, preview = null, source = source)
		}
	}

	private fun JSONObject.toManga(): Manga? {
		val id = getStringOrNull("manga_id") ?: return null
		val taxonomy = optJSONObject("taxonomy")
		return Manga(
			id = generateUid(id),
			title = getStringOrNull("title").orEmpty(),
			altTitles = emptySet(),
			// A query string rather than a path, because that is how this API
			// addresses a series; [mangaId] reads the id back out of it.
			url = "/api/komik/detail?manga_id=$id",
			publicUrl = "https://$domain/api/komik/detail?manga_id=$id",
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = getStringOrNull("cover_portrait_url") ?: getStringOrNull("cover_image_url"),
			tags = emptySet(),
			// Numbered without being documented. Anything outside the three
			// values seen in the wild is left unknown rather than guessed.
			state = when (optInt("status", -1)) {
				1 -> MangaState.ONGOING
				2 -> MangaState.FINISHED
				3 -> MangaState.PAUSED
				else -> null
			},
			authors = taxonomy?.optJSONArray("Author").names(),
			source = source,
		)
	}

	private fun Manga.mangaId() = url.substringAfterLast('=')

	private fun MangaChapter.chapterId() = url.substringAfterLast('=')

	/** `retcode` 0 and a payload, or the `message` explaining why not. */
	private fun JSONObject.unwrapArray(): JSONArray = when {
		optInt("retcode", -1) != 0 -> throw ParseException(failureMessage(), "https://$domain")
		else -> optJSONArray("data") ?: JSONArray()
	}

	private fun JSONObject.unwrapObject(): JSONObject = when {
		optInt("retcode", -1) != 0 -> throw ParseException(failureMessage(), "https://$domain")
		else -> optJSONObject("data") ?: throw ParseException(failureMessage(), "https://$domain")
	}

	private fun JSONObject.failureMessage() =
		getStringOrNull("message") ?: "The source returned no data"

	private fun JSONArray?.names(): Set<String> = this?.mapJSONNotNull {
		it.getStringOrNull("name")
	}.orEmpty().toSet()

	/**
	 * The release dates are not documented either. ISO-8601 is what the client
	 * this was written from passed straight through, so it is tried first, then
	 * the same without a zone, and an unparseable date becomes no date rather
	 * than a wrong one.
	 */
	private fun String?.toEpochMillis(): Long {
		if (this.isNullOrEmpty()) return 0L
		for (pattern in DATE_PATTERNS) {
			val parsed = SimpleDateFormat(pattern, Locale.ROOT).parseSafe(this)
			if (parsed != 0L) return parsed
		}
		return 0L
	}

	/** Derived from [configKeyDomain] so changing the domain actually moves it. */
	private val api get() = "https://$domain/api/komik"

	private companion object {
		private val DATE_PATTERNS = arrayOf(
			"yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
			"yyyy-MM-dd'T'HH:mm:ssXXX",
			"yyyy-MM-dd HH:mm:ss",
			"yyyy-MM-dd",
		)
	}
}
