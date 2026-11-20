package com.andharness.ru.data.ssh

import com.andharness.ru.domain.ssh.SshAuthType
import com.andharness.ru.domain.ssh.SshServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins server disambiguation (update.md §5.4): an unnamed or ambiguous server must come back as a
 * question (a numbered list), never as a silent pick.
 */
class SshServerResolverTest {

    private val servers = listOf(
        server(1, "prod"),
        server(2, "staging"),
        server(3, "web-1"),
        server(4, "web-2"),
    )

    @Test
    fun exactNameResolvesRegardlessOfCaseAndPadding() {
        assertEquals(1L, found(SshServerResolver.resolve(servers, "prod", null)).id)
        assertEquals(1L, found(SshServerResolver.resolve(servers, "PROD", null)).id)
        assertEquals(1L, found(SshServerResolver.resolve(servers, "  prod  ", null)).id)
    }

    @Test
    fun idWinsOverName() {
        assertEquals(2L, found(SshServerResolver.resolve(servers, "prod", 2L)).id)
        assertEquals(4L, found(SshServerResolver.resolve(servers, "prod", 4L)).id)
    }

    @Test
    fun unknownIdIsNotFound() {
        assertEquals(ServerResolution.NotFound, SshServerResolver.resolve(servers, null, 99L))
    }

    @Test
    fun missingSelectorAsksForOne() {
        assertEquals(ServerResolution.Unspecified, SshServerResolver.resolve(servers, null, null))
        assertEquals(ServerResolution.Unspecified, SshServerResolver.resolve(servers, "", null))
        assertEquals(ServerResolution.Unspecified, SshServerResolver.resolve(servers, "   ", null))
    }

    @Test
    fun uniquePartialNameIsAccepted() {
        assertEquals(2L, found(SshServerResolver.resolve(servers, "stag", null)).id)
    }

    @Test
    fun ambiguousPartialNameReturnsEveryCandidate() {
        val resolution = SshServerResolver.resolve(servers, "web", null)
        assertTrue("expected Ambiguous but was $resolution", resolution is ServerResolution.Ambiguous)
        val names = (resolution as ServerResolution.Ambiguous).candidates.map { it.name }
        assertEquals(listOf("web-1", "web-2"), names)
    }

    @Test
    fun unknownNameIsNotFound() {
        assertEquals(ServerResolution.NotFound, SshServerResolver.resolve(servers, "nope", null))
    }

    @Test
    fun emptyRegistryCannotResolveAnything() {
        assertEquals(ServerResolution.NotFound, SshServerResolver.resolve(emptyList(), "prod", null))
        assertEquals(
            ServerResolution.NotFound,
            SshServerResolver.resolve(emptyList(), null, 1L)
        )
        assertEquals(ServerResolution.Unspecified, SshServerResolver.resolve(emptyList(), null, null))
    }

    private fun found(resolution: ServerResolution): SshServer {
        assertTrue("expected Found but was $resolution", resolution is ServerResolution.Found)
        return (resolution as ServerResolution.Found).server
    }

    private fun server(id: Long, name: String) = SshServer(
        id = id,
        name = name,
        host = "$name.example.com",
        port = 22,
        user = "root",
        authType = SshAuthType.KEY,
        keyRef = "content://keys/$id",
        createdAt = 0L
    )
}
