package com.jocmp.capy.accounts.miniflux

import com.jocmp.capy.AccountDelegate
import com.jocmp.capy.AccountPreferences
import com.jocmp.capy.ArticleFilter
import com.jocmp.capy.InMemoryDataStore
import com.jocmp.capy.InMemoryDatabaseProvider
import com.jocmp.capy.accounts.AddFeedResult
import com.jocmp.capy.common.TimeHelpers.nowUTC
import com.jocmp.capy.common.UnauthorizedError
import com.jocmp.capy.db.Database
import com.jocmp.capy.fixtures.ArticleFixture
import com.jocmp.capy.fixtures.FeedFixture
import com.jocmp.capy.persistence.ArticleRecords
import com.jocmp.capy.persistence.EnclosureRecords
import com.jocmp.minifluxclient.Category
import com.jocmp.minifluxclient.CreateCategoryRequest
import com.jocmp.minifluxclient.CreateFeedRequest
import com.jocmp.minifluxclient.CreateFeedResponse
import com.jocmp.minifluxclient.Enclosure
import com.jocmp.minifluxclient.Entry
import com.jocmp.minifluxclient.EntryResultSet
import com.jocmp.minifluxclient.EntryStatus
import com.jocmp.minifluxclient.Feed
import com.jocmp.minifluxclient.Icon
import com.jocmp.minifluxclient.IconData
import com.jocmp.minifluxclient.Miniflux
import com.jocmp.minifluxclient.UpdateEntriesRequest
import com.jocmp.minifluxclient.UpdateFeedRequest
import com.jocmp.capy.logging.CapyLog
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.Response
import java.net.SocketTimeoutException
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MinifluxAccountDelegateTest {
    private val accountID = "777"
    private lateinit var database: Database
    private lateinit var miniflux: Miniflux
    private lateinit var feedFixture: FeedFixture
    private lateinit var delegate: AccountDelegate

    private val category = Category(
        id = 1,
        title = "Tech",
        user_id = 100
    )

    private val icon = Icon(
        feed_id = 2,
        icon_id = 1
    )

    private val arsTechnicaFeed = Feed(
        id = 2,
        user_id = 100,
        title = "Ars Technica",
        site_url = "https://arstechnica.com",
        feed_url = "https://feeds.arstechnica.com/arstechnica/index",
        checked_at = "2024-02-23T17:47:45.708056Z",
        etag_header = null,
        last_modified_header = null,
        parsing_error_message = null,
        parsing_error_count = 0,
        scraper_rules = null,
        rewrite_rules = null,
        crawler = false,
        blocklist_rules = null,
        keeplist_rules = null,
        user_agent = null,
        username = null,
        password = null,
        disabled = false,
        ignore_http_cache = false,
        fetch_via_proxy = false,
        category = category,
        icon = icon
    )

    private val vergeFeed = Feed(
        id = 5,
        user_id = 100,
        title = "The Verge",
        site_url = "https://theverge.com",
        feed_url = "https://www.theverge.com/rss/index.xml",
        checked_at = "2025-02-09T14:02:28.994289Z",
        etag_header = null,
        last_modified_header = null,
        parsing_error_message = null,
        parsing_error_count = 0,
        scraper_rules = null,
        rewrite_rules = null,
        crawler = false,
        blocklist_rules = null,
        keeplist_rules = null,
        user_agent = null,
        username = null,
        password = null,
        disabled = false,
        ignore_http_cache = false,
        fetch_via_proxy = false,
        category = null,
        icon = null
    )

    private val feeds = listOf(arsTechnicaFeed, vergeFeed)
    private val categories = listOf(category)

    private val arsTechnicaArticle = Entry(
        id = 4375836222,
        user_id = 100,
        feed_id = 2,
        status = EntryStatus.UNREAD,
        hash = "abc123",
        title = "Reddit admits more moderator protests could hurt its business",
        url = "https://arstechnica.com/?p=2005526",
        comments_url = null,
        published_at = "2024-02-23T17:42:38.000000Z",
        created_at = "2024-02-23T17:47:45.708056Z",
        changed_at = "2024-02-23T17:47:45.708056Z",
        content = "<p>Reddit filed to go public on Thursday (PDF), revealing various details of the social media company's inner workings.</p>",
        author = "Scharon Harding",
        share_code = null,
        starred = false,
        reading_time = 5,
        enclosures = null,
        feed = null
    )

    private val vergeArticle = Entry(
        id = 4718104685,
        user_id = 100,
        feed_id = 5,
        status = EntryStatus.READ,
        hash = "def456",
        title = "Amazfit Active 2 review: outsized bang for your buck",
        url = "https://www.theverge.com/smartwatch-review/608342/amazfit-active-2-review",
        comments_url = null,
        published_at = "2025-02-09T14:00:00.000000Z",
        created_at = "2025-02-09T14:02:28.994289Z",
        changed_at = "2025-02-09T14:02:28.994289Z",
        content = "<p>This $130 smartwatch certainly doesn't look it.</p>",
        author = "Victoria Song",
        share_code = null,
        starred = false,
        reading_time = 8,
        enclosures = listOf(
            Enclosure(
                id = 1,
                user_id = 100,
                entry_id = 4718104685,
                url = "https://www.podtrac.com/pts/redirect.mp3/pdst.fm/e/chtbl.com/track/524GE/traffic.megaphone.fm/VMP2413819050.mp3",
                mime_type = "audio/mpeg",
                size = 45678900
            )
        ),
        feed = null
    )

    private val entries = listOf(arsTechnicaArticle, vergeArticle)

    @BeforeTest
    fun setup() {
        mockkObject(CapyLog)
        every { CapyLog.warn(any(), any()) }.returns(Unit)
        every { CapyLog.info(any(), any()) }.returns(Unit)

        database = InMemoryDatabaseProvider.build(accountID)
        feedFixture = FeedFixture(database)
        miniflux = mockk()
        delegate = MinifluxAccountDelegate(database, miniflux, AccountPreferences(InMemoryDataStore()))
    }

    @Test
    fun refresh_updatesEntries() = runTest {
        coEvery { miniflux.feeds() }.returns(Response.success(feeds))
        coEvery { miniflux.icon(1) }.returns(
            Response.success(IconData(id = 1, data = "image/png;base64,abc", mime_type = "image/png"))
        )
        coEvery { miniflux.entries(starred = true, limit = 250, offset = 0) }.returns(
            Response.success(
                EntryResultSet(
                    total = 0,
                    entries = emptyList()
                )
            )
        )
        coEvery { miniflux.entries(status = EntryStatus.UNREAD.value, limit = 250, offset = 0) }.returns(
            Response.success(
                EntryResultSet(
                    total = 1,
                    entries = listOf(arsTechnicaArticle)
                )
            )
        )
        coEvery {
            miniflux.entries(
                limit = 250,
                offset = 0,
                order = "published_at",
                direction = "desc",
                changedAfter = null,
            )
        }.returns(
            Response.success(
                EntryResultSet(
                    total = 2,
                    entries = entries
                )
            )
        )

        delegate.refresh(ArticleFilter.default())

        val articles = database
            .articlesQueries
            .countAll(read = false, starred = false)
            .executeAsList()

        val taggedNames = database
            .feedsQueries
            .tagged()
            .executeAsList()
            .map { it.name }

        val feedsInDb = database
            .feedsQueries
            .all()
            .executeAsList()

        assertEquals(expected = 2, actual = feedsInDb.size)
        assertEquals(expected = listOf(null, "Tech"), actual = taggedNames.sortedWith(nullsFirst(naturalOrder())))
        assertEquals(expected = 1, actual = articles.size)

        val enclosures = EnclosureRecords(database).findByArticle(vergeArticle.id.toString())
        assertEquals(expected = 1, actual = enclosures.size)
    }

    @Test
    fun refresh_skipsArticlesPastAutoDeleteCutoff() = runTest {
        val starredReadArticle = vergeArticle.copy(id = 4718104686, starred = true)

        coEvery { miniflux.feeds() }.returns(Response.success(feeds))
        coEvery { miniflux.icon(1) }.returns(
            Response.success(IconData(id = 1, data = "image/png;base64,abc", mime_type = "image/png"))
        )
        coEvery { miniflux.entries(starred = true, limit = 250, offset = 0) }.returns(
            Response.success(EntryResultSet(total = 1, entries = listOf(starredReadArticle)))
        )
        coEvery { miniflux.entries(status = EntryStatus.UNREAD.value, limit = 250, offset = 0) }.returns(
            Response.success(EntryResultSet(total = 1, entries = listOf(arsTechnicaArticle)))
        )
        coEvery {
            miniflux.entries(
                limit = 250,
                offset = 0,
                order = "published_at",
                direction = "desc",
                changedAfter = null,
            )
        }.returns(
            Response.success(
                EntryResultSet(
                    total = 3,
                    entries = listOf(arsTechnicaArticle, vergeArticle, starredReadArticle)
                )
            )
        )

        delegate.refresh(ArticleFilter.default(), cutoffDate = nowUTC())

        val articleRecords = ArticleRecords(database)

        assertNotNull(articleRecords.find(arsTechnicaArticle.id.toString()))
        assertNotNull(articleRecords.find(starredReadArticle.id.toString()))
        assertNull(articleRecords.find(vergeArticle.id.toString()))
    }

    @Test
    fun refresh_IOException() = runTest {
        val networkError = SocketTimeoutException("Network timeout")
        coEvery { miniflux.feeds() }.throws(networkError)

        val result = delegate.refresh(ArticleFilter.default())

        assertEquals(result, Result.failure(networkError))
    }

    @Test
    fun refresh_fetchesMissingUnreadEntries() = runTest {
        coEvery { miniflux.feeds() }.returns(Response.success(feeds))
        coEvery { miniflux.icon(1) }.returns(
            Response.success(IconData(id = 1, data = "image/png;base64,abc", mime_type = "image/png"))
        )
        coEvery { miniflux.entries(starred = true, limit = 250, offset = 0) }.returns(
            Response.success(EntryResultSet(total = 0, entries = emptyList()))
        )
        coEvery { miniflux.entries(status = EntryStatus.UNREAD.value, limit = 250, offset = 0) }.returns(
            Response.success(EntryResultSet(total = 1, entries = listOf(arsTechnicaArticle)))
        )
        coEvery {
            miniflux.entries(
                limit = 250,
                offset = 0,
                order = "published_at",
                direction = "desc",
                changedAfter = null,
            )
        }.returns(Response.success(EntryResultSet(total = 0, entries = emptyList())))
        coEvery { miniflux.entry(arsTechnicaArticle.id) }.returns(Response.success(arsTechnicaArticle))

        delegate.refresh(ArticleFilter.default())

        val article = ArticleRecords(database).find(arsTechnicaArticle.id.toString())

        assertNotNull(article)
        assertEquals(expected = false, actual = article.read)
    }

    @Test
    fun refresh_pagesFullEntryListingByEntriesReceived() = runTest {
        val unread = (1L..5L).map { arsTechnicaArticle.copy(id = it, hash = "hash$it") }

        coEvery { miniflux.feeds() }.returns(Response.success(feeds))
        coEvery { miniflux.icon(1) }.returns(
            Response.success(IconData(id = 1, data = "image/png;base64,abc", mime_type = "image/png"))
        )
        coEvery { miniflux.entries(starred = true, limit = 250, offset = 0) }.returns(
            Response.success(EntryResultSet(total = 0, entries = emptyList()))
        )
        listOf(0, 2, 4).forEach { offset ->
            coEvery {
                miniflux.entries(status = EntryStatus.UNREAD.value, limit = 250, offset = offset)
            }.returns(
                Response.success(
                    EntryResultSet(total = 5, entries = unread.drop(offset).take(2))
                )
            )
        }
        coEvery {
            miniflux.entries(
                limit = 250,
                offset = 0,
                order = "published_at",
                direction = "desc",
                changedAfter = null,
            )
        }.returns(Response.success(EntryResultSet(total = 5, entries = unread)))

        delegate.refresh(ArticleFilter.default())

        coVerifyOrder {
            miniflux.entries(status = EntryStatus.UNREAD.value, limit = 250, offset = 0)
            miniflux.entries(status = EntryStatus.UNREAD.value, limit = 250, offset = 2)
            miniflux.entries(status = EntryStatus.UNREAD.value, limit = 250, offset = 4)
        }

        val records = ArticleRecords(database)
        unread.forEach { entry ->
            assertEquals(expected = false, actual = records.find(entry.id.toString())?.read)
        }
    }

    @Test
    fun refresh_overlapsChangedAfterWithLastRefresh() = runTest {
        val preferences = AccountPreferences(InMemoryDataStore())
        val lastRefreshedAt = nowUTC().minusHours(1).toEpochSecond()
        preferences.lastRefreshedAt.set(lastRefreshedAt)
        delegate = MinifluxAccountDelegate(database, miniflux, preferences)

        coEvery { miniflux.feeds() }.returns(Response.success(feeds))
        coEvery { miniflux.icon(1) }.returns(
            Response.success(IconData(id = 1, data = "image/png;base64,abc", mime_type = "image/png"))
        )
        coEvery { miniflux.entries(starred = true, limit = 250, offset = 0) }.returns(
            Response.success(EntryResultSet(total = 0, entries = emptyList()))
        )
        coEvery { miniflux.entries(status = EntryStatus.UNREAD.value, limit = 250, offset = 0) }.returns(
            Response.success(EntryResultSet(total = 0, entries = emptyList()))
        )
        coEvery {
            miniflux.entries(
                limit = 250,
                offset = 0,
                order = "published_at",
                direction = "desc",
                changedAfter = lastRefreshedAt - 300,
            )
        }.returns(Response.success(EntryResultSet(total = 0, entries = emptyList())))

        val startedAt = nowUTC().toEpochSecond()
        val result = delegate.refresh(ArticleFilter.default())
        val finishedAt = nowUTC().toEpochSecond()

        assertTrue(result.isSuccess)
        assertTrue(preferences.lastRefreshedAt.get() in startedAt..finishedAt)
    }

    @Test
    fun refresh_failedEntriesResponse() = runTest {
        val preferences = AccountPreferences(InMemoryDataStore())
        delegate = MinifluxAccountDelegate(database, miniflux, preferences)
        val existingArticle = ArticleFixture(database).create(
            feed = feedFixture.create(feedID = arsTechnicaFeed.id.toString()),
            read = false,
        )

        coEvery { miniflux.feeds() }.returns(Response.success(feeds))
        coEvery { miniflux.icon(1) }.returns(
            Response.success(IconData(id = 1, data = "image/png;base64,abc", mime_type = "image/png"))
        )
        coEvery { miniflux.entries(starred = true, limit = 250, offset = 0) }.returns(
            Response.success(EntryResultSet(total = 0, entries = emptyList()))
        )
        coEvery { miniflux.entries(status = EntryStatus.UNREAD.value, limit = 250, offset = 0) }.returns(
            Response.error(502, "Bad Gateway".toResponseBody())
        )

        val result = delegate.refresh(ArticleFilter.default())

        assertTrue(result.isFailure)
        assertEquals(expected = 0L, actual = preferences.lastRefreshedAt.get())
        assertEquals(
            expected = false,
            actual = ArticleRecords(database).find(existingArticle.id)?.read
        )
    }

    @Test
    fun markRead() = runTest {
        val id = 777L

        coEvery { miniflux.updateEntries(any()) } returns Response.success(Unit)

        delegate.markRead(listOf(id.toString()))

        coVerify {
            miniflux.updateEntries(
                UpdateEntriesRequest(
                    entry_ids = listOf(id),
                    status = EntryStatus.READ
                )
            )
        }
    }

    @Test
    fun markUnread() = runTest {
        val id = 777L

        coEvery { miniflux.updateEntries(any()) } returns Response.success(Unit)

        delegate.markUnread(listOf(id.toString()))

        coVerify {
            miniflux.updateEntries(
                UpdateEntriesRequest(
                    entry_ids = listOf(id),
                    status = EntryStatus.UNREAD
                )
            )
        }
    }

    @Test
    fun addStar_flipsOnlyEntriesThatAreNotStarred() = runTest {
        val server = stubStarServer(mapOf(1L to false, 2L to true, 3L to false))

        val result = delegate.addStar(listOf("1", "2", "3"))

        assertTrue(result.isSuccess)
        assertEquals(listOf(1L, 3L), server.flips)
        assertEquals(mapOf(1L to true, 2L to true, 3L to true), server.starred)
    }

    @Test
    fun removeStar_flipsOnlyEntriesThatAreStarred() = runTest {
        val server = stubStarServer(mapOf(1L to true, 2L to false, 3L to true))

        val result = delegate.removeStar(listOf("1", "2", "3"))

        assertTrue(result.isSuccess)
        assertEquals(listOf(1L, 3L), server.flips)
        assertEquals(mapOf(1L to false, 2L to false, 3L to false), server.starred)
    }

    @Test
    fun addStar_retryAfterPartialFailureDoesNotInvertStars() = runTest {
        val server = stubStarServer(
            mapOf(1L to false, 2L to false, 3L to false),
            failOnFlip = 2,
        )
        val ids = listOf("1", "2", "3")

        val first = delegate.addStar(ids)
        val second = delegate.addStar(ids)

        assertTrue(first.isFailure)
        assertTrue(second.isSuccess)
        assertEquals(mapOf(1L to true, 2L to true, 3L to true), server.starred)
        assertEquals(listOf(1L, 2L, 3L), server.flips)
    }

    @Test
    fun removeStar_forEntryThatWasNeverStarredSendsNoFlip() = runTest {
        val server = stubStarServer(mapOf(1L to false))

        val result = delegate.removeStar(listOf("1"))

        assertTrue(result.isSuccess)
        assertEquals(emptyList(), server.flips)
        coVerify(exactly = 0) { miniflux.toggleBookmark(any()) }
        assertEquals(mapOf(1L to false), server.starred)
    }

    @Test
    fun addStar_skipsEntryThatNoLongerExists() = runTest {
        val server = stubStarServer(mapOf(1L to false, 3L to false))

        val result = delegate.addStar(listOf("1", "2", "3"))

        assertTrue(result.isSuccess)
        assertEquals(listOf(1L, 3L), server.flips)
        assertEquals(mapOf(1L to true, 3L to true), server.starred)
    }

    @Test
    fun addStar_serverErrorOnReadReportsFailure() = runTest {
        val server = stubStarServer(mapOf(1L to false))
        coEvery { miniflux.entry(1L) } returns
            Response.error(500, "Internal Server Error".toResponseBody())

        val result = delegate.addStar(listOf("1"))

        assertTrue(result.isFailure)
        assertEquals(emptyList(), server.flips)
        coVerify(exactly = 0) { miniflux.toggleBookmark(1L) }
        assertEquals(mapOf(1L to false), server.starred)
    }

    private class StarServer(initial: Map<Long, Boolean>) {
        val starred = initial.toMutableMap()
        val flips = mutableListOf<Long>()
    }

    @Test
    fun addStar_sendsOneBulkRequestWithStarredTrue() = runTest {
        val server = stubStarServer(
            mapOf(1L to false, 2L to false, 3L to false),
            bulkSupported = true,
        )

        val result = delegate.addStar(listOf("1", "2", "3"))

        assertTrue(result.isSuccess)
        assertEquals(mapOf(1L to true, 2L to true, 3L to true), server.starred)
        coVerify(exactly = 1) {
            miniflux.updateEntries(
                UpdateEntriesRequest(entry_ids = listOf(1L, 2L, 3L), starred = true)
            )
        }
        coVerify(exactly = 1) { miniflux.updateEntries(any()) }
        coVerify(exactly = 0) { miniflux.entry(any()) }
        coVerify(exactly = 0) { miniflux.toggleBookmark(any()) }
    }

    @Test
    fun removeStar_sendsOneBulkRequestWithStarredFalse() = runTest {
        val server = stubStarServer(
            mapOf(1L to true, 2L to true, 3L to true),
            bulkSupported = true,
        )

        val result = delegate.removeStar(listOf("1", "2", "3"))

        assertTrue(result.isSuccess)
        assertEquals(mapOf(1L to false, 2L to false, 3L to false), server.starred)
        coVerify(exactly = 1) {
            miniflux.updateEntries(
                UpdateEntriesRequest(entry_ids = listOf(1L, 2L, 3L), starred = false)
            )
        }
        coVerify(exactly = 1) { miniflux.updateEntries(any()) }
        coVerify(exactly = 0) { miniflux.entry(any()) }
        coVerify(exactly = 0) { miniflux.toggleBookmark(any()) }
    }

    @Test
    fun addStar_serverRejectsBulkRequestWith400FallsBackAndCompletes() = runTest {
        val server = stubStarServer(mapOf(1L to false, 2L to true, 3L to false))

        val result = delegate.addStar(listOf("1", "2", "3"))

        assertTrue(result.isSuccess)
        assertEquals(mapOf(1L to true, 2L to true, 3L to true), server.starred)
        assertEquals(listOf(1L, 3L), server.flips)
        coVerify(exactly = 1) { miniflux.updateEntries(any()) }
    }

    @Test
    fun addStar_afterRejectionDoesNotSendBulkRequestAgain() = runTest {
        val server = stubStarServer(mapOf(1L to false, 2L to false, 3L to false))

        val first = delegate.addStar(listOf("1"))
        val second = delegate.addStar(listOf("2", "3"))

        assertTrue(first.isSuccess)
        assertTrue(second.isSuccess)
        assertEquals(mapOf(1L to true, 2L to true, 3L to true), server.starred)
        coVerify(exactly = 1) { miniflux.updateEntries(any()) }
    }

    @Test
    fun addStar_serverErrorOnBulkRequestReportsFailureWithoutFallback() = runTest {
        stubStarServer(mapOf(1L to false))
        coEvery { miniflux.updateEntries(any()) } returns
            Response.error(500, "Internal Server Error".toResponseBody())

        val result = delegate.addStar(listOf("1"))

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { miniflux.entry(any()) }
        coVerify(exactly = 0) { miniflux.toggleBookmark(any()) }
    }

    @Test
    fun addStar_authErrorOnBulkRequestReportsFailureWithoutFallback() = runTest {
        stubStarServer(mapOf(1L to false))
        coEvery { miniflux.updateEntries(any()) } returns
            Response.error(401, "Unauthorized".toResponseBody())

        val result = delegate.addStar(listOf("1"))

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { miniflux.entry(any()) }
        coVerify(exactly = 0) { miniflux.toggleBookmark(any()) }
    }

    @Test
    fun addStar_bulkRequestWithDeletedEntryReportsSuccess() = runTest {
        val server = stubStarServer(mapOf(1L to false, 3L to false), bulkSupported = true)

        val result = delegate.addStar(listOf("1", "2", "3"))

        assertTrue(result.isSuccess)
        assertEquals(mapOf(1L to true, 3L to true), server.starred)
        coVerify(exactly = 1) { miniflux.updateEntries(any()) }
        coVerify(exactly = 0) { miniflux.entry(any()) }
    }

    /**
     * Stands in for a Miniflux server. Its toggle always works. When
     * [bulkSupported] is true the starred-only update applies to the named IDs
     * and ignores unknown ones, as 2.3.5 does; otherwise it answers 400 as
     * 2.2.17 does. IDs missing from [initial] answer 404 on read. When
     * [failOnFlip] is set, that toggle call (1-based, counted across calls)
     * times out without flipping.
     */
    private fun stubStarServer(
        initial: Map<Long, Boolean>,
        failOnFlip: Int? = null,
        bulkSupported: Boolean = false,
    ): StarServer {
        val server = StarServer(initial)
        var flipCalls = 0

        coEvery { miniflux.updateEntries(any()) } answers {
            val request = firstArg<UpdateEntriesRequest>()

            if (bulkSupported) {
                request.entry_ids.filter { it in server.starred }.forEach { id ->
                    server.starred[id] = request.starred ?: server.starred.getValue(id)
                }
                Response.success(Unit)
            } else {
                Response.error(
                    400,
                    """{"error_message":"invalid entry status, valid status values are: \"read\", \"unread\" and \"removed\""}"""
                        .toResponseBody()
                )
            }
        }

        coEvery { miniflux.entry(any()) } answers {
            val id = firstArg<Long>()
            val starred = server.starred[id]

            if (starred == null) {
                Response.error(404, "Not Found".toResponseBody())
            } else {
                Response.success(arsTechnicaArticle.copy(id = id, starred = starred))
            }
        }

        coEvery { miniflux.toggleBookmark(any()) } answers {
            val id = firstArg<Long>()
            flipCalls += 1

            if (flipCalls == failOnFlip) {
                throw SocketTimeoutException("Network timeout")
            }

            server.starred[id] = !server.starred.getValue(id)
            server.flips.add(id)
            Response.success(Unit)
        }

        return server
    }

    @Test
    fun addFeed() = runTest {
        val url = "https://wheresyoured.at/feed"
        val feedID = 2819820L

        coEvery {
            miniflux.createFeed(CreateFeedRequest(feed_url = url, category_id = null))
        } returns Response.success(CreateFeedResponse(feed_id = feedID))

        coEvery { miniflux.feed(feedID) }.returns(Response.success(arsTechnicaFeed))
        coEvery { miniflux.icon(1) }.returns(
            Response.success(IconData(id = 1, data = "image/png;base64,abc", mime_type = "image/png"))
        )

        coEvery { miniflux.entries(starred = true, limit = any(), offset = any()) }.returns(
            Response.success(EntryResultSet(total = 0, entries = emptyList()))
        )
        coEvery { miniflux.entries(status = EntryStatus.UNREAD.value, limit = any(), offset = any()) }.returns(
            Response.success(EntryResultSet(total = 0, entries = emptyList()))
        )
        coEvery {
            miniflux.entries(
                limit = any(),
                offset = any(),
                order = any(),
                direction = any()
            )
        }.returns(Response.success(EntryResultSet(total = 0, entries = emptyList())))

        val result = delegate.addFeed(
            url = url,
            folderTitles = emptyList(),
            title = ""
        ) as AddFeedResult.Success
        val feed = result.feed

        assertEquals(
            expected = "Ars Technica",
            actual = feed.title
        )
    }

    @Test
    fun addFeed_Failure() = runTest {
        val url = "https://example.com/invalid"

        coEvery {
            miniflux.createFeed(CreateFeedRequest(feed_url = url, category_id = null))
        } returns Response.error(404, mockk(relaxed = true))

        val result = delegate.addFeed(url = url, folderTitles = emptyList(), title = "")

        assertTrue(result is AddFeedResult.Failure)
    }

    @Test
    fun addFeed_failedEntriesResponse() = runTest {
        val url = "https://wheresyoured.at/feed"
        val feedID = 2819820L

        coEvery {
            miniflux.createFeed(CreateFeedRequest(feed_url = url, category_id = null))
        } returns Response.success(CreateFeedResponse(feed_id = feedID))
        coEvery { miniflux.feed(feedID) }.returns(Response.success(arsTechnicaFeed))
        coEvery { miniflux.icon(1) }.returns(
            Response.success(IconData(id = 1, data = "image/png;base64,abc", mime_type = "image/png"))
        )
        coEvery { miniflux.entries(starred = true, limit = any(), offset = any()) }.returns(
            Response.error(502, "Bad Gateway".toResponseBody())
        )
        coEvery { miniflux.entries(status = EntryStatus.UNREAD.value, limit = any(), offset = any()) }.returns(
            Response.success(EntryResultSet(total = 0, entries = emptyList()))
        )

        val result = delegate.addFeed(url = url, folderTitles = emptyList(), title = "")

        assertTrue(result is AddFeedResult.Success)
    }

    @Test
    fun refresh_unauthorized() = runTest {
        coEvery { miniflux.feeds() }.returns(Response.error(401, "Unauthorized".toResponseBody()))

        val result = delegate.refresh(ArticleFilter.default())

        assertTrue(result.exceptionOrNull() is UnauthorizedError)
    }

    @Test
    fun updateFeed_modifyTitle() = runTest {
        val feed = feedFixture.create()
        val feedTitle = "The Verge Mobile Podcast"

        coEvery {
            miniflux.updateFeed(
                feedID = feed.id.toLong(),
                request = UpdateFeedRequest(title = feedTitle, category_id = null)
            )
        }.returns(Response.success(vergeFeed))

        val updated = delegate.updateFeed(
            feed = feed,
            title = feedTitle,
            folderTitles = emptyList(),
        ).getOrThrow()

        assertEquals(expected = feedTitle, actual = updated.title)
    }

    @Test
    fun updateFeed_modifyCategoryToExisting() = runTest {
        val feed = feedFixture.create(folderNames = listOf("Tech"))
        val newCategory = Category(id = 2, title = "News", user_id = 100)

        coEvery {
            miniflux.categories()
        }.returns(Response.success(listOf(category, newCategory)))

        coEvery {
            miniflux.updateFeed(
                feedID = feed.id.toLong(),
                request = UpdateFeedRequest(title = feed.title, category_id = newCategory.id)
            )
        }.returns(Response.success(vergeFeed))

        delegate.updateFeed(
            feed = feed,
            title = feed.title,
            folderTitles = listOf("News"),
        ).getOrThrow()

        val allTaggings = database.taggingsQueries
            .findFeedTaggingsToDelete(feedID = feed.id, excludedNames = emptyList())
            .executeAsList()

        val nonNewsTaggings = database.taggingsQueries
            .findFeedTaggingsToDelete(feedID = feed.id, excludedNames = listOf("News"))
            .executeAsList()

        assertEquals(expected = 1, actual = allTaggings.size)
        assertEquals(expected = 0, actual = nonNewsTaggings.size)
    }

    @Test
    fun updateFeed_newCategory() = runTest {
        val feed = feedFixture.create(folderNames = listOf("Tech"))
        val createdCategory = Category(id = 3, title = "Science", user_id = 100)

        coEvery {
            miniflux.categories()
        }.returns(Response.success(listOf(category)))

        coEvery {
            miniflux.createCategory(CreateCategoryRequest(title = "Science"))
        }.returns(Response.success(createdCategory))

        coEvery {
            miniflux.updateFeed(
                feedID = feed.id.toLong(),
                request = UpdateFeedRequest(title = feed.title, category_id = createdCategory.id)
            )
        }.returns(Response.success(vergeFeed))

        delegate.updateFeed(
            feed = feed,
            title = feed.title,
            folderTitles = listOf("Science"),
        ).getOrThrow()

        val allTaggings = database.taggingsQueries
            .findFeedTaggingsToDelete(feedID = feed.id, excludedNames = emptyList())
            .executeAsList()

        val nonScienceTaggings = database.taggingsQueries
            .findFeedTaggingsToDelete(feedID = feed.id, excludedNames = listOf("Science"))
            .executeAsList()

        assertEquals(expected = 1, actual = allTaggings.size)
        assertEquals(expected = 0, actual = nonScienceTaggings.size)
    }
}
