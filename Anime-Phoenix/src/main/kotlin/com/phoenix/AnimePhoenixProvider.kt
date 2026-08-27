package com.phoenix

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.select.Elements
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Reconstructed from a decompile of the previously-published Anime-Phoenix.cs3 binary
 * (no source for this provider existed anywhere in this repo). The compiled plugin is
 * confirmed working, so this aims to be a faithful, readable translation of its actual
 * behavior rather than a redesign. See inline TODOs for the handful of spots where the
 * decompiled bytecode's control flow was ambiguous/corrupted and a judgment call was made.
 */
class AnimePhoenixProvider : MainAPI() {
    override var mainUrl = "https://anime-phoenix.com"
    override var name = "anime-phoenix"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val customHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
        "Referer" to mainUrl
    )

    // Confidence: high. Reconstructed cleanly; the only real uncertainty is the relative
    // order of the "home-cols" widgets vs the dedicated "Movies" section below, since jadx
    // merged their two forEach loops into one corrupted/unreachable loop structure in the
    // decompiled bytecode. The selectors, fields, and hasNext=true return value are all
    // directly confirmed from the bytecode.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
        val document = app.get(url, headers = customHeaders).document

        val homePageRows = ArrayList<HomePageList>()

        // "Latest episodes added" strip at the top of the homepage.
        document.selectFirst("main.FJ-Phoenix-Anastasia-Latest")?.let { latestSection ->
            val sectionTitle = latestSection.selectFirst("h2.FJ-Phoenix-Anastasia-Title")?.text()
                ?: "آخر الحلقات المضافة"

            val items = latestSection.select("a.FJ-Phoenix-Anastasia-EpCard").mapNotNull { card ->
                val cardName = card.selectFirst(".FJ-Phoenix-Anastasia-EpCard-Name")?.text()
                    ?: return@mapNotNull null
                val epMeta = card.selectFirst(".FJ-Phoenix-Anastasia-EpCard-MetaModern")?.text() ?: ""
                val cleanTitle = if (epMeta.isNotEmpty()) "$cardName - $epMeta" else cardName
                val href = card.attr("href")
                val cleanHref = if (href.startsWith("http")) href else mainUrl + href
                val poster = card.selectFirst("img.FJ-Phoenix-Anastasia-EpCard-Img")?.attr("src")
                newAnimeSearchResponse(cleanTitle, cleanHref, TvType.Anime) {
                    posterUrl = poster
                }
            }

            if (items.isNotEmpty()) {
                homePageRows.add(HomePageList(sectionTitle, items))
            }
        }

        // Homepage "column" widgets (e.g. recently added / popular collections).
        document.select("section.home-cols div.home-cols-col").forEach { col ->
            val colTitle = col.selectFirst("h2.home-cols-title")?.text() ?: ""
            val colItems = col.select("a.home-cols-card").mapNotNull { card ->
                val title = card.selectFirst("h3.home-cols-name")?.text() ?: return@mapNotNull null
                val href = card.attr("href")
                val cleanHref = if (href.startsWith("http")) href else mainUrl + href
                val poster = card.selectFirst("img.home-cols-thumb")?.attr("src")
                val isMovie = href.contains("/movies/")
                newAnimeSearchResponse(title, cleanHref, if (isMovie) TvType.AnimeMovie else TvType.Anime) {
                    posterUrl = poster
                }
            }
            if (colTitle.isNotEmpty() && colItems.isNotEmpty()) {
                homePageRows.add(HomePageList(colTitle, colItems))
            }
        }

        // Dedicated "Movies" sections.
        document.select("section.FJ-Phoenix-Anastasia-Movies").forEach { section ->
            val sectionTitle = section.selectFirst(".FJ-Phoenix-Anastasia-Title")?.text() ?: ""
            val items = section.select("a.FJ-Phoenix-Anastasia-EpCard").mapNotNull { card ->
                val title = card.selectFirst(".FJ-Phoenix-Anastasia-EpCard-Name")?.text()
                    ?: return@mapNotNull null
                val href = card.attr("href")
                val cleanHref = if (href.startsWith("http")) href else mainUrl + href
                val poster = card.selectFirst("img.FJ-Phoenix-Anastasia-EpCard-Img")?.attr("src")
                val isMovie = href.contains("/movies/")
                newAnimeSearchResponse(title, cleanHref, if (isMovie) TvType.AnimeMovie else TvType.Anime) {
                    posterUrl = poster
                }
            }
            if (sectionTitle.isNotEmpty() && items.isNotEmpty()) {
                homePageRows.add(HomePageList(sectionTitle, items))
            }
        }

        return newHomePageResponse(homePageRows, hasNext = true)
    }

    // Confidence: high. GET the search landing page and scrape the WP nonce embedded as
    // JSON (`"nonce":"..."`) in an inline script. Falls back to a hardcoded nonce value that
    // was present in the compiled bytecode (likely baked in by the original author as a
    // last-resort default) if the request or regex fails.
    private suspend fun getNonce(query: String): String {
        return try {
            val searchLandingUrl = "$mainUrl/search/?q=" + URLEncoder.encode(query, "UTF-8")
            val html = app.get(searchLandingUrl).text
            Regex("\"nonce\"\\s*:\\s*\"([a-f0-9]+)\"").find(html)?.groupValues?.getOrNull(1)
                ?: "25ccbcb8fb"
        } catch (e: Exception) {
            "25ccbcb8fb"
        }
    }

    // Confidence: high on the request shape (confirmed byte-for-byte from the decompile).
    // Medium on the `success` check: the decompiled bytecode literally reads
    // `if (!json.optBoolean("success"))` before parsing `data.results`, which would only
    // return results on a *failed* call. That inversion pattern (jadx mis-decompiling a
    // Kotlin `if/else` around a null-safe elvis-like check) shows up repeatedly in this file,
    // including an identical "success" check in load()'s AJAX handling, so this has been
    // translated as the sensible "on success, parse results" version.
    // TODO: if search ever silently returns nothing against a live site, this is the first
    // thing to double check by inspecting one raw AJAX response.
    override suspend fun search(query: String): List<SearchResponse> {
        val nonce = getNonce(query)
        val searchResults = ArrayList<SearchResponse>()

        try {
            val response = app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                ),
                data = mapOf(
                    "action" to "phoenix_search",
                    "nonce" to nonce,
                    "q" to query,
                    "type" to "all",
                    "genre" to "",
                    "status" to "",
                    "year" to "",
                    "season" to "",
                    "sort" to "relevance",
                    "page" to "1",
                    "per_page" to "25",
                    "dropdown" to "0"
                )
            )

            val json = JSONObject(response.text)
            if (json.optBoolean("success")) {
                val results = json.getJSONObject("data").getJSONArray("results")
                for (i in 0 until results.length()) {
                    val item = results.getJSONObject(i)
                    val titleAr = item.optString("title_ar")
                    val titleEn = item.optString("title_en")
                    val title = titleAr.ifEmpty { titleEn }
                    val href = item.optString("url")
                    val poster = item.optString("thumbnail_url")
                    val tvType = if (item.optString("item_type") == "movie") TvType.AnimeMovie else TvType.Anime

                    if (title.isNotEmpty() && href.isNotEmpty()) {
                        searchResults.add(
                            newAnimeSearchResponse(title, href, tvType) {
                                posterUrl = poster
                            }
                        )
                    }
                }
            }
            return searchResults
        } catch (e: Exception) {
            // Fallback: scrape the plain (non-AJAX) search results page directly.
            return try {
                val searchUrl = "$mainUrl/search/?q=" + URLEncoder.encode(query, "UTF-8")
                val doc = app.get(searchUrl).document
                doc.select("div.FJ-episode-wrap").mapNotNull { wrap ->
                    val title = wrap.selectFirst("a.FJ-Phoenix-Anastasia-EpCard-Name")?.text() ?: ""
                    val href = (wrap.selectFirst("a.FJ-episode-img-box") ?: wrap.selectFirst("a"))?.attr("href")
                    if (title.isNotEmpty() && !href.isNullOrEmpty()) {
                        newAnimeSearchResponse(title, href, TvType.Anime)
                    } else {
                        null
                    }
                }
            } catch (e2: Exception) {
                emptyList()
            }
        }
    }

    // Confidence: medium-high on the movie path and the two scraping/fallback layers
    // (selectors and fields taken directly from the decompiled bytecode). Lower confidence
    // on the exact "sample interpolation" gating condition - see the TODO further below.
    override suspend fun load(url: String): LoadResponse? {
        return try {
            val response = app.get(url, headers = customHeaders)
            val finalUrl = response.url
            val document = response.document

            val title = document.selectFirst("h1.FJ-Phoenix-Hero-Title")?.text()
                ?: document.selectFirst("h1.FJ-CC-Title")?.text()
                ?: ""
            val poster = document.selectFirst(".FJ-Phoenix-Hero-Poster img")?.attr("src")
            val description = document.selectFirst(".FJ-Phoenix-Desc-Full")?.text()
            val isMovie = finalUrl.contains("/movies/")

            if (isMovie) {
                val watchUrl = document.selectFirst("a.FJ-Btn-Watch")?.attr("href")
                    ?: "$finalUrl/watch"
                return newMovieLoadResponse(title, finalUrl, TvType.AnimeMovie, watchUrl) {
                    posterUrl = poster
                    plot = description
                }
            }

            val episodes = ArrayList<Episode>()
            val episodesPageUrl = finalUrl.removeSuffix("/") + "/episodes/"

            // Fallback used when the dedicated /episodes/ page can't be fetched/parsed at
            // all: read the numbered pagination "pills" straight off the show page itself.
            fun scrapeEpisodePillsFallback(): List<Episode> {
                return document.select("div.FJ-EpsGrid a").reversed().mapIndexedNotNull { index, pill ->
                    val epUrl = pill.attr("href")
                    if (epUrl.isBlank()) return@mapIndexedNotNull null
                    val epTitle = pill.attr("title").ifBlank { "الحلقة" }
                    val epNum = Regex("(\\d+)$").find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: (index + 1)
                    newEpisode(epUrl) {
                        name = epTitle
                        episode = epNum
                        season = 1
                        posterUrl = poster
                    }
                }
            }

            fun parseEpisodeCards(cards: Elements): List<Episode> {
                return cards.mapNotNull { item ->
                    val epTitleRaw = item.selectFirst(".FJ-Phoenix-Anastasia-EpCard-Name")?.text()
                        ?: return@mapNotNull null
                    val epUrl = item.attr("href")
                    val cleanTitle = Regex("\\s+").replace(epTitleRaw, " ").trim()
                    val epNum = Regex("(\\d+)$").find(cleanTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    newEpisode(epUrl) {
                        name = cleanTitle
                        episode = epNum
                        season = 1
                        posterUrl = poster
                    }
                }
            }

            try {
                val epResponse = app.get(episodesPageUrl, headers = customHeaders)
                val epDocument = epResponse.document
                val epHtml = epResponse.text

                // The show's /episodes/ page usually renders only a short preview strip of
                // episodes statically; the rest is loaded client-side via AJAX pagination.
                val firstGroup = parseEpisodeCards(epDocument.select("div#episodesGrid a.FJ-episode-wrap"))

                if (firstGroup.isNotEmpty()) {
                    episodes.addAll(firstGroup)

                    // The same page also embeds a signed token/sig pair (as inline JSON,
                    // e.g. from a wp_localize_script call) used to authenticate the
                    // "load more episodes" AJAX endpoint.
                    val token = Regex("\"token\"\\s*:\\s*\"([^\"]+)\"").find(epHtml)?.groupValues?.getOrNull(1)
                    val sig = Regex("\"sig\"\\s*:\\s*\"([^\"]+)\"").find(epHtml)?.groupValues?.getOrNull(1)
                    val paginationEl = epDocument.selectFirst("#pagination")
                        ?: epDocument.selectFirst(".FJ-Phoenix-Anastasia-Pagination-Wrap")
                    val totalPages = paginationEl?.attr("data-total")?.toIntOrNull() ?: 1

                    // TODO: this condition (`totalPages <= 1`) and the fact that only a
                    // single AJAX page is ever fetched (page=$totalPages, not a 1..totalPages
                    // loop) are preserved literally from the decompiled bytecode. It reads
                    // backwards - you'd expect to paginate when totalPages > 1 - but since
                    // totalPages defaults to 1 whenever the pagination element/attribute
                    // isn't found (which appears to be the common case), this branch is
                    // likely taken on most/all real pages anyway. Flagging in case this is
                    // actually a latent bug in the original plugin rather than a decompiler
                    // artifact; worth re-checking against a live multi-page show.
                    if (totalPages <= 1 && token != null && sig != null) {
                        try {
                            val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php?action=fj_get_episodes" +
                                "&token=" + URLEncoder.encode(token, "UTF-8") +
                                "&sig=$sig&page=$totalPages&sort=oldest&search="
                            val ajaxResponse = app.get(ajaxUrl, headers = customHeaders)
                            val json = JSONObject(ajaxResponse.text)

                            // Same "success" inversion note as in search().
                            if (json.optBoolean("success")) {
                                val html = json.getJSONObject("data").optString("html")
                                if (html.isNotEmpty()) {
                                    val lastPageDoc = Jsoup.parse(html)
                                    val lastGroup = parseEpisodeCards(lastPageDoc.select("a.FJ-episode-wrap"))

                                    if (lastGroup.isNotEmpty()) {
                                        // Neither the preview strip nor this single AJAX page
                                        // necessarily cover every episode in between, so the
                                        // plugin fills the gap by guessing sequential episode
                                        // URLs: it strips the trailing episode number off a
                                        // sample episode from the first group and increments
                                        // it for every number between the two groups.
                                        val sampleEp = firstGroup.first()
                                        val sampleTitle = sampleEp.name ?: ""
                                        val sampleUrl = sampleEp.data
                                        val titlePrefix = Regex("\\d+$").replace(sampleTitle, "")
                                        val urlPrefix = Regex("\\d+$").replace(sampleUrl, "")

                                        val startNum = (firstGroup.last().episode ?: firstGroup.size) + 1
                                        // 1000 is a magic fallback sentinel taken verbatim
                                        // from the decompiled bytecode for when the last
                                        // group's first episode has no parsed number.
                                        val endNum = (lastGroup.first().episode ?: 1000) - 1

                                        if (startNum <= endNum) {
                                            for (num in startNum..endNum) {
                                                episodes.add(
                                                    newEpisode(urlPrefix + num) {
                                                        name = titlePrefix + num
                                                        episode = num
                                                        season = 1
                                                        posterUrl = poster
                                                    }
                                                )
                                            }
                                        }
                                        episodes.addAll(lastGroup)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // Keep whatever episodes we already have from the preview strip.
                        }
                    }
                } else {
                    episodes.addAll(scrapeEpisodePillsFallback())
                }
            } catch (e: Exception) {
                episodes.addAll(scrapeEpisodePillsFallback())
            }

            newTvSeriesLoadResponse(title, finalUrl, TvType.Anime, episodes) {
                posterUrl = poster
                plot = description
            }
        } catch (e: Exception) {
            null
        }
    }

    // Confidence: medium. The overall shape (decode data-server -> base64 -> URL-decode ->
    // JSON {name,type,link} -> branch on type) is solid and confirmed directly from the
    // bytecode. The exact nesting of the iframe/Google-Drive/direct branches was badly
    // mangled by the decompiler (duplicated try/catch scaffolding made several branches
    // look like they were nested inside each other when they're almost certainly siblings
    // of a single `when (linkType)`), so this is reconstructed as the cleanest version that
    // preserves every concrete detail found (both Google Drive id regexes, the two
    // newExtractorLink referer values, and the loadExtractor() fallback).
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val serverLinks = document.select("a.server-link")

        serverLinks.forEach { sLink ->
            val rawData = sLink.attr("data-server")
            if (rawData.isBlank()) return@forEach

            try {
                val decodedBytes = Base64.decode(rawData, Base64.DEFAULT)
                val decodedStr = String(decodedBytes, Charsets.UTF_8)
                val unquotedJson = URLDecoder.decode(decodedStr, "UTF-8")
                val serverInfo = JSONObject(unquotedJson)

                // NOTE: the decompiled bytecode passes the *provider's* name ("anime-phoenix",
                // this.name) as the ExtractorLink source, and this per-server name only as the
                // display name - kept faithful to that rather than the more obvious-looking
                // swap.
                val serverName = serverInfo.optString("name", "Phoenix Server")
                val linkType = serverInfo.optString("type")
                val videoUrl = serverInfo.optString("link")
                if (videoUrl.isBlank()) return@forEach

                when (linkType) {
                    "iframe" -> {
                        var iframeUrl = videoUrl
                        if (iframeUrl.contains("<iframe")) {
                            iframeUrl = Jsoup.parse(iframeUrl).selectFirst("iframe")?.attr("src") ?: ""
                        }
                        if (iframeUrl.isBlank()) return@forEach

                        if (iframeUrl.contains("drive.google.com", ignoreCase = true)) {
                            val fileId = Regex("/file/d/([0-9A-Za-z_-]{10,})").find(iframeUrl)
                                ?.groupValues?.getOrNull(1)
                                ?: Regex("[?&]id=([0-9A-Za-z_-]{10,})").find(iframeUrl)
                                    ?.groupValues?.getOrNull(1)

                            if (!fileId.isNullOrBlank()) {
                                val directDriveUrl =
                                    "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
                                callback(
                                    newExtractorLink(this@AnimePhoenixProvider.name, "$serverName (GDrive Direct)", directDriveUrl) {
                                        this.referer = "https://drive.google.com/"
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                            } else {
                                loadExtractor(iframeUrl, subtitleCallback, callback)
                            }
                        } else {
                            loadExtractor(iframeUrl, subtitleCallback, callback)
                        }
                    }
                    "direct" -> {
                        callback(
                            newExtractorLink(this@AnimePhoenixProvider.name, serverName, videoUrl) {
                                this.referer = mainUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                    else -> {
                        loadExtractor(videoUrl, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                // Skip this server and keep processing the rest.
            }
        }

        return true
    }
}
