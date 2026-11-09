package com.jocmp.capy.accounts.miniflux

import com.jocmp.capy.AccountDelegate
import com.jocmp.capy.AccountPreferences
import com.jocmp.capy.ArticleFilter
import com.jocmp.capy.Feed
import com.jocmp.capy.accounts.AddFeedResult
import com.jocmp.capy.accounts.orThrow
import com.jocmp.capy.accounts.willAutoDelete
import com.jocmp.capy.accounts.withErrorHandling
import com.jocmp.capy.common.ContentFormatter
import com.jocmp.capy.common.TimeHelpers
import com.jocmp.capy.common.UnauthorizedError
import com.jocmp.capy.common.stripTitleMarkup
import com.jocmp.capy.common.toDateTime
import com.jocmp.capy.common.transactionWithErrorHandling
import com.jocmp.capy.db.Database
import com.jocmp.capy.logging.CapyLog
import com.jocmp.capy.persistence.ArticleRecords
import com.jocmp.capy.persistence.EnclosureRecords
import com.jocmp.capy.persistence.FeedRecords
import com.jocmp.capy.persistence.TaggingRecords
import com.jocmp.minifluxclient.CreateCategoryRequest
import com.jocmp.minifluxclient.CreateFeedRequest
import com.jocmp.minifluxclient.Entry
import com.jocmp.minifluxclient.EntryResultSet
import com.jocmp.minifluxclient.EntryStatus
import com.jocmp.minifluxclient.Miniflux
import com.jocmp.minifluxclient.UpdateCategoryRequest
import com.jocmp.minifluxclient.UpdateEntriesRequest
import com.jocmp.minifluxclient.UpdateFeedRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okio.IOException
import retrofit2.HttpException
import retrofit2.Response
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.minutes
import com.jocmp.minifluxclient.Feed as MinifluxFeed

