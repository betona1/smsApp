package com.example.smsreceiverapp

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.DELETE
import retrofit2.http.Path

// 서버에서 설정 리스트를 받아오고, 문자 수신 정보를 서버로 전송
interface ApiService {

    // 문자 수신 정보 서버에 저장
    @POST("api/cpc/sms/receive/")
    suspend fun sendReceivedSMS(
        @Body sms: ReceivedSMSRequest
    ): retrofit2.Response<Void>

    // 한번만 호출되는 설정 목록 조회
    @GET("api/settings/")
    suspend fun getCSPhoneSettings(): retrofit2.Response<List<CSPhoneSettingResponse>>

    @POST("api/sms/receive/")  // 너 서버 엔드포인트에 맞춰 경로 수정 필요
    suspend fun sendSMS(@Body sms: ReceivedSMSRequest): Response<Any>

    @POST("api/settings/add/")
    suspend fun createCSPhoneSetting(@Body setting: CSPhoneSettingRequest): Response<CSPhoneSettingResponse>

    @PUT("api/settings/{id}/")
    suspend fun updateCSPhoneSetting(@Path("id") id: Int, @Body setting: CSPhoneSettingRequest): Response<CSPhoneSettingResponse>

    @DELETE("api/settings/{id}/delete/")
    suspend fun deleteCSPhoneSetting(@Path("id") id: Int): Response<Void>

    @PUT("api/settings/{id}/")
    suspend fun updateCSPhoneSetting(
        @Path("id") id: Int,
        @Body setting: CSPhoneSettingResponse
    ): Response<CSPhoneSettingResponse>

}

