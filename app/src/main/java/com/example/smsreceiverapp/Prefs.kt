package com.example.smsreceiverapp

import android.content.Context
import androidx.core.content.edit

object Prefs {
    private const val PREF_NAME = "app_prefs"
    private const val KEY_URL = "base_url"
    private const val DEFAULT_URL = "http://192.168.219.100:8010/"

    // API 서버 설정
    private const val KEY_API_HOST = "api_host"
    private const val KEY_API_PORT = "api_port"
    private const val DEFAULT_API_HOST = "192.168.219.100"
    private const val DEFAULT_API_PORT = "8010"


    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // --- API 서버 ---
    fun getApiHost(ctx: Context): String =
        prefs(ctx).getString(KEY_API_HOST, DEFAULT_API_HOST) ?: DEFAULT_API_HOST

    fun getApiPort(ctx: Context): String =
        prefs(ctx).getString(KEY_API_PORT, DEFAULT_API_PORT) ?: DEFAULT_API_PORT

    fun setApiServer(ctx: Context, host: String, port: String) {
        prefs(ctx).edit {
            putString(KEY_API_HOST, host)
            putString(KEY_API_PORT, port)
            putString(KEY_URL, "http://$host:$port/")
        }
    }

    fun getBaseUrl(ctx: Context): String {
        val host = getApiHost(ctx)
        val port = getApiPort(ctx)
        return "http://$host:$port/"
    }

    fun setBaseUrl(ctx: Context, url: String) {
        prefs(ctx).edit {
            putString(KEY_URL, url)
        }
    }

}