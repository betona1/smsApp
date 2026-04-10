package com.example.smsreceiverapp

import android.content.Context
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.lang.reflect.Type

object RetrofitClient {
    private var currentBaseUrl: String? = null
    private var apiInstance: ApiService? = null

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

    fun getApi(context: Context): ApiService {
        val baseUrl = Prefs.getBaseUrl(context)
        if (apiInstance == null || currentBaseUrl != baseUrl) {
            currentBaseUrl = baseUrl
            apiInstance = Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
                .create(ApiService::class.java)
        }
        return apiInstance!!
    }

    // 서버 주소 변경 시 인스턴스 재생성 강제
    fun reset() {
        apiInstance = null
        currentBaseUrl = null
    }
}
