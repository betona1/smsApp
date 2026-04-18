package com.example.smsreceiverapp

import android.content.Context
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    private var currentBaseUrl: String? = null
    private var apiInstance: ApiService? = null

    // 현재 연결된 URL (UI에서 참조)
    var activeBaseUrl: String? = null
        private set

    // 외부 연결 여부 (UI에서 참조)
    var isExternal: Boolean = false
        private set

    // 서버에서 Boolean을 0/1 숫자 또는 true/false 둘 다 처리
    private val booleanDeserializer = JsonDeserializer<Boolean> { json, _, _ ->
        try {
            val prim = json.asJsonPrimitive
            when {
                prim.isBoolean -> prim.asBoolean
                prim.isNumber -> prim.asInt != 0
                prim.isString -> {
                    val s = prim.asString.lowercase()
                    s == "true" || s == "1"
                }
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }

    private val gson = GsonBuilder()
        .registerTypeAdapter(Boolean::class.java, booleanDeserializer)
        .registerTypeAdapter(Boolean::class.javaPrimitiveType, booleanDeserializer)
        .create()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun getApi(context: Context): ApiService {
        val baseUrl = activeBaseUrl ?: Prefs.getBaseUrl(context)
        if (apiInstance == null || currentBaseUrl != baseUrl) {
            currentBaseUrl = baseUrl
            apiInstance = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(httpClient)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
                .create(ApiService::class.java)
        }
        return apiInstance!!
    }

    /**
     * 내부 → 외부 페일오버 체크.
     * 내부 URL 먼저 시도, 실패 시 외부 시도.
     * @return true=연결 성공, false=둘 다 실패
     */
    suspend fun checkAndSwitch(context: Context): Boolean {
        val internalUrl = Prefs.getBaseUrl(context)
        val externalUrl = Prefs.getExtBaseUrl(context)

        // 1. 내부 시도
        if (tryConnect(internalUrl)) {
            if (activeBaseUrl != internalUrl) {
                activeBaseUrl = internalUrl
                isExternal = false
                apiInstance = null
            }
            return true
        }

        // 2. 외부 시도
        if (tryConnect(externalUrl)) {
            if (activeBaseUrl != externalUrl) {
                activeBaseUrl = externalUrl
                isExternal = true
                apiInstance = null
            }
            return true
        }

        return false
    }

    private suspend fun tryConnect(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${baseUrl}api/settings/")
                .head()
                .build()
            val response = httpClient.newCall(request).execute()
            response.close()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    fun reset() {
        apiInstance = null
        currentBaseUrl = null
        activeBaseUrl = null
        isExternal = false
    }
}
