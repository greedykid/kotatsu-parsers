package org.koitharu.kotatsu.parsers.site.mangareader.id

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.EnumSet

@MangaSourceParser("KOMIKU", "Komiku", "id")
internal class Komiku(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.KOMIKU, "komiku.org", pageSize = 20, searchPageSize = 10) {

	// Komiku does not serve its catalogue inline — /pustaka/ carries only the
	// filters, and the entries are pulled in afterwards with htmx. The endpoint
	// that serves them moved: api.komiku.id no longer resolves at all, so the
	// list and the search both came back empty while the site itself was fine.
	// The new host is named in the hx-get attribute on komiku.org/pustaka/.
	private val apiDomain = "api.komiku.org"
	override val datePattern = "dd/MM/yyyy"
	override val selectPage = "#Baca_Komik img"
	override val selectTestScript = "script:containsData(thisIsNeverFound)"
	override val listUrl = "/manga/"
	override val selectMangaList = "div.bge"
	override val selectMangaListImg = "img"
	override val selectMangaListTitle = "h3"
	override val selectChapter = "#Daftar_Chapter tr:has(td.judulseries)"
	override val detailsDescriptionSelector = "#Sinopsis > p"

	override val filterCapabilities: MangaListFilterCapabilities
		get() = super.filterCapabilities.copy(
			isTagsExclusionSupported = false,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")

			when {
				!filter.query.isNullOrEmpty() -> {
					append(apiDomain)
					append("/page/")
					append(page.toString())
					append("/?post_type=manga&s=")
					append(filter.query.urlEncoded())
				}

				else -> {
					append(apiDomain)
					append(listUrl)
					if (page > 1) {
						append("/page/")
						append(page.toString())
					}
					append("/?")

					append("orderby=")
					append(
						when (order) {
							SortOrder.ALPHABETICAL, SortOrder.ALPHABETICAL_DESC -> "title"
							SortOrder.NEWEST -> "date"
							SortOrder.POPULARITY -> "meta_value_num"
							SortOrder.UPDATED -> "modified"
							else -> "modified"
						},
					)

					filter.tags.oneOrThrowIfMany()?.let { tag ->
						append("&genre=")
						append(tag.key)
					}

					filter.types.oneOrThrowIfMany()?.let {
						append("&tipe=")
						append(
							when (it) {
								ContentType.MANGA -> "manga"
								ContentType.MANHWA -> "manhwa"
								ContentType.MANHUA -> "manhua"
								else -> ""
							},
						)
					}

					filter.states.oneOrThrowIfMany()?.let {
						append("&status=")
						when (it) {
							MangaState.ONGOING -> append("ongoing")
							MangaState.FINISHED -> append("end")
							else -> append("")
						}
					}
				}
			}
		}

		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	override fun parseMangaList(docs: Document): List<Manga> {
		return docs.select(selectMangaList).mapNotNull { element ->
			val a = element.selectFirst("a:has(h3)") ?: return@mapNotNull null
			val relativeUrl = a.attrAsRelativeUrl("href").toRelativeUrl(domain)

			val thumbnailUrl = element.selectFirst(selectMangaListImg)?.src()?.let { url ->
				if (url.contains("/uploads/\\d{4}/\\d{2}/".toRegex())) {
					url
				} else {
					url.substringBeforeLast("?")
						.replace("/Manga-", "/Komik-")
						.replace("/Manhua-", "/Komik-")
						.replace("/Manhwa-", "/Komik-")
				}
			}

			Manga(
				id = generateUid(relativeUrl),
				url = relativeUrl,
				title = element.selectFirst(selectMangaListTitle)?.text()?.trim() ?: return@mapNotNull null,
				altTitles = emptySet(),
				publicUrl = a.attrAsAbsoluteUrl("href"),
				rating = RATING_UNKNOWN,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
				coverUrl = thumbnailUrl,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val docs = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)

		val chapters = docs.select(selectChapter).mapChapters(reversed = true) { index, element ->
			val a = element.selectFirst("td.judulseries a") ?: return@mapChapters null
			val url = a.attrAsRelativeUrl("href")
			val dateText = element.selectFirst("td.tanggalseries")?.text()

			MangaChapter(
				id = generateUid(url),
				title = a.selectFirst("span")?.text()?.trim() ?: a.text().trim(),
				url = url,
				number = index + 1f,
				volume = 0,
				scanlator = null,
				uploadDate = dateFormat.parseSafe(dateText),
				branch = null,
				source = source,
			)
		}

		return parseInfo(docs, manga, chapters)
	}

	override suspend fun parseInfo(docs: Document, manga: Manga, chapters: List<MangaChapter>): Manga {
		val tags = docs.select("ul.genre li.genre a").mapNotNullToSet { element ->
			val href = element.attr("href")
			val genreKey = href.substringAfter("/genre/").substringBefore("/")
			val genreTitle = element.selectFirst("span[itemprop='genre']")?.text()?.trim()
				?: element.text().trim()

			MangaTag(
				key = genreKey,
				title = genreTitle.toTitleCase(sourceLocale),
				source = source,
			)
		}
		val statusText = docs.selectFirst("table.inftable tr > td:contains(Status) + td")?.text()
		val state = when {
			statusText?.contains("Ongoing") == true -> MangaState.ONGOING
			statusText?.contains("Completed") == true -> MangaState.FINISHED
			statusText?.contains("Tamat", ignoreCase = true) == true -> MangaState.FINISHED
			statusText?.contains("End", ignoreCase = true) == true -> MangaState.FINISHED

			else -> null
		}

		val author = docs.selectFirst("table.inftable tr:has(td:contains(Pengarang)) td:last-child")?.text()?.trim()

		val altTitle =
			docs.selectFirst("table.inftable tr:has(td:contains(Judul Indonesia)) td:last-child")?.text()?.trim()
		val altTitles = if (!altTitle.isNullOrBlank()) setOf(altTitle) else emptySet()

		val thumbnail = docs.selectFirst("div.ims > img")?.attr("src")?.substringBeforeLast("?")

		return manga.copy(
			altTitles = altTitles,
			description = docs.selectFirst(detailsDescriptionSelector)?.text()?.trim(),
			state = state,
			authors = setOfNotNull(author),
			contentRating = if (manga.contentRating == ContentRating.ADULT) ContentRating.ADULT else ContentRating.SAFE,
			tags = tags,
			chapters = chapters,
			coverUrl = thumbnail ?: manga.coverUrl,
		)
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		// Not the API host: its root answers 500. The genre dropdown lives on the
		// site's own library page, which is also where the filters are rendered.
		val doc = webClient.httpGet("https://$domain/pustaka/").parseHtml()
		val tags = mutableSetOf<MangaTag>()

		doc.select("select[name='genre'] option").forEach { option ->
			val value = option.attr("value")
			val title = option.text().trim()

			if (value.isNotBlank() && !title.contains("Genre", ignoreCase = true)) {
				tags.add(
					MangaTag(
						key = value,
						title = title,
						source = source,
					),
				)
			}
		}
		return tags
	}
}
