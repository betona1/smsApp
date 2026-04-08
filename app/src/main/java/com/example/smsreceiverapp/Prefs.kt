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

    // DB 서버 설정
    private const val KEY_DB_HOST = "db_host"
    private const val KEY_DB_PORT = "db_port"
    private const val KEY_DB_NAME = "db_name"
    private const val KEY_DB_USER = "db_user"
    private const val KEY_DB_PASSWORD = "db_password"
    private const val DEFAULT_DB_HOST = "192.168.219.200"
    private const val DEFAULT_DB_PORT = "3306"
    private const val DEFAULT_DB_NAME = "sms2"
    private const val DEFAULT_DB_USER = "joachamsms"
    private const val DEFAULT_DB_PASSWORD = "joachmsms#"

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

    // --- DB 서버 ---
    fun getDbHost(ctx: Context): String =
        prefs(ctx).getString(KEY_DB_HOST, DEFAULT_DB_HOST) ?: DEFAULT_DB_HOST

    fun getDbPort(ctx: Context): String =
        prefs(ctx).getString(KEY_DB_PORT, DEFAULT_DB_PORT) ?: DEFAULT_DB_PORT

    fun getDbName(ctx: Context): String =
        prefs(ctx).getString(KEY_DB_NAME, DEFAULT_DB_NAME) ?: DEFAULT_DB_NAME

    fun getDbUser(ctx: Context): String =
        prefs(ctx).getString(KEY_DB_USER, DEFAULT_DB_USER) ?: DEFAULT_DB_USER

    fun getDbPassword(ctx: Context): String =
        prefs(ctx).getString(KEY_DB_PASSWORD, DEFAULT_DB_PASSWORD) ?: DEFAULT_DB_PASSWORD

    fun setDbServer(ctx: Context, host: String, port: String, name: String, user: String, password: String) {
        prefs(ctx).edit {
            putString(KEY_DB_HOST, host)
            putString(KEY_DB_PORT, port)
            putString(KEY_DB_NAME, name)
            putString(KEY_DB_USER, user)
            putString(KEY_DB_PASSWORD, password)
        }
    }
}