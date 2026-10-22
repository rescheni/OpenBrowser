package com.openbrowser.mobile.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WebsiteDataTest {
    private fun vector(): JSONObject = JSONObject(javaClass.classLoader!!.getResourceAsStream("website-state-vector.json")!!.bufferedReader().use { it.readText() })
    private fun options() = JSONObject().put("space", "unit").put("includeCookies", true).put("includeSiteData", true).put("includeCredentials", true).put("acceptNew", true)
    @Test fun desktopDigestsAndV2SchemaAgree() {
        val v = vector(); val body = PortableBundle.validate(v.getJSONObject("body")); val profile = body.getJSONArray("profiles").getJSONObject(0)
        assertEquals(SyncMerge.canonical(v.getJSONObject("digests")), SyncMerge.canonical(SyncMerge.digests(profile)))
    }
    @Test fun independentSectionsMergeAndPasswordDeletionIsPreserved() {
        val base = vector().getJSONObject("body"); val l = JSONObject(base.toString()); val r = JSONObject(base.toString()).put("sync", JSONObject().put("space", "unit").put("version", 1))
        val lp = l.getJSONArray("profiles").getJSONObject(0); val rp = r.getJSONArray("profiles").getJSONObject(0)
        lp.put("name", "local name"); rp.put("credentials", JSONArray()); rp.getJSONArray("siteData").getJSONObject(0).put("localStorage", JSONArray().put(JSONArray().put("session").put("remote")))
        val state = JSONObject().put("bases", JSONObject().put(lp.getString("id"), SyncMerge.digests(base.getJSONArray("profiles").getJSONObject(0))))
        val plan = SyncMerge.plan(l, r, state, options(), emptySet())
        assertEquals(0, plan.conflicts.length()); val result = plan.body.getJSONArray("profiles").getJSONObject(0)
        assertEquals("local name", result.getString("name")); assertEquals(0, result.getJSONArray("credentials").length())
        assertEquals("remote", result.getJSONArray("siteData").getJSONObject(0).getJSONArray("localStorage").getJSONArray(0).getString(1))
    }
    @Test fun optOutPreservesRemoteSectionsWithoutBaseline() {
        val local = vector().getJSONObject("body"); val remote = JSONObject(local.toString()).put("sync", JSONObject().put("version", 1).put("space", "unit"))
        val p = local.getJSONArray("profiles").getJSONObject(0); p.put("siteData", JSONArray()).put("credentials", JSONArray())
        val plan = SyncMerge.plan(local, remote, JSONObject(), options().put("includeSiteData", false).put("includeCredentials", false), emptySet())
        assertEquals(1, plan.body.getJSONArray("profiles").getJSONObject(0).getJSONArray("credentials").length())
        assertFalse(plan.bases.getJSONObject(p.getString("id")).has("credentials")); assertFalse(plan.bases.getJSONObject(p.getString("id")).has("siteData"))
    }
    @Test fun invalidOriginsAndDuplicatePasswordsReject() {
        assertThrows(IllegalArgumentException::class.java) { WebsiteData.origin("https://user:password@example.com") }
        assertThrows(IllegalArgumentException::class.java) { WebsiteData.origin("https://example.com/") }
        val c = JSONObject().put("origin", "https://example.com").put("username", "alice").put("password", "test")
        assertThrows(IllegalArgumentException::class.java) { WebsiteData.credentials(JSONArray().put(c).put(c)) }
        val body = vector().getJSONObject("body").put("version", 1)
        assertThrows(IllegalArgumentException::class.java) { PortableBundle.validate(body) }
    }
}