internal class MinifluxAccountDelegate(
    private val database: Database,
    private val miniflux: Miniflux,
    private val preferences: AccountPreferences,
) : AccountDelegate {
    private val articleRecords = ArticleRecords(database)
    private val enclosureRecords = EnclosureRecords(database)
    private val feedRecords = FeedRecords(database)
    private val taggingRecords = TaggingRecords(database)

    @Volatile
    private var bulkStarUnsupported = false

    @Volatile
    private var entryIDsUnsupported = false

    override suspend fun refresh(filter: ArticleFilter, cutoffDate: ZonedDateTime?): Result<Unit> {
        val startedAt = TimeHelpers.nowUTC()

        return try {
            refreshIntegrationStatus()
            refreshFeeds()
            refreshArticles(cutoffDate = cutoffDate)
            preferences.touchLastRefreshedAt(startedAt)

            Result.success(Unit)
        } catch (exception: IOException) {
            Result.failure(exception)
        } catch (e: HttpException) {
            if (e.code() == 401) {
                Result.failure(UnauthorizedError())
            } else {
                Result.failure(e)
            }
        } catch (e: UnauthorizedError) {
            Result.failure(e)
        }
    }

    override suspend fun markRead(articleIDs: List<String>): Result<Unit> {
        val entryIDs = articleIDs.map { it.toLong() }

        return withErrorHandling {
            miniflux.updateEntries(
                UpdateEntriesRequest(
                    entry_ids = entryIDs,
                    status = EntryStatus.READ
                )
            ).orThrow()
            Unit
        }
    }

    override suspend fun markUnread(articleIDs: List<String>): Result<Unit> {
        val entryIDs = articleIDs.map { it.toLong() }

        return withErrorHandling {
            miniflux.updateEntries(
                UpdateEntriesRequest(
                    entry_ids = entryIDs,
                    status = EntryStatus.UNREAD
                )
            ).orThrow()
            Unit
        }
    }

    override suspend fun addStar(articleIDs: List<String>): Result<Unit> {
        return setStarred(articleIDs, starred = true)
    }

    override suspend fun removeStar(articleIDs: List<String>): Result<Unit> {
        return setStarred(articleIDs, starred = false)
    }

    /**
     * Newer Miniflux servers set the starred state for a whole batch in one request.
     * Servers before 2.3.2 reject that with a 400, so fall back to reading each entry
     * first and flipping only the ones not already in the requested state. A blind
     * toggle inverts entries that a partially failed earlier attempt already changed.
     */
    private suspend fun setStarred(articleIDs: List<String>, starred: Boolean): Result<Unit> {
        val entryIDs = articleIDs.map { it.toLong() }

        return withErrorHandling {
            if (!bulkStarUnsupported) {
                val bulk = miniflux.updateEntries(
                    UpdateEntriesRequest(entry_ids = entryIDs, starred = starred)
                )

                if (bulk.isSuccessful) {
                    return@withErrorHandling Unit
                }

                if (bulk.code() == 400) {
                    bulkStarUnsupported = true
                } else {
                    bulk.orThrow()
                }
            }

            entryIDs.forEach { entryID ->
                val response = miniflux.entry(entryID)

                if (response.code() == 404) {
                    return@forEach
                }

                val entry = response.orThrow().body()
                    ?: throw IOException("Empty body for entry $entryID")

                if (entry.starred != starred) {
                    miniflux.toggleBookmark(entryID).orThrow()
                }
            }
            Unit
        }
    }

    override suspend fun addSavedSearch(articleID: String, savedSearchID: String): Result<Unit> {
        return Result.failure(UnsupportedOperationException("Labels not supported"))
    }

    override suspend fun removeSavedSearch(articleID: String, savedSearchID: String): Result<Unit> {
        return Result.failure(UnsupportedOperationException("Labels not supported"))
    }

    override suspend fun createSavedSearch(name: String): Result<String> {
        return Result.failure(UnsupportedOperationException("Labels not supported"))
    }

    override suspend fun createPage(url: String) =
        Result.failure<Unit>(UnsupportedOperationException("Pages not supported"))

    override suspend fun saveArticleExternally(articleID: String): Result<Unit> {
        return try {
            val response = miniflux.saveEntry(articleID.toLong())
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(IOException("Save failed"))
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    override suspend fun addFeed(
        url: String,
        title: String?,
        folderTitles: List<String>?
    ): AddFeedResult {
        return try {
            val categoryId = folderTitles?.firstOrNull()?.let { folderTitle ->
                findOrCreateCategory(folderTitle)
            }

            val response = miniflux.createFeed(
                CreateFeedRequest(feed_url = url, category_id = categoryId)
            )
            val createResponse = response.body()

            if (response.code() > 300 || createResponse == null) {
                return AddFeedResult.Failure(AddFeedResult.Error.FeedNotFound())
            }

            val feedResponse = miniflux.feed(createResponse.feed_id)
            val feed = feedResponse.body()

            return if (feed != null) {
                val icons = fetchIcons(listOf(feed))
                upsertFeed(feed, icons)

                val localFeed = feedRecords.find(feed.id.toString())

                if (localFeed != null) {
                    coroutineScope {
                        launch {
                            try {
                                refreshArticles()
                            } catch (e: HttpException) {
                                CapyLog.warn("add_feed_refresh", mapOf("code" to e.code().toString()))
                            }
                        }
                    }

                    AddFeedResult.Success(localFeed)
                } else {
                    AddFeedResult.Failure(AddFeedResult.Error.SaveFailure())
                }
            } else {
                AddFeedResult.Failure(AddFeedResult.Error.FeedNotFound())
            }
        } catch (e: IOException) {
            AddFeedResult.networkError()
        }
    }

    override suspend fun updateFeed(
        feed: Feed,
        title: String,
        folderTitles: List<String>,
    ): Result<Feed> = withErrorHandling {
        val categoryId = folderTitles.firstOrNull()?.let { folderTitle ->
            findOrCreateCategory(folderTitle)
        }

        miniflux.updateFeed(
            feedID = feed.id.toLong(),
            request = UpdateFeedRequest(title = title, category_id = categoryId)
        ).orThrow()

        database.transactionWithErrorHandling {
            feedRecords.update(
                feedID = feed.id,
                title = title,
            )

            if (categoryId != null) {
                folderTitles.forEach { folderTitle ->
                    taggingRecords.upsert(
                        id = taggingID(feed.id, categoryId),
                        feedID = feed.id,
                        name = folderTitle,
                    )
                }
            }

            val taggingIDsToDelete = taggingRecords.findFeedTaggingsToDelete(
                feed = feed,
                excludedTaggingNames = folderTitles
            )

            taggingIDsToDelete.forEach { taggingID ->
                taggingRecords.deleteTagging(taggingID = taggingID)
            }
        }

        feedRecords.find(feed.id)
    }

    override suspend fun updateFolder(
        oldTitle: String,
        newTitle: String
    ): Result<Unit> = withErrorHandling {
        val categories = miniflux.categories().orThrow().body() ?: emptyList()
        val category = categories.find { it.title == oldTitle }

        if (category != null) {
            miniflux.updateCategory(
                categoryID = category.id,
                request = UpdateCategoryRequest(title = newTitle)
            ).orThrow()

            taggingRecords.updateTitle(previousTitle = oldTitle, title = newTitle)
        }

        Unit
    }

    override suspend fun removeFeed(feed: Feed): Result<Unit> = withErrorHandling {
        miniflux.deleteFeed(feedID = feed.id.toLong()).orThrow()

        Unit
    }

    override suspend fun removeFolder(folderTitle: String): Result<Unit> = withErrorHandling {
        val categories = miniflux.categories().orThrow().body() ?: emptyList()
        val category = categories.find { it.title == folderTitle }

        if (category != null) {
            miniflux.deleteCategory(categoryID = category.id).orThrow()
            taggingRecords.deleteByFolderName(folderTitle)
        }

        Unit
    }

    private suspend fun refreshIntegrationStatus() {
        try {
            val response = miniflux.integrationStatus()
            val status = response.body()
            if (response.isSuccessful && status != null) {
                preferences.canSaveArticleExternally.set(status.has_integrations)
            }
        } catch (e: Exception) {
            CapyLog.warn("refresh_integration_status", mapOf("error" to e.message))
        }
    }

    private suspend fun refreshFeeds() {
        val feeds = miniflux.feeds().orThrow().body() ?: return

        val feedIDsWithIcons = database.feedsQueries.all()
            .executeAsList()
            .filter { it.favicon_url != null }
            .map { it.id }
            .toSet()

        val icons = fetchIcons(feeds.filterNot { it.id.toString() in feedIDsWithIcons })

        database.transactionWithErrorHandling {
            feeds.forEach { feed ->
                upsertFeed(feed, icons)
            }
        }

        val feedsToKeep = feeds.map { it.id.toString() }
        database.feedsQueries.deleteAllExcept(feedsToKeep)
    }

    private suspend fun refreshArticles(cutoffDate: ZonedDateTime? = null) = coroutineScope {
        val starred = async { refreshStarredEntries() }
        val unread = async { refreshUnreadEntries() }
        val statusIDs = starred.await() + unread.await()
        fetchAllEntries(cutoffDate = cutoffDate)
        fetchMissingEntries(statusIDs = statusIDs, cutoffDate = cutoffDate)
    }

    private suspend fun refreshStarredEntries(): List<String> {
        val ids = fetchEntryIDs(starred = true)

        articleRecords.markAllStarred(articleIDs = ids)

        return ids
    }

    private suspend fun refreshUnreadEntries(): List<String> {
        val ids = fetchEntryIDs(status = EntryStatus.UNREAD)

        articleRecords.markAllUnread(articleIDs = ids)

        return ids
    }

    /**
     * Uses the IDs-only listing when the server has it and falls back to the full-entry
     * listing when it does not. A missing list is never returned as an empty list, because
     * applying an empty list marks every local article read or unstarred.
     */
    private suspend fun fetchEntryIDs(
        status: EntryStatus? = null,
        starred: Boolean? = null
    ): List<String> {
        if (!entryIDsUnsupported) {
            val firstResponse = miniflux.entryIDs(
                status = status?.value,
                starred = starred,
                limit = MAX_ENTRY_IDS_LIMIT,
                offset = 0
            )
            val firstIDs = if (firstResponse.isSuccessful) firstResponse.body() else null
            val firstEntryIDs = firstIDs?.entry_ids

            if (firstIDs != null && firstEntryIDs != null) {
                val firstIDPage = IDPage(
                    total = firstIDs.total ?: firstEntryIDs.size,
                    ids = firstEntryIDs.map { it.toString() }
                )

                return fetchAllEntryIDs { offset ->
                    if (offset == 0) {
                        firstIDPage
                    } else {
                        val body = miniflux.entryIDs(
                            status = status?.value,
                            starred = starred,
                            limit = MAX_ENTRY_IDS_LIMIT,
                            offset = offset
                        )
                            .orThrow()
                            .body()
                        val entryIDs = body?.entry_ids
                            ?: throw IOException("Miniflux entry IDs page at offset $offset has no entry_ids")

                        IDPage(body.total ?: entryIDs.size, entryIDs.map { it.toString() })
                    }
                }
            }

            if (firstResponse.isSuccessful || firstResponse.code() == 400 || firstResponse.code() == 404) {
                entryIDsUnsupported = true
            } else {
                firstResponse.orThrow()
            }
        }

        return fetchAllEntryIDs { offset ->
            val body = miniflux.entries(
                status = status?.value,
                starred = starred,
                limit = MAX_ENTRY_LIMIT,
                offset = offset
            )
                .orThrow()
                .body()

            body?.let { IDPage(it.total, it.entries.map { entry -> entry.id.toString() }) }
        }
    }

    private suspend fun fetchAllEntryIDs(
        fetch: suspend (offset: Int) -> IDPage?
    ): List<String> {
        val firstPage = fetch(0) ?: return emptyList()
        val ids = firstPage.ids.toMutableList()

        while (ids.size < firstPage.total) {
            val page = fetch(ids.size) ?: break
            if (page.ids.isEmpty()) break
            ids.addAll(page.ids)
        }

        return ids
    }

    private class IDPage(val total: Int, val ids: List<String>)

    private suspend fun fetchAllEntries(cutoffDate: ZonedDateTime?) = coroutineScope {
        val changedAfter = preferences.lastRefreshedAt.get()
            .takeIf { it > 0 }
            ?.minus(CHANGED_AFTER_OVERLAP.inWholeSeconds)

        val firstResult = miniflux.entries(
            limit = MAX_ENTRY_LIMIT,
            offset = 0,
            order = "published_at",
            direction = "desc",
            changedAfter = changedAfter,
        ).orThrow().body() ?: return@coroutineScope

        val total = firstResult.total

        saveEntries(firstResult.entries, cutoffDate = cutoffDate)

        val semaphore = Semaphore(MAX_CONCURRENT_FETCHES)

        (MAX_ENTRY_LIMIT until total step MAX_ENTRY_LIMIT)
            .map { offset ->
                async {
                    semaphore.withPermit {
                        val entries = miniflux.entries(
                            limit = MAX_ENTRY_LIMIT,
                            offset = offset,
                            order = "published_at",
                            direction = "desc",
                            changedAfter = changedAfter,
                        ).orThrow().body()?.entries ?: return@withPermit
                        saveEntries(entries, cutoffDate = cutoffDate)
                    }
                }
            }
            .awaitAll()
    }

    /**
     * Entries can slip past `changed_after` when the server's clock trails the device's or a
     * feed refresh commits mid-sync, so fetch any unread or starred entry still missing locally.
     */
    private suspend fun fetchMissingEntries(
        statusIDs: List<String>,
        cutoffDate: ZonedDateTime?
    ) = coroutineScope {
        val missingIDs = articleRecords.findMissingArticles().toSet() intersect statusIDs.toSet()
        val semaphore = Semaphore(MAX_CONCURRENT_FETCHES)

        val entries = missingIDs
            .map { entryID ->
                async {
                    semaphore.withPermit { fetchEntry(entryID) }
                }
            }
            .awaitAll()
            .filterNotNull()

        saveEntries(entries, cutoffDate = cutoffDate)
    }

    private suspend fun fetchEntry(entryID: String): Entry? {
        return try {
            miniflux.entry(entryID.toLong()).body()
        } catch (e: IOException) {
            CapyLog.warn("fetch_missing_entry", mapOf("entry_id" to entryID, "error" to e.message))
            null
        }
    }

    private fun saveEntries(entries: List<Entry>, cutoffDate: ZonedDateTime?) {
        database.transactionWithErrorHandling {
            entries.forEach { entry ->
                val updated = TimeHelpers.nowUTC()
                val publishedAt = entry.published_at.toDateTime?.toEpochSecond() ?: updated.toEpochSecond()
                val read = entry.status == EntryStatus.READ

                val skip = willAutoDelete(
                    publishedAt = publishedAt,
                    read = read,
                    starred = entry.starred,
                    cutoffDate = cutoffDate,
                )

                if (skip) {
                    return@forEach
                }

                val articleID = entry.id.toString()
                val imageURL = MinifluxEnclosureParsing.parsedImageURL(entry)
                val enclosures = entry.enclosures.orEmpty()

                database.articlesQueries.create(
                    id = articleID,
                    feed_id = entry.feed_id.toString(),
                    title = entry.title.stripTitleMarkup(),
                    author = entry.author,
                    content_html = entry.content,
                    extracted_content_url = null,
                    url = entry.url,
                    summary = ContentFormatter.summary(entry.content),
                    image_url = imageURL,
                    published_at = publishedAt,
                    enclosure_type = enclosures.firstOrNull()?.mime_type,
                )

                articleRecords.createStatus(
                    articleID = articleID,
                    updatedAt = updated,
                    read = read
                )

                enclosures.forEach { enclosure ->
                    enclosureRecords.create(
                        url = enclosure.url,
                        type = enclosure.mime_type,
                        articleID = articleID,
                        itunesDurationSeconds = null,
                        itunesImage = null,
                    )
                }
            }
        }
    }

    private fun upsertFeed(feed: MinifluxFeed, icons: Map<Long, String>) {
        val icon = feed.icon?.icon_id?.let { icons[it] }

        database.feedsQueries.upsert(
            id = feed.id.toString(),
            subscription_id = feed.id.toString(),
            title = feed.title,
            feed_url = feed.feed_url,
            site_url = feed.site_url,
            favicon_url = icon,
            priority = null,
            itunes_image_url = null,
            read_later = false,
        )

        feed.category?.let { category ->
            database.taggingsQueries.upsert(
                id = taggingID(feed.id.toString(), category.id),
                feed_id = feed.id.toString(),
                name = category.title
            )
        }
    }

    private suspend fun findOrCreateCategory(title: String): Long {
        val categories = miniflux.categories().orThrow().body() ?: emptyList()
        val existing = categories.find { it.title == title }

        return if (existing != null) {
            existing.id
        } else {
            val response = miniflux.createCategory(CreateCategoryRequest(title = title))
            response.body()?.id ?: throw IOException("Failed to create category")
        }
    }

    private fun taggingID(feedID: String, categoryId: Long) = "${feedID}-${categoryId}"

    private suspend fun fetchIcons(feeds: List<MinifluxFeed>): Map<Long, String> = coroutineScope {
        val iconIds = feeds.mapNotNull { it.icon?.icon_id }.filter { it > 0 }.distinct()

        iconIds.map { iconId ->
            async {
                try {
                    val response = miniflux.icon(iconId)
                    val iconData = response.body()

                    if (response.isSuccessful && iconData != null) {
                        iconId to "data:${iconData.data}"
                    } else {
                        null
                    }
                } catch (_: Exception) {
                    CapyLog.warn("fetch_icon", mapOf("icon_id" to iconId.toString()))
                    null
                }
            }
        }.awaitAll().filterNotNull().toMap()
    }

    companion object {
        const val MAX_ENTRY_LIMIT = 250
        const val MAX_ENTRY_IDS_LIMIT = 10_000
        private const val MAX_CONCURRENT_FETCHES = 4
        private val CHANGED_AFTER_OVERLAP = 5.minutes
    }
}
