package org.koitharu.kotatsu.parsers.site.wpjson

import org.json.JSONObject
import org.jsoup.HttpStatusException
import org.koitharu.kotatsu.parsers.MangaLoaderContext
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
 * Indonesian manga sites running WordPress behind a Tailwind theme, read
 * through WordPress' own REST API instead of their markup.
 *
 * Several of them share a theme — the same `manga` post type, the same
 * `manga-status`, `manga-type`, `genre`, `series-author` and `artist`
 * taxonomies, the same `/manga/{slug}/` permalinks. Natsu and Kiryuu were
 * rebuilt on it within a month of each other, and both broke the same way:
 * their old selector parsers matched nothing and the sources listed nothing.
 *
 * The catalogue comes from `wp-json` rather than the page because the theme
 * styles everything with Tailwind utility classes — `flex`, `items-center`,
 * `rounded-[5px]` — which describe how a thing looks and get rewritten
 * whenever the design is touched. `wp-json` belongs to WordPress, not to the
 * theme, and should outlive the next redesign.
 *
 * Chapters and pages still have to be read from the page: the `chapter` post
 * type carries no usable link back to its manga. They anchor on
 * `data-chapter-number`, `<time datetime>` and `section[data-image-data]`,
 * which describe content rather than appearance, and on the schema.org JSON-LD
 * for the synopsis and author. Those are the parts most likely to differ
 * between sites on this theme, so they are open for a subclass to replace.
 */
internal abstract class WpJsonMangaParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	domain: String,
	pageSize: Int = 20,
) : PagedMangaParser(context, source, pageSize) {

	override val configKeyDomain = ConfigKey.Domain(domain)

	protected open val selectChapter = "div[data-chapter-number]"
	protected open val selectPage = "section[data-image-data] img"
	protected open val selectDescription = "[itemprop=description]"

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	// WordPress orders a custom post type by date, modified or title, and has no
	// notion of popularity for one — so that is not offered rather than being
	// quietly mapped onto something else.
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

	// Fetched once and reused, because a manga's own genres arrive as names and
	// the filter takes term ids. Without the map, the tags shown on a manga
	// would not be tags you could then browse by.
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

		// WordPress answers 400 rather than an empty array once asked for a page
		// past the last, and the paginator always asks for one more than it needs
		// to know it has reached the end. Only that case is swallowed.
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
				title = jo.getJSONObject("title").getString("rendered").cleanupHtmlEntities(),
				altTitles = setOfNotNull(jo.meta()?.getStringOrNull("alternative_title")?.nullIfEmpty()),
				url = relativeUrl,
				publicUrl = link,
				rating = jo.meta()?.getStringOrNull("score")?.toFloatOrNull()
					?.let { if (it in 0f..10f) it / 10f else RATING_UNKNOWN }
					?: RATING_UNKNOWN,
				contentRating = null,
				coverUrl = jo.coverUrl(),
				tags = jo.taxonomyNames("genre").mapNotNullToSet { name -> knownTag(name) },
				state = jo.taxonomyNames("status").firstNotNullOfOrNull { it.toMangaState() },
				authors = jo.taxonomyNames("series-author"),
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

		val chapters = doc.select(selectChapter).mapChapters(reversed = true) { _, element ->
			val href = element.selectFirst("a[href]")?.attrAsRelativeUrl("href") ?: return@mapChapters null
			val number = element.attr("data-chapter-number").toFloatOrNull() ?: 0f
			MangaChapter(
				id = generateUid(href),
				// The row spells the number out as part of a longer label that
				// varies; the attribute is the dependable one.
				title = element.attr("data-chapter-number").nullIfEmpty()?.let { "Chapter $it" },
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
			altTitles = manga.altTitles.ifEmpty {
				info?.getStringOrNull("alternateName")
					?.split(',')
					?.mapNotNullToSet { it.trim().nullIfEmpty() }
					.orEmpty()
			},
			description = info?.getStringOrNull("description")
				?: doc.selectFirst(selectDescription)?.textOrNull(),
			authors = manga.authors.ifEmpty {
				setOfNotNull(info?.optJSONObject("author")?.getStringOrNull("name"))
			},
			state = manga.state ?: info?.getStringOrNull("creativeWorkStatus")?.toMangaState(),
			tags = manga.tags.ifEmpty {
				info?.optJSONArray("genre")?.toStringSet().orEmpty()
					.mapNotNullToSet { name -> knownTag(name) }
			},
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		return doc.select(selectPage).mapNotNull { img ->
			val url = img.src() ?: return@mapNotNull null
			MangaPage(id = generateUid(url), url = url, preview = null, source = source)
		}
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		val json = webClient.httpGet("https://$domain/wp-json/wp/v2/genre?per_page=100").parseJsonArray()
		return json.mapJSONToSet { jo ->
			MangaTag(
				key = jo.getInt("id").toString(),
				title = jo.getString("name").cleanupHtmlEntities(),
				source = source,
			)
		}
	}

	/** The filter takes term ids, so a genre only counts if the index knows it. */
	private suspend fun knownTag(name: String): MangaTag? {
		val wanted = name.trim().lowercase(sourceLocale)
		return tagsCache.getOrNull()?.firstOrNull { it.title.lowercase(sourceLocale) == wanted }
	}

	/**
	 * Term names for one taxonomy.
	 *
	 * This theme carries a `metadata.tax` array with the full term objects on
	 * every record, which is both cheaper and more complete than the `_embedded`
	 * terms — but not every site on it does, so `_embedded` remains the fallback
	 * rather than the other way round.
	 */
	private fun JSONObject.taxonomyNames(taxonomy: String): Set<String> {
		optJSONObject("metadata")?.optJSONArray("tax")?.let { tax ->
			val names = tax.mapJSONNotNull { term ->
				term.getStringOrNull("name")?.takeIf { term.getStringOrNull("taxonomy") == taxonomy }
			}
			if (names.isNotEmpty()) {
				return names.toSet()
			}
		}
		val embedded = optJSONObject("_embedded")?.optJSONArray("wp:term") ?: return emptySet()
		return (0 until embedded.length()).flatMapTo(HashSet()) { i ->
			embedded.optJSONArray(i)?.mapJSONNotNull { term ->
				term.getStringOrNull("name")?.takeIf { term.getStringOrNull("taxonomy") == taxonomy }
			}.orEmpty()
		}
	}

	private fun JSONObject.meta(): JSONObject? = optJSONObject("metadata")?.optJSONObject("meta")

	private fun JSONObject.coverUrl(): String? = meta()?.getStringOrNull("thumbnail")?.nullIfEmpty()
		?: optJSONObject("_embedded")
			?.optJSONArray("wp:featuredmedia")
			?.optJSONObject(0)
			?.getStringOrNull("source_url")

	private fun String.toMangaState(): MangaState? = when (lowercase(Locale.ROOT)) {
		"ongoing", "berjalan" -> MangaState.ONGOING
		"completed", "tamat", "end" -> MangaState.FINISHED
		"on hiatus", "hiatus" -> MangaState.PAUSED
		"cancelled", "dropped" -> MangaState.ABANDONED
		else -> null
	}

	/** WordPress renders titles with HTML entities in them, `&#8217;` and friends. */
	private fun String.cleanupHtmlEntities() = replace("&#8217;", "’")
		.replace("&#8216;", "‘")
		.replace("&#8211;", "–")
		.replace("&#038;", "&")
		.replace("&amp;", "&")
		.trim()
}
