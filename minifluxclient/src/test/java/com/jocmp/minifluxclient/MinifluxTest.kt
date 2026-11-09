package com.jocmp.minifluxclient

import com.squareup.moshi.Moshi
import okhttp3.OkHttpClient
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MinifluxTest {
    private val adapter = Moshi.Builder().build().adapter(UpdateEntriesRequest::class.java)

    @Test
    fun `create Miniflux client`() {
        val client = OkHttpClient()
        val miniflux = Miniflux.create(
            client = client,
            baseURL = "https://miniflux.app/v1/"
        )

        assertNotNull(miniflux)
    }

    @Test
    fun `update entries request encodes status only`() {
        val json = adapter.toJson(
            UpdateEntriesRequest(entry_ids = listOf(1L, 2L), status = EntryStatus.READ)
        )

        assertEquals("""{"entry_ids":[1,2],"status":"read"}""", json)
    }

    @Test
    fun `update entries request encodes starred only`() {
        val json = adapter.toJson(
            UpdateEntriesRequest(entry_ids = listOf(3L), starred = true)
        )

        assertEquals("""{"entry_ids":[3],"starred":true}""", json)
    }

    @Test
    fun `update entries request omits null fields`() {
        val json = adapter.toJson(UpdateEntriesRequest(entry_ids = listOf(4L)))

        assertEquals("""{"entry_ids":[4]}""", json)
    }

    private val idsAdapter = Moshi.Builder().build().adapter(EntryIDsResponse::class.java)

    @Test
    fun `entry IDs response decodes 2_3_5 body`() {
        val response = idsAdapter.fromJson("""{"total":7,"entry_ids":[8,7,6,5,4,2,1]}""")

        assertEquals(7, response?.total)
        assertEquals(listOf(8L, 7L, 6L, 5L, 4L, 2L, 1L), response?.entry_ids)
    }

    @Test
    fun `entry IDs response decodes empty listing`() {
        val response = idsAdapter.fromJson("""{"total":0,"entry_ids":[]}""")

        assertEquals(0, response?.total)
        assertEquals(emptyList(), response?.entry_ids)
    }

    @Test
    fun `entry IDs response from a 2_2_17 server decodes with null entry_ids`() {
        // Servers before 2.3.2 route entries/ids to the single-entry handler and answer an Entry.
        val body = """{"id":2,"user_id":1,"feed_id":1,"status":"unread","hash":"084693a2b4bbec8fe2018d81da792492d3adab15b28d845d0cf065290eeb93a8","title":"Probe entry 7","url":"http://example.invalid/e7","comments_url":"","published_at":"2026-11-07T10:00:00Z","created_at":"2026-11-09T00:53:19.889439Z","changed_at":"2026-11-09T00:53:19.889439Z","content":"Body 7","author":"","share_code":"","starred":false,"reading_time":1,"enclosures":[],"feed":{"id":1,"user_id":1,"feed_url":"http://host.docker.internal:18099/feed.xml","site_url":"http://example.invalid/","title":"Probe feed","category":{"id":1,"title":"All","user_id":1,"hide_globally":false},"icon":{"feed_id":1,"icon_id":0,"external_icon_id":""}},"tags":[]}"""

        val response = idsAdapter.fromJson(body)

        assertNotNull(response)
        assertEquals(null, response.entry_ids)
        assertEquals(null, response.total)
    }
}
