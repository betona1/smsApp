package com.example.smsreceiverapp

import android.content.Context
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {
    private var currentBaseUrl: String? = null
    private var apiInstance: ApiService? = null

    fun getApi(context: Context): ApiService {
        val baseUrl = Prefs.getBaseUrl(context)
        if (apiInstance == null || currentBaseUrl != baseUrl) {
            currentBaseUrl = baseUrl
            apiInstance = Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(GsonConverterFactory.create())
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
