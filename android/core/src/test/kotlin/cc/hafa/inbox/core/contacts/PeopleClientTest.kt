package cc.hafa.inbox.core.contacts

import cc.hafa.inbox.core.gmail.HttpResponse
import cc.hafa.inbox.core.gmail.TokenSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleClientTest {
    private object Tokens : TokenSource {
        override suspend fun get(): String = "token"

        override fun invalidate() = Unit
    }

    @Test
    fun `asks for photos and keeps the primary real one`() = runTest {
        val urls = mutableListOf<String>()
        val client = PeopleClient(Tokens) { _, url, _, _ ->
            urls += url
            HttpResponse(
                200,
                """
                {"connections":[
                  {"resourceName":"people/c1","emailAddresses":[{"value":"a@example.com"}],
                   "photos":[{"url":"https://lh3.googleusercontent.com/first","metadata":{"source":{"type":"PROFILE"}}},
                             {"url":"https://lh3.googleusercontent.com/primary","metadata":{"primary":true}}]},
                  {"resourceName":"people/c2","emailAddresses":[{"value":"b@example.com"}],
                   "photos":[{"url":"https://lh3.googleusercontent.com/letter","default":true,"metadata":{"primary":true}}]},
                  {"resourceName":"people/c3","emailAddresses":[{"value":"c@example.com"}]}
                ]}
                """.trimIndent(),
            )
        }
        val page = client.page(ContactSource.CONNECTIONS, null, null)
        assertEquals(
            listOf("https://lh3.googleusercontent.com/primary", null, null),
            page.changed.map { it.photoUrl },
        )
        assertTrue(urls.single().contains("personFields=names%2CemailAddresses%2Cphotos%2Cmetadata"))
    }

    @Test
    fun `takes the primary name and puts the primary address first, once each`() = runTest {
        val client = PeopleClient(Tokens) { _, _, _, _ ->
            HttpResponse(
                200,
                """
                {"connections":[
                  {"resourceName":"people/c1",
                   "names":[{"displayName":"Maya"},{"displayName":"Maya Chen","metadata":{"primary":true}}],
                   "emailAddresses":[{"value":"maya@work.example.net"},{"value":"maya.chen@gmail.example.com","metadata":{"primary":true}},
                                     {"value":"MAYA@work.example.net"}]}
                ]}
                """.trimIndent(),
            )
        }
        val contact = client.page(ContactSource.CONNECTIONS, null, null).changed.single()
        assertEquals("Maya Chen", contact.name)
        assertEquals(listOf("maya.chen@gmail.example.com", "maya@work.example.net"), contact.emails)
    }

    @Test
    fun `sizes a photo url, replacing a size already there`() {
        assertEquals("https://lh3.googleusercontent.com/a/x?sz=96", sizedPhotoUrl("https://lh3.googleusercontent.com/a/x", 96))
        assertEquals("https://lh3.googleusercontent.com/a/x?k=1&sz=64", sizedPhotoUrl("https://lh3.googleusercontent.com/a/x?k=1", 64))
        assertEquals("https://lh3.googleusercontent.com/a/x?sz=48&k=1", sizedPhotoUrl("https://lh3.googleusercontent.com/a/x?sz=100&k=1", 48))
    }
}
