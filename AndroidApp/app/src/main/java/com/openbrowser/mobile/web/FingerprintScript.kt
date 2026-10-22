package com.openbrowser.mobile.web

import android.content.Context
import com.openbrowser.mobile.data.Profile

/**
 * 把 assets/fingerprint.js 与当前环境配置合成为最终的 document-start 脚本。
 * 模板只读取一次，之后仅做字符串替换。
 */
object FingerprintScript {

    private const val TOKEN = "__OB_CONFIG__"

    @Volatile
    private var template: String? = null

    fun build(context: Context, profile: Profile): String {
        val source = template ?: synchronized(this) {
            template ?: context.applicationContext.assets.open("fingerprint.js")
                .bufferedReader()
                .use { it.readText() }
                .also { template = it }
        }
        return source.replace(TOKEN, profile.fingerprintJson().toString())
    }
}
