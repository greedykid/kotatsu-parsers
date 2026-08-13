package org.koitharu.kotatsu.parsers.site.id

import org.json.JSONObject
import org.jsoup.HttpStatusException
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONToSet
import org.koitharu.kotatsu.parsers.util.json.toJSONObjectOrNull
import org.koitharu.kotatsu.parsers.util.json.toStringSet
import org.koitharu.kotatsu.parsers.util.suspendlazy.getOrNull
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

/**
 * Natsu, which used to be a Themesia site at natsu.id and is now a WordPress
 * install at natsu.one running a theme of its own.
 *
 * Nothing carried over — the old parser inherited MangaReaderParser and every
 * selector it relied on is gone — so this is written from scratch rather than
 * patched.
 *
 * The catalogue comes from WordPress' own REST API rather than from the page
 * markup. The theme styles everything with Tailwind utility classes, which say
 * nothing about what an element *is* and get rewritten whenever the design is
 * touched; `wp-json` returns the same records as structured data and is part of
 * WordPress rather than of this theme.
 *
 * Chapters and pages still have to be scraped, because the `chapter` post type
 * the API exposes has no usable link back to its manga. They anchor on
 * `data-chapter-number`, `<time datetime>` and `section[data-image-data]`, all
 * of which describe content rather than appearance, and on the schema.org
 * JSON-LD block for the synopsis, author, genres and status.
 */
@MangaSourceParser("NATSU", "Natsu", "id")
internal class NatsuParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.NATSU, pageSize = 20) {

	override val configKeyDomain = ConfigKey.Domain("natsu.one")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	// WordPress orders by date, modified or title. It has no notion of
	// popularity for a custom post type, so that one is not offered rather than
	// being quietly mapped onto something else.
	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
			isSearchWithFiltersSupported = true,
		)

	// Fetched once and reused, because getDetails needs it too: the genre links
	// on a details page carry a slug, while the API filters by term id, so
	// without this the tags shown on a manga would not be the tags you can
	// browse by.
	private val tagsCache = suspendLazy(initializer = ::fetchTags)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = tagsCache.get(),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			append("/wp-json/wp/v2/manga?_embed=1&per_page=")
			append(pageSize.toString())
			append("&page=")
			append(page.toString())

			if (!filter.query.isNullOrEmpty()) {
				append("&search=")
				append(filter.query.urlEncoded())
			}

			if (filter.tags.isNotEmpty()) {
				append("&genre=")
				filter.tags.joinTo(this, ",") { it.key }
			}

			append("&orderby=")
			append(
				when (order) {
					SortOrder.NEWEST -> "date"
					SortOrder.ALPHABETICAL, SortOrder.ALPHABETICAL_DESC -> "title"
					else -> "modified"
				},
			)
			append("&order=")
			append(if (order == SortOrder.ALPHABETICAL) "asc" else "desc")
		}

		// WordPress answers 400 rather than an empty array once you ask for a page
		// past the last one, and the paginator always asks for one more than it
		// needs to know it has reached the end. Only that specific case is
		// swallowed — anything else still surfaces as the error it is.
		val json = try {
			webClient.httpGet(url).parseJsonArray()
		} catch (e: HttpStatusException) {
			if (e.statusCode == 400) return emptyList() else throw e
		}

		return json.mapJSONNotNull { jo ->
			val link = jo.getStringOrNull("link") ?: return@mapJSONNotNull null
			val relativeUrl = link.toRelativeUrl(domain)
			Manga(
				id = generateUid(relativeUrl),
				title = jo.getJSONObject("title").getString("rendered").cleanupTitle(),
				altTitles = emptySet(),
				url = relativeUrl,
				publicUrl = link,
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = jo.embeddedCoverUrl(),
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		// Quoted: the value contains a slash and a plus, which an unquoted Jsoup
		// attribute selector does not read the way you would expect.
		val info = doc.select("script[type=\"application/ld+json\"]")
			.firstNotNullOfOrNull { it.data().toJSONObjectOrNull()?.takeIf { j -> j.has("creativeWorkStatus") } }
		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)

		val chapters = doc.select("div[data-chapter-number]").mapChapters(reversed = true) { _, element ->
			val href = element.selectFirst("a[href]")?.attrAsRelativeUrl("href") ?: return@mapChapters null
			val number = element.attr("data-chapter-number").toFloatOrNull() ?: 0f
			MangaChapter(
				id = generateUid(href),
				// The row spells the number out in its own markup, but as part of
				// a longer label that varies; the attribute is the reliable one.
				title = "Chapter ${element.attr("data-chapter-number")}",
				url = href,
				number = number,
				volume = 0,
				scanlator = null,
				uploadDate = dateFormat.parseSafe(element.selectFirst("time[datetime]")?.attr("datetime")),
				branch = null,
				source = source,
			)
		}

		return manga.copy(
			altTitles = info?.getStringOrNull("alternateName")
				?.split(',')
				?.mapNotNullToSet { it.trim().nullIfEmpty() }
				.orEmpty(),
			description = info?.getStringOrNull("description")
				?: doc.selectFirst("[itemprop=description]")?.textOrNull(),
			authors = setOfNotNull(
				info?.optJSONObject("author")?.getStringOrNull("name"),
				info?.optJSONObject("illustrator")?.getStringOrNull("name"),
			),
			state = when (info?.getStringOrNull("creativeWorkStatus")?.lowercase(Locale.ROOT)) {
				"ongoing" -> MangaState.ONGOING
				"completed" -> MangaState.FINISHED
				"on hiatus" -> MangaState.PAUSED
				"cancelled" -> MangaState.ABANDONED
				else -> null
			},
			// Matched by title against the fetched genre list so these carry the
			// same term ids the filter uses, and are therefore browsable. A genre
			// the list does not know about is dropped rather than shown with a
			// key that would filter to nothing.
			tags = info?.optJSONArray("genre")?.toStringSet().orEmpty().let { names ->
				val known = tagsCache.getOrNull().orEmpty().associateBy { it.title.lowercase(Locale.ROOT) }
				names.mapNotNullToSet { known[it.trim().lowercase(Locale.ROOT)] }
			},
			coverUrl = info?.optJSONObject("image")?.getStringOrNull("url") ?: manga.coverUrl,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		return doc.select("section[data-image-data] img").mapNotNull { img ->
			val url = img.src() ?: return@mapNotNull null
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		val json = webClient.httpGet("https://$domain/wp-json/wp/v2/genre?per_page=100").parseJsonArray()
		return json.mapJSONToSet { jo ->
			MangaTag(
				key = jo.getInt("id").toString(),
				title = jo.getString("name").cleanupTitle(),
				source = source,
			)
		}
	}

	/** WordPress renders titles with HTML entities in them, `&#8217;` and friends. */
	private fun String.cleanupTitle() = replace("&#8217;", "’")
		.replace("&#8211;", "–")
		.replace("&amp;", "&")
		.trim()

	private fun JSONObject.embeddedCoverUrl(): String? = optJSONObject("_embedded")
		?.optJSONArray("wp:featuredmedia")
		?.optJSONObject(0)
		?.getStringOrNull("source_url")
}
