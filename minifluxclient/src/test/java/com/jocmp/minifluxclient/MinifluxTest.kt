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
}
