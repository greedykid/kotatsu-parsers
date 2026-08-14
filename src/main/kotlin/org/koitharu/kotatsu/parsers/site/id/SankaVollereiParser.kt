package org.koitharu.kotatsu.parsers.site.id

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
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNullToSet
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

/**
 * SankaVollerei, a JSON API over Komiku's catalogue.
 *
 * Worth having alongside the Komiku parser rather than instead of it. It is the
 * same library of titles reached a different way: structured JSON instead of
 * scraped markup, and — at the time of writing — no bot wall of any kind, while
 * a third of the Indonesian sites in this library answer nothing but a
 * Cloudflare challenge. When one route breaks the other tends not to.
 *
 * The service publishes a limit of 30 requests a minute per IP and says it bans
 * over it. That is per reader here rather than per server, and ordinary
 * browsing is nowhere near it — covers are served by Komiku's own CDN, so the
 * only calls made are one per list page, one per manga opened and one per
 * chapter read.
 */
@MangaSourceParser("SANKAVOLLEREI", "SankaVollerei", "id")
internal class SankaVollereiParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.SANKAVOLLEREI, pageSize = 10) {

	override val configKeyDomain = ConfigKey.Domain("www.sankavollerei.web.id")

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

	// The API exposes no genre index, and the genres on a manga are plain names
	// with nothing to filter by, so there is nothing to offer here.
	override suspend fun getFilterOptions() = MangaListFilterOptions()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		if (!filter.query.isNullOrEmpty()) {
			// Search ignores the page number and answers the same rows whatever it
			// is given, so anything past the first page would repeat itself.
			if (page > 1) return emptyList()
			val body = webClient.httpGet(
				"https://$domain/comic/search?q=${filter.query.urlEncoded()}",
			).parseJson()
			body.assertNotRefusal()
			return body.optJSONArray("data")?.mapJSONNotNull { jo ->
				// A query with no hits comes back as one placeholder row whose slug
				// is empty, rather than as an empty array.
				val slug = jo.getStringOrNull("slug") ?: return@mapJSONNotNull null
				jo.toManga(slug, jo.getStringOrNull("thumbnail"))
			}.orEmpty()
		}

		val path = if (order == SortOrder.POPULARITY) "populer" else "terbaru"
		val body = webClient.httpGet("https://$domain/comic/$path?page=$page").parseJson()
		body.assertNotRefusal()
		return body.optJSONArray("comics")?.mapJSONNotNull { jo ->
			val slug = jo.getStringOrNull("link").slugFromLink() ?: return@mapJSONNotNull null
			jo.toManga(slug, jo.getStringOrNull("image"))
		}.orEmpty()
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val body = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseJson()
		body.assertNotRefusal()
		val metadata = body.optJSONObject("metadata")
		val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.ROOT)

		val rows = body.optJSONArray("chapters")?.mapJSONNotNull { jo ->
			jo.takeIf { it.getStringOrNull("slug") != null }
		}.orEmpty()
		val chapters = rows.mapChapters(reversed = true) { index, jo ->
			val slug = jo.getStringOrNull("slug") ?: return@mapChapters null
			MangaChapter(
				id = generateUid(slug),
				title = jo.getStringOrNull("chapter"),
				url = "/comic/chapter/$slug",
				// The rows carry a printed label rather than a number, and it is not
				// always a plain one, so position is the dependable ordering.
				number = index + 1f,
				volume = 0,
				scanlator = null,
				uploadDate = dateFormat.parseSafe(jo.getStringOrNull("date")),
				branch = null,
				source = source,
			)
		}

		// The site carries both the original title and an Indonesian one; the
		// Indonesian one is what a reader here is looking for, and the original
		// becomes the alternative rather than being dropped.
		val original = body.getStringOrNull("title")
		val indonesian = body.getStringOrNull("title_indonesian")

		return manga.copy(
			title = indonesian?.takeIf { it != original } ?: manga.title,
			altTitles = setOfNotNull(original?.takeIf { it != indonesian }),
			description = body.getStringOrNull("synopsis_full") ?: body.getStringOrNull("synopsis"),
			authors = setOfNotNull(metadata?.getStringOrNull("author")),
			state = when (metadata?.getStringOrNull("status")?.lowercase(Locale.ROOT)) {
				"ongoing", "berjalan" -> MangaState.ONGOING
				"completed", "tamat", "end" -> MangaState.FINISHED
				"hiatus" -> MangaState.PAUSED
				else -> null
			},
			tags = body.optJSONArray("genres")?.mapJSONNotNullToSet { jo ->
				val name = jo.getStringOrNull("name") ?: return@mapJSONNotNullToSet null
				// Nothing here filters by genre, so the key is only an identity.
				MangaTag(key = name.lowercase(Locale.ROOT), title = name, source = source)
			}.orEmpty(),
			coverUrl = body.getStringOrNull("image") ?: manga.coverUrl,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val body = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseJson()
		body.assertNotRefusal()
		val images = body.optJSONArray("images") ?: return emptyList()
		return (0 until images.length()).mapNotNull { i ->
			val url = images.optString(i).takeIf { it.startsWith("http") } ?: return@mapNotNull null
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	/**
	 * A refusal wearing a 200's clothes.
	 *
	 * A wrong path — and occasionally a request the service does not like — is
	 * answered with a normal-looking body whose `status` is "Plana AI Detector".
	 * Without this it would read as a page with no manga on it, which is a much
	 * more confusing thing to show a reader than an error.
	 */
	private fun JSONObject.assertNotRefusal() {
		if (getStringOrNull("status") == "Plana AI Detector") {
			throw ParseException(getStringOrNull("message") ?: "The source refused the request", "https://$domain")
		}
	}

	/**
	 * Listing rows link to Komiku itself, as an absolute or a relative URL. Both
	 * end with the slug, which is all that is needed to address the API.
	 */
	private fun String?.slugFromLink(): String? =
		this?.substringAfter("/manga/", "")?.substringBefore('/')?.nullIfEmpty()

	private fun JSONObject.toManga(slug: String, cover: String?) = Manga(
		id = generateUid(slug),
		title = getStringOrNull("title").orEmpty(),
		altTitles = setOfNotNull(getStringOrNull("altTitle")),
		url = "/comic/comic/$slug",
		publicUrl = "https://komiku.org/manga/$slug/",
		rating = RATING_UNKNOWN,
		contentRating = null,
		coverUrl = cover,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		source = source,
	)
}
