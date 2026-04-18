package com.example.smsreceiverapp

import android.content.Context
import androidx.core.content.edit

object Prefs {
    private const val PREF_NAME = "app_prefs"
    private const val KEY_URL = "base_url"
    private const val DEFAULT_URL = "http://192.168.219.100:8010/"

    // API 서버 설정 (내부)
    private const val KEY_API_HOST = "api_host"
    private const val KEY_API_PORT = "api_port"
    private const val DEFAULT_API_HOST = "192.168.219.100"
    private const val DEFAULT_API_PORT = "8379"

    // 외부 서버 설정
    private const val KEY_EXT_HOST = "ext_api_host"
    private const val KEY_EXT_PORT = "ext_api_port"
    private const val DEFAULT_EXT_HOST = "106.247.220.118"
    private const val DEFAULT_EXT_PORT = "8379"


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

    // --- 외부 서버 ---
    fun getExtHost(ctx: Context): String =
        prefs(ctx).getString(KEY_EXT_HOST, DEFAULT_EXT_HOST) ?: DEFAULT_EXT_HOST

    fun getExtPort(ctx: Context): String =
        prefs(ctx).getString(KEY_EXT_PORT, DEFAULT_EXT_PORT) ?: DEFAULT_EXT_PORT

    fun setExtServer(ctx: Context, host: String, port: String) {
        prefs(ctx).edit {
            putString(KEY_EXT_HOST, host)
            putString(KEY_EXT_PORT, port)
        }
    }

    fun getExtBaseUrl(ctx: Context): String {
        val host = getExtHost(ctx)
        val port = getExtPort(ctx)
        return "http://$host:$port/"
    }

}