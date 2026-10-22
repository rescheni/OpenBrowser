package com.openbrowser.mobile.sync

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Portable v2. Never deserialize executable objects or copy browser database files. */
object WebsiteData {
    fun origin(value: String): String {
        val u = URI(value)
        require(u.scheme in listOf("https", "http") && !u.host.isNullOrBlank() && u.userInfo == null && u.query == null && u.fragment == null) { "网站存储来源无效" }
        val host = if (u.host.contains(':') && !u.host.startsWith('[')) "[${u.host}]" else u.host
        val port = if (u.port == -1 || u.scheme == "https" && u.port == 443 || u.scheme == "http" && u.port == 80) "" else ":${u.port}"
        require(value == "${u.scheme}://$host$port" && u.host == u.host.lowercase(java.util.Locale.ROOT)) { "网站存储来源无效" }
        return value
    }
    fun credentials(items: JSONArray): JSONArray {
        require(items.length() <= 500) { "密码库数量超过限制" }
        val keys = mutableSetOf<String>()
        for (i in 0 until items.length()) {
            val c = items.getJSONObject(i); origin(c.getString("origin"))
            require(c.getString("username").length <= 1000 && c.getString("password").length <= 10000 &&
                c.keys().asSequence().all { it in listOf("origin", "username", "password") } &&
                keys.add(JSONArray().put(c.getString("origin")).put(c.getString("username")).toString())) { "密码库条目无效或重复" }
        }
        return items
    }
    fun sites(items: JSONArray): JSONArray {
        require(items.length() <= 50 && items.toString().toByteArray(Charsets.UTF_8).size <= 6 * 1024 * 1024) { "网站存储快照超过容量限制" }
        val origins = mutableSetOf<String>()
        for (i in 0 until items.length()) {
            val s = items.getJSONObject(i); origin(s.getString("origin"))
            require(origins.add(s.getString("origin")) && s.toString().toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) { "网站存储来源重复或超过 2 MB" }
            val local = s.getJSONArray("localStorage"); val keys = mutableSetOf<String>()
            for (j in 0 until local.length()) { val pair = local.getJSONArray(j); require(pair.length() == 2 && pair.get(0) is String && pair.get(1) is String && keys.add(pair.getString(0))) { "网站存储键无效" } }
            val dbs = s.getJSONArray("databases"); require(dbs.length() <= 50) { "网站数据库过多" }
            val names = mutableSetOf<String>(); var count = 0
            for (j in 0 until dbs.length()) {
                val d = dbs.getJSONObject(j); val version = d.getDouble("version")
                require(names.add(d.getString("name")) && version >= 1 && version <= 9007199254740991.0 && version == d.getLong("version").toDouble()) { "网站数据库格式无效" }
                val stores = d.getJSONArray("stores"); val sn = mutableSetOf<String>()
                for (k in 0 until stores.length()) {
                    val st = stores.getJSONObject(k)
                    require(sn.add(st.getString("name")) && keyPath(st.get("keyPath")) && st.get("autoIncrement") is Boolean) { "网站数据表格式无效" }
                    val indexes = st.getJSONArray("indexes"); val ix = mutableSetOf<String>()
                    for (n in 0 until indexes.length()) { val x = indexes.getJSONObject(n); require(ix.add(x.getString("name")) && x.get("keyPath") != JSONObject.NULL && keyPath(x.get("keyPath")) && x.get("unique") is Boolean && x.get("multiEntry") is Boolean) { "网站索引格式无效" } }
                    val rows = st.getJSONArray("rows")
                    for (n in 0 until rows.length()) { require(++count <= 10000) { "网站存储记录过多" }; val r = rows.getJSONObject(n); encoded(r.getJSONArray("key")); encoded(r.getJSONArray("value")) }
                }
            }
        }
        return items
    }
    private fun keyPath(v: Any): Boolean = v == JSONObject.NULL || v is String || v is JSONArray && (0 until v.length()).all { v.get(it) is String }
    private fun encoded(v: JSONArray, depth: Int = 0) {
        require(depth <= 64 && v.length() > 0) { "网站存储编码无效" }
        fun nested(x: JSONArray) = encoded(x, depth + 1)
        fun bytes(x: JSONArray) { require(x.length() <= 2 * 1024 * 1024 && (0 until x.length()).all { val n = x.getDouble(it); n in 0.0..255.0 && n == n.toInt().toDouble() }) { "网站存储二进制值无效" } }
        when (v.getString(0)) {
            "primitive" -> require(v.get(1) == JSONObject.NULL || v.get(1) is String || v.get(1) is Boolean)
            "number" -> require(v.get(1) is Number || v.getString(1) in listOf("NaN", "Infinity", "-Infinity", "-0"))
            "undefined", "hole" -> Unit
            "bigint" -> require(v.getString(1).matches(Regex("-?[0-9]+")))
            "date" -> require(v.get(1) is String)
            "buffer" -> bytes(v.getJSONArray(1))
            "view" -> { require(v.getString(1) in listOf("Int8Array", "Uint8Array", "Uint8ClampedArray", "Int16Array", "Uint16Array", "Int32Array", "Uint32Array", "Float32Array", "Float64Array", "DataView", "BigInt64Array", "BigUint64Array")); bytes(v.getJSONArray(2)) }
            "blob" -> { require(v.get(1) is String); bytes(v.getJSONArray(2)) }
            "file" -> { require(v.get(1) is String && v.get(2) is String && v.get(3) is Number); bytes(v.getJSONArray(4)) }
            "array", "set" -> v.getJSONArray(1).let { a -> for (i in 0 until a.length()) nested(a.getJSONArray(i)) }
            "object", "map" -> v.getJSONArray(1).let { a -> for (i in 0 until a.length()) { val pair = a.getJSONArray(i); require(pair.length() == 2); if (v.getString(0) == "object") require(pair.get(0) is String) else nested(pair.getJSONArray(0)); nested(pair.getJSONArray(1)) } }
            else -> error("网站存储编码无效")
        }
    }
}
