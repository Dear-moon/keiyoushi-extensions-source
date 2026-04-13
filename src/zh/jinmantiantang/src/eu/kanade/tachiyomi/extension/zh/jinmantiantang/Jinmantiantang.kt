package eu.kanade.tachiyomi.extension.zh.jinmantiantang

import android.util.Base64
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.randomua.addRandomUAPreference
import keiyoushi.lib.randomua.setRandomUserAgent
import keiyoushi.utils.getPreferences
import keiyoushi.utils.tryParse
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale

class Jinmantiantang :
    ParsedHttpSource(),
    ConfigurableSource {

    override val lang: String = "zh"
    override val name: String = "禁漫天堂"
    override val supportsLatest: Boolean = true

    private val preferences = getPreferences { preferenceMigration() }

    override val baseUrl: String = "https://" + preferences.baseUrl

    private val updateUrlInterceptor = UpdateUrlInterceptor(preferences)

    // 自动登录开关的 key
    private val autoLoginEnabled: Boolean
        get() = preferences.getBoolean(AUTO_LOGIN_PREF, true)

    // 认证拦截器：自动登录 + 添加 Cookie
    private inner class AuthInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url.toString()

            // 判断是否需要登录的请求（收藏夹等）
            val requiresAuth = url.contains("/user/") || url.contains("/favorite/")

            if (requiresAuth && autoLoginEnabled) {
                var cookies = preferences.getString(COOKIE_PREF, "")
                if (cookies.isNullOrEmpty()) {
                    // 自动登录
                    val username = preferences.getString(USERNAME_PREF, "")!!
                    val password = preferences.getString(PASSWORD_PREF, "")!!
                    if (username.isNotBlank() && password.isNotBlank()) {
                        cookies = performLogin(username, password)
                        if (cookies != null) {
                            preferences.edit().putString(COOKIE_PREF, cookies).apply()
                        }
                    }
                }
                if (!cookies.isNullOrEmpty()) {
                    val newRequest = request.newBuilder()
                        .header("Cookie", cookies)
                        .build()
                    val response = chain.proceed(newRequest)
                    // 如果返回 401/403，可能是 Cookie 失效，清除并重试一次
                    if (response.code == 401 || response.code == 403) {
                        response.close()
                        preferences.edit().remove(COOKIE_PREF).apply()
                        val newCookies = performLogin(
                            preferences.getString(USERNAME_PREF, "")!!,
                            preferences.getString(PASSWORD_PREF, "")!!,
                        )
                        if (newCookies != null) {
                            preferences.edit().putString(COOKIE_PREF, newCookies).apply()
                            val retryRequest = request.newBuilder()
                                .header("Cookie", newCookies)
                                .build()
                            return chain.proceed(retryRequest)
                        }
                    }
                    return response
                }
            }
            // 不需要登录或自动登录关闭，直接放行
            return chain.proceed(request)
        }

        private fun performLogin(username: String, password: String): String? {
            val loginUrl = "$baseUrl/login"
            val formBody = FormBody.Builder()
                .add("username", username)
                .add("password", password)
                .add("id_remember", "on")
                .add("login_remember", "on")
                .add("submit_login", "")
                .build()
            val request = POST(loginUrl, headers, formBody)
            // 使用临时 client 避免递归
            val tempClient = client.newBuilder()
                .interceptors().filterNot { it is AuthInterceptor }
                .let { OkHttpClient.Builder().apply { interceptors().addAll(it) } }
                .build()
            val response = tempClient.newCall(request).execute()
            return if (response.isSuccessful) {
                extractCookiesFromResponse(response)
            } else {
                null
            }
        }

        private fun extractCookiesFromResponse(response: Response): String {
            val cookies = mutableListOf<String>()
            for (header in response.headers("Set-Cookie")) {
                val cookiePart = header.substringBefore(";")
                cookies.add(cookiePart)
            }
            return cookies.joinToString("; ")
        }
    }

    // 处理URL请求
    override val client: OkHttpClient = network.cloudflareClient
        .newBuilder()
        .rateLimitHost(
            baseUrl.toHttpUrl(),
            preferences.getString(MAINSITE_RATELIMIT_PREF, MAINSITE_RATELIMIT_PREF_DEFAULT)!!.toInt(),
            preferences.getString(MAINSITE_RATELIMIT_PERIOD, MAINSITE_RATELIMIT_PERIOD_DEFAULT)!!.toLong(),
        )
        .apply { interceptors().add(0, updateUrlInterceptor) }
        .addInterceptor(AuthInterceptor()) // 使用认证拦截器
        .addInterceptor(ScrambledImageInterceptor)
        .build()

    // 添加额外的header增加规避Cloudflare可能性
    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .setRandomUserAgent()

    // 点击量排序(人气)
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/albums?o=mv&page=$page", headers)

    override fun popularMangaNextPageSelector(): String = "a.prevnext"
    override fun popularMangaSelector(): String = "div.list-col > div.p-b-15:not([data-group])"

    private fun List<SManga>.filterGenre(): List<SManga> {
        val removedGenres = preferences.getString(BLOCK_PREF, "")!!.substringBefore("//").trim()
        if (removedGenres.isEmpty()) return this
        val removedList = removedGenres.lowercase().split(' ')
        return this.filterNot { manga ->
            manga.genre.orEmpty().lowercase().split(", ").any { removedList.contains(it) }
        }
    }

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        val children = element.children()
        if (children[0].tagName() == "a") children.removeFirst()
        title = children[1].text()
        setUrlWithoutDomain(children[0].selectFirst("a")!!.attr("href"))
        val img = children[0].selectFirst("img")!!
        thumbnail_url = img.extractThumbnailUrl().substringBeforeLast('?')
        author = children[2].select("a").joinToString(", ") { it.text() }
        genre = children[3].select("a").joinToString(", ") { it.text() }
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val page = super.popularMangaParse(response)
        return MangasPage(page.mangas.filterGenre(), page.hasNextPage)
    }

    // 最新排序
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/albums?o=mr&page=$page", headers)

    override fun latestUpdatesNextPageSelector(): String = popularMangaNextPageSelector()
    override fun latestUpdatesSelector(): String = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val page = super.latestUpdatesParse(response)
        return MangasPage(page.mangas.filterGenre(), page.hasNextPage)
    }

    // For JinmantiantangUrlActivity
    private fun searchMangaByIdRequest(id: String) = GET("$baseUrl/album/$id", headers)

    private fun searchMangaByIdParse(response: Response, id: String): MangasPage {
        val sManga = mangaDetailsParse(response)
        sManga.url = "/album/$id/"
        return MangasPage(listOf(sManga), false)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = if (query.startsWith(PREFIX_ID_SEARCH_NO_COLON, true) || query.toIntOrNull() != null) {
        val id = query.removePrefix(PREFIX_ID_SEARCH_NO_COLON).removePrefix(":")
        client.newCall(searchMangaByIdRequest(id))
            .asObservableSuccess()
            .map { response -> searchMangaByIdParse(response, id) }
    } else {
        super.fetchSearchManga(page, query, filters)
    }

    // 查询信息
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var params = filters.filterIsInstance<UriPartFilter>().joinToString("") { it.toUriPart() }

        val url = if (query != "" && !query.contains("-")) {
            var newQuery = query.replace("+", "%2B").replace(" ", "+")
            params = params.substringAfter("?")
            if (params.contains("search_query")) {
                val keyword = params.substringBefore("&").substringAfter("=")
                newQuery = "$newQuery+%2B$keyword"
                params = params.substringAfter("&")
            }
            "$baseUrl/search/photos?search_query=$newQuery&page=$page&$params"
        } else {
            params = if (params == "") "/albums?" else params
            if (query == "") {
                "$baseUrl$params&page=$page"
            } else {
                val removedGenres = query.split(" ").filter { it.startsWith("-") }.joinToString("+") { it.removePrefix("-") }
                "$baseUrl$params&page=$page&screen=$removedGenres"
            }
        }
        return GET(url, headers)
    }

    override fun searchMangaNextPageSelector(): String = popularMangaNextPageSelector()
    override fun searchMangaSelector(): String = popularMangaSelector()
    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun searchMangaParse(response: Response): MangasPage {
        val page = super.searchMangaParse(response)
        return MangasPage(page.mangas.filterGenre(), page.hasNextPage)
    }

    // 漫画详情
    private fun mangaDetailsResolve(response: Response): Document {
        val document = response.asJsoup()
        val scripts =
            document.select("#wrapper > script:containsData(function base64DecodeUtf8):containsData(document.write(html))")

        for (script in scripts) {
            val jsCode = script.html().trim()

            jsCode.lines().forEach { line ->
                val trimmedLine = line.trim()
                if (trimmedLine.startsWith("const html") || trimmedLine.startsWith("let html") || trimmedLine.startsWith("var html")) {
                    val start = trimmedLine.indexOf("base64DecodeUtf8(\"") + "base64DecodeUtf8(\"".length
                    val end = trimmedLine.indexOf("\");", start)
                    if (start > 0 && end > start) {
                        val html = Base64.decode(trimmedLine.substring(start, end), Base64.DEFAULT)
                        document.body().append(String(html))
                    }
                }
            }
        }
        return document
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = mangaDetailsResolve(response)
        return mangaDetailsParse(document)
    }

    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("h1")!!.text()
        thumbnail_url = document.selectFirst(".thumb-overlay > img")!!.extractThumbnailUrl().substringBeforeLast('.') + "_3x4.jpg"
        author = selectAuthor(document)
        genre = selectDetailsStatusAndGenre(document, 0).trim().split(" ").joinToString(", ")
        status = selectDetailsStatusAndGenre(document, 1).trim().toInt()
        description = document.selectFirst("#intro-block .p-t-5.p-b-5")!!.text().substringAfter("敘述：").trim()
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl${manga.url}"

    private fun Element.extractThumbnailUrl(): String = when {
        hasAttr("data-original") -> attr("data-original")
        hasAttr("src") -> attr("src")
        hasAttr("data-cfsrc") -> attr("data-cfsrc")
        else -> ""
    }

    private fun selectAuthor(document: Document): String {
        val element = document.select("div.panel-body div.tag-block")[3]
        return element.select(".btn-primary").joinToString { it.text() }
    }

    private fun selectDetailsStatusAndGenre(document: Document, index: Int): String {
        var status = "0"
        var genre = ""
        if (document.select("span[itemprop=genre] a").size == 0) {
            return if (index == 1) status else genre
        }
        val elements: Elements = document.select("span[itemprop=genre]").first()!!.select("a")
        for (value in elements) {
            when (val vote: String = value.select("a").text()) {
                "連載中" -> status = "1"
                "完結" -> status = "2"
                else -> genre = "$genre$vote "
            }
        }
        return if (index == 1) status else genre
    }

    // 漫画章节信息
    override fun chapterListSelector(): String = "div[id=episode-block] a[href^=/photo/]"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)

    override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        url = element.select("a").attr("href")
        name = element.select("a li h3").first()!!.ownText()
        date_upload = dateFormat.tryParse(element.select("a li span.hidden-xs").text().trim())
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = mangaDetailsResolve(response)
        if (document.select("div[id=episode-block] a li").size == 0) {
            val singleChapter = SChapter.create().apply {
                name = "单章节"
                url = document.select("#album_photo_cover > div.thumb-overlay > a").attr("href")
                date_upload = dateFormat.tryParse(document.select("[itemprop=datePublished]").last()!!.attr("content"))
            }
            return listOf(singleChapter)
        }
        return document.select(chapterListSelector()).map { chapterFromElement(it) }.reversed()
    }

    // 漫画图片信息
    override fun pageListParse(document: Document): List<Page> {
        tailrec fun internalParse(document: Document, pages: MutableList<Page>): List<Page> {
            val elements = document.select("div[class=center scramble-page spnotice_chk][id*=0]")
            for (element in elements) {
                pages.apply {
                    if (element.select("div[class=center scramble-page spnotice_chk][id*=0] img").attr("src").indexOf("blank.jpg") >= 0 ||
                        element.select("div[class=center scramble-page spnotice_chk][id*=0] img").attr("data-cfsrc").indexOf("blank.jpg") >= 0
                    ) {
                        add(Page(size, "", element.select("div[class=center scramble-page spnotice_chk][id*=0] img").attr("data-original").split("\\?")[0]))
                    } else {
                        add(Page(size, "", element.select("div[class=center scramble-page spnotice_chk][id*=0] img").attr("src").split("\\?")[0]))
                    }
                }
            }
            return when (val next = document.select("a.prevnext").firstOrNull()) {
                null -> pages
                else -> internalParse(client.newCall(GET(next.attr("abs:href"), headers)).execute().asJsoup(), pages)
            }
        }
        return internalParse(document, mutableListOf())
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()

    // Filters
    override fun getFilterList() = FilterList(
        CategoryGroup(),
        SortFilter(),
        TimeFilter(),
        TypeFilter(),
    )

    private inner class CategoryGroup :
        UriPartFilter(
            "按类型",
            arrayOf(
                Pair("全部", "/albums?"),
                Pair("其他", "/albums/another?"),
                Pair("收藏夹", "/user/${preferences.getString(USERNAME_PREF, "")}/favorite/albums?"),
                Pair("同人", "/albums/doujin?"),
                Pair("韩漫", "/albums/hanman?"),
                Pair("美漫", "/albums/meiman?"),
                Pair("短篇", "/albums/short?"),
                Pair("单本", "/albums/single?"),
                Pair("汉化", "/albums/doujin/sub/chinese?"),
                Pair("日语", "/albums/doujin/sub/japanese?"),
                Pair("Cosplay", "/albums/doujin/sub/cosplay?"),
                Pair("CG图集", "/albums/doujin/sub/CG?"),
                Pair("P站", "/search/photos?search_query=PIXIV&"),
                Pair("3D", "/search/photos?search_query=3D&"),
                Pair("剧情", "/search/photos?search_query=劇情&"),
                Pair("校园", "/search/photos?search_query=校園&"),
                Pair("纯爱", "/search/photos?search_query=純愛&"),
                Pair("人妻", "/search/photos?search_query=人妻&"),
                Pair("师生", "/search/photos?search_query=師生&"),
                Pair("乱伦", "/search/photos?search_query=亂倫&"),
                Pair("近亲", "/search/photos?search_query=近親&"),
                Pair("百合", "/search/photos?search_query=百合&"),
                Pair("男同", "/search/photos?search_query=YAOI&"),
                Pair("性转", "/search/photos?search_query=性轉&"),
                Pair("NTR", "/search/photos?search_query=NTR&"),
                Pair("伪娘", "/search/photos?search_query=偽娘&"),
                Pair("痴女", "/search/photos?search_query=癡女&"),
                Pair("全彩", "/search/photos?search_query=全彩&"),
                Pair("女性向", "/search/photos?search_query=女性向&"),
                Pair("萝莉", "/search/photos?search_query=蘿莉&"),
                Pair("御姐", "/search/photos?search_query=御姐&"),
                Pair("熟女", "/search/photos?search_query=熟女&"),
                Pair("正太", "/search/photos?search_query=正太&"),
                Pair("巨乳", "/search/photos?search_query=巨乳&"),
                Pair("贫乳", "/search/photos?search_query=貧乳&"),
                Pair("女王", "/search/photos?search_query=女王&"),
                Pair("教师", "/search/photos?search_query=教師&"),
                Pair("女仆", "/search/photos?search_query=女僕&"),
                Pair("护士", "/search/photos?search_query=護士&"),
                Pair("泳裝", "/search/photos?search_query=泳裝&"),
                Pair("眼镜", "/search/photos?search_query=眼鏡&"),
                Pair("丝袜", "/search/photos?search_query=絲襪&"),
                Pair("连裤袜", "/search/photos?search_query=連褲襪&"),
                Pair("制服", "/search/photos?search_query=制服&"),
                Pair("兔女郎", "/search/photos?search_query=兔女郎&"),
                Pair("群交", "/search/photos?search_query=群交&"),
                Pair("足交", "/search/photos?search_query=足交&"),
                Pair("SM", "/search/photos?search_query=SM&"),
                Pair("肛交", "/search/photos?search_query=肛交&"),
                Pair("阿黑颜", "/search/photos?search_query=阿黑顏&"),
                Pair("药物", "/search/photos?search_query=藥物&"),
                Pair("扶他", "/search/photos?search_query=扶他&"),
                Pair("调教", "/search/photos?search_query=調教&"),
                Pair("野外", "/search/photos?search_query=野外&"),
                Pair("露出", "/search/photos?search_query=露出&"),
                Pair("催眠", "/search/photos?search_query=催眠&"),
                Pair("自慰", "/search/photos?search_query=自慰&"),
                Pair("触手", "/search/photos?search_query=觸手&"),
                Pair("兽交", "/search/photos?search_query=獸交&"),
                Pair("亚人", "/search/photos?search_query=亞人&"),
                Pair("魔物", "/search/photos?search_query=魔物&"),
                Pair("CG集", "/search/photos?search_query=CG集&"),
                Pair("重口", "/search/photos?search_query=重口&"),
                Pair("猎奇", "/search/photos?search_query=獵奇&"),
                Pair("非H", "/search/photos?search_query=非H&"),
                Pair("血腥", "/search/photos?search_query=血腥&"),
                Pair("暴力", "/search/photos?search_query=暴力&"),
                Pair("血腥暴力", "/search/photos?search_query=血腥暴力&"),
            ),
        )

    private class SortFilter :
        UriPartFilter(
            "排序",
            arrayOf(
                Pair("最新", "o=mr&"),
                Pair("最多浏览", "o=mv&"),
                Pair("最多爱心", "o=tf&"),
                Pair("最多图片", "o=mp&"),
            ),
        )

    private class TimeFilter :
        UriPartFilter(
            "时间",
            arrayOf(
                Pair("全部", "t=a&"),
                Pair("今天", "t=t&"),
                Pair("这周", "t=w&"),
                Pair("本月", "t=m&"),
            ),
        )

    private class TypeFilter :
        UriPartFilter(
            "搜索范围",
            arrayOf(
                Pair("站内搜索", "main_tag=0"),
                Pair("作品", "main_tag=1"),
                Pair("作者", "main_tag=2"),
                Pair("标签", "main_tag=3"),
                Pair("登场人物", "main_tag=4"),
            ),
        )

    private open class UriPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
        defaultValue: Int = 0,
    ) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray(), defaultValue) {
        open fun toUriPart() = vals[state].second
    }

    // ---------- 设置界面 ----------
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val context = screen.context

        // 自动登录开关
        SwitchPreferenceCompat(context).apply {
            key = AUTO_LOGIN_PREF
            title = "自动登录"
            summary = "开启后，访问需要登录的页面（如收藏夹）时会自动使用下方账号登录"
            setDefaultValue(true)
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue == false) {
                    // 关闭开关时清除 Cookie
                    preferences.edit().remove(COOKIE_PREF).apply()
                    Toast.makeText(context, "已清除登录状态", Toast.LENGTH_SHORT).show()
                } else {
                    // 开启开关时，如果有用户名密码，可以尝试立即登录（可选）
                    val username = preferences.getString(USERNAME_PREF, "")!!
                    val password = preferences.getString(PASSWORD_PREF, "")!!
                    if (username.isNotBlank() && password.isNotBlank()) {
                        preferences.edit().remove(COOKIE_PREF).apply()
                        Toast.makeText(context, "已开启自动登录，下次访问需登录页面时将自动登录", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "请先填写用户名和密码", Toast.LENGTH_SHORT).show()
                    }
                }
                true
            }
        }.let(screen::addPreference)

        // 用户名
        EditTextPreference(context).apply {
            key = USERNAME_PREF
            title = "用户名"
            summary = "用于自动登录"
            setDefaultValue("")
        }.let(screen::addPreference)

        // 密码
        EditTextPreference(context).apply {
            key = PASSWORD_PREF
            title = "密码"
            summary = "仅用于获取 Cookie，不会明文存储"
            setDefaultValue("")
        }.let(screen::addPreference)

        // 原有的其他设置项（镜像、屏蔽词等）
        getPreferenceList(context, preferences, updateUrlInterceptor.isUpdated).forEach(screen::addPreference)
        screen.addRandomUAPreference()
    }

    companion object {
        private const val PREFIX_ID_SEARCH_NO_COLON = "JM"
        const val PREFIX_ID_SEARCH = "$PREFIX_ID_SEARCH_NO_COLON:"
        private const val USERNAME_PREF = "username"
        private const val PASSWORD_PREF = "password"
        private const val COOKIE_PREF = "auth_cookie"
        private const val AUTO_LOGIN_PREF = "auto_login"
    }
}
