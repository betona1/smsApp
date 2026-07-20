package com.bitic.smsgateway

import android.content.Context
import androidx.core.content.edit

object Prefs {
    private const val PREF_NAME = "app_prefs"
    private const val KEY_URL = "base_url"

    // API 서버 설정 (내부) — 기본값 공란: 설치 후 설정화면에서 입력
    private const val KEY_API_HOST = "api_host"
    private const val KEY_API_PORT = "api_port"
    private const val DEFAULT_API_HOST = ""
    private const val DEFAULT_API_PORT = "8080"

    // 외부 서버 설정 (선택)
    private const val KEY_EXT_HOST = "ext_api_host"
    private const val KEY_EXT_PORT = "ext_api_port"
    private const val DEFAULT_EXT_HOST = ""
    private const val DEFAULT_EXT_PORT = "8080"

    // 자동업데이트 소스 (github "owner/repo", 공란=업데이트 확인 안 함)
    private const val KEY_UPDATE_REPO = "update_repo"
    private const val DEFAULT_UPDATE_REPO = ""


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

    /** 서버 미설정(host 공란) 시 크래시 방지용 placeholder 반환 (연결만 실패, 앱은 유지) */
    fun getBaseUrl(ctx: Context): String {
        val host = getApiHost(ctx)
        val port = getApiPort(ctx)
        if (host.isBlank()) return "http://127.0.0.1:$port/"
        return "http://$host:$port/"
    }

    /** 서버 주소가 설정됐는지 여부 (설정화면 안내용) */
    fun isConfigured(ctx: Context): Boolean = getApiHost(ctx).isNotBlank()

    // --- 자동업데이트 소스 ---
    fun getUpdateRepo(ctx: Context): String =
        prefs(ctx).getString(KEY_UPDATE_REPO, DEFAULT_UPDATE_REPO) ?: DEFAULT_UPDATE_REPO

    fun setUpdateRepo(ctx: Context, repo: String) {
        prefs(ctx).edit { putString(KEY_UPDATE_REPO, repo.trim()) }
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
        if (host.isBlank()) return ""
        return "http://$host:$port/"
    }

}