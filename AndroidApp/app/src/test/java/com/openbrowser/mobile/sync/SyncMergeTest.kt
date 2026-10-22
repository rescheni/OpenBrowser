package com.openbrowser.mobile.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SyncMergeTest {
    private fun body(): JSONObject = JSONObject(checkNotNull(javaClass.getResourceAsStream("/portable-sync-vector.txt"))
        .bufferedReader(Charsets.UTF_8).use { it.readLines()[2] })
    private fun config() = JSONObject().put("space", "unit").put("includeCookies", true).put("acceptNew", true)
    private fun remote() = body().put("sync", JSONObject().put("space", "unit").put("version", 1))
    private fun first(value: JSONObject) = value.getJSONArray("profiles").getJSONObject(0)

    @Test
    fun publishedS3SignatureMatchesDesktopAndAwsExample() {
        val endpoint = SyncEndpoint("https://examplebucket.s3.amazonaws.com/test.txt", provider = "s3", region = "us-east-1", accessKey = "AKIAIOSFODNN7EXAMPLE", secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
        val headers = S3Signer.sign("GET", endpoint.getUrl, endpoint, extra = mapOf("Range" to "bytes=0-9"), at = Instant.parse("2013-05-24T00:00:00Z"))
        assertTrue(headers.getValue("Authorization").endsWith("Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"))
    }

    @Test
    fun independentConfigAndCookieChangesMerge() {
        val left = body(); val right = remote(); val id = first(left).getString("id")
        val state = JSONObject().put("bases", JSONObject().put(id, SyncMerge.digests(first(left))))
        first(left).put("note", "local edit")
        first(right).put("cookies", JSONArray().put(JSONObject().put("url", "https://example.com/").put("setCookie", "session=test-only; Path=/; Secure; HttpOnly")))
        val result = SyncMerge.plan(left, right, state, config(), emptySet())
        assertEquals(0, result.conflicts.length())
        assertEquals("local edit", first(result.body).getString("note"))
        assertEquals(1, result.imports.getJSONObject(0).getJSONArray("cookies").length())
    }

    @Test
    fun concurrentEditsRequireReviewedVersionSpecificDecision() {
        val left = body(); val right = remote(); val id = first(left).getString("id")
        val state = JSONObject().put("bases", JSONObject().put(id, SyncMerge.digests(first(left))))
        first(left).put("name", "local"); first(right).put("name", "remote")
        val conflict = SyncMerge.plan(left, right, state, config(), emptySet())
        assertEquals(1, conflict.conflicts.length())
        state.put("decisions", SyncMerge.resolve(conflict.conflicts, "remote"))
        assertEquals("remote", first(SyncMerge.plan(left, right, state, config(), emptySet()).body).getString("name"))
        first(right).put("name", "changed after review")
        assertEquals(1, SyncMerge.plan(left, right, state, config(), emptySet()).conflicts.length())
    }

    @Test
    fun deletedLocalAndOptedOutProfilesDoNotResurrect() {
        val left = body(); val right = remote(); val id = first(left).getString("id")
        val state = JSONObject().put("bases", JSONObject().put(id, SyncMerge.digests(first(left))))
        left.put("profiles", JSONArray())
        val result = SyncMerge.plan(left, right, state, config(), emptySet())
        assertEquals(0, result.imports.length()); assertEquals(1, result.body.getJSONArray("profiles").length())
        assertEquals(0, SyncMerge.plan(left, right, JSONObject(), config(), setOf(id)).imports.length())
    }

    @Test
    fun cookieAttributesNormalizeIndependentOfOrderAndRequestPath() {
        val a = first(body()); val b = first(body())
        a.put("cookies", JSONArray().put(JSONObject().put("url", "https://example.com/a").put("setCookie", "x=test-only; Path=/; HttpOnly; Secure; SameSite=Lax")))
        b.put("cookies", JSONArray().put(JSONObject().put("url", "https://example.com/b").put("setCookie", "x=test-only; samesite=lax; secure; httponly; path=/")))
        assertEquals(SyncMerge.canonical(SyncMerge.digests(a)), SyncMerge.canonical(SyncMerge.digests(b)))
    }
}
