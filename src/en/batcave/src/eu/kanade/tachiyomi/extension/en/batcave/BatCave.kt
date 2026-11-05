package eu.kanade.tachiyomi.extension.en.batcave

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.attrOrNull
import keiyoushi.utils.tryParseDate
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.lang.UnsupportedOperationException

@Source
abstract class Batcave : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addInterceptor(DleGuardResolver.interceptor(baseUrl))

    override suspend fun getPopularManga(page: Int): MangasPage {
        // Route to the actual comics list URL, handling page 1 explicitly
        val pageUrl = if (page == 1) "$baseUrl/comix/" else "$baseUrl/comix/page/$page/"
        val document = client.get(pageUrl, Fetch.DOCUMENT).asJsoup()

        val mangaElements = document.select("a.poster.grid-item")

        val mangas = mangaElements.map { el ->
            SManga.create().apply {
                url = el.attr("href").removePrefix(baseUrl)
                title = el.select("p.poster__title").text()

                val imgElement = el.selectFirst("img")
                thumbnail_url = imgElement?.attrOrNull("data-src") ?: imgElement?.attrOrNull("src")
            }
        }

        val hasNextPage = document.select("div.pagination__pages span:not([class]) + a").isNotEmpty()

        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        // DLE sites typically sort by newest on the main catalog by default
        val pageUrl = if (page == 1) "$baseUrl/comix/" else "$baseUrl/comix/page/$page/"
        val document = client.get(pageUrl, Fetch.DOCUMENT).asJsoup()

        val mangaElements = document.select("a.poster.grid-item")

        val mangas = mangaElements.map { el ->
            SManga.create().apply {
                url = el.attr("href").removePrefix(baseUrl)
                title = el.select("p.poster__title").text()

                val imgElement = el.selectFirst("img")
                thumbnail_url = imgElement?.attrOrNull("data-src") ?: imgElement?.attrOrNull("src")
            }
        }

        val hasNextPage = document.select("div.pagination__pages span:not([class]) + a").isNotEmpty()

        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get("$baseUrl${manga.url}", Fetch.DOCUMENT).asJsoup()

        val updatedManga = manga.apply {
            description = document.select("div.page__text").text()
            author = document.select("li:has(div:contains(Writer)) a").text()
            artist = document.select("li:has(div:contains(Artist)) a").text()
            genre = document.select("div.page__tags a").joinToString { it.text() }

            val statusText = document.select("li:has(div:contains(Release type))").text().lowercase()
            status = when {
                statusText.contains("ongoing") -> SManga.ONGOING
                statusText.contains("completed") -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }

        val chapterList = document.select("div.cl__item").map { el ->
            SChapter.create().apply {
                val a = el.select("h3.cl__item-title a")
                name = a.text()
                url = a.attr("href").removePrefix(baseUrl)

                val dateString = el.select("div.cl__item-date").text()
                date_upload = dateFormat.tryParseDate(dateString)
            }
        }

        return SMangaUpdate(updatedManga, chapterList)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get("$baseUrl${chapter.url}", Fetch.DOCUMENT).asJsoup()

        return document.select("div.reader__item-wrap img").mapIndexed { index, img ->
            // Prioritize data-src for lazy loading, fallback to src, and ensure absolute URLs
            val imageUrl = img.absUrl("data-src").ifEmpty { img.absUrl("src") }
            Page(index, imageUrl = imageUrl)
        }
    }

    companion object {
        // Matches the "6.11.2024" format found on the site
        private val dateFormat = java.time.format.DateTimeFormatter.ofPattern("d.MM.yyyy", java.util.Locale.ENGLISH)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = throw UnsupportedOperationException()

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? = null
}
