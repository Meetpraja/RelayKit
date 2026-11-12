package com.relaykit.network.internal.http

import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Url
import retrofit2.http.Multipart
import retrofit2.http.Part

internal interface RetrofitService {
    @GET suspend fun get(@Url url: String, @retrofit2.http.HeaderMap headers: Map<String, String>): Response<ResponseBody>
    @POST suspend fun post(@Url url: String, @retrofit2.http.HeaderMap headers: Map<String, String>, @Body body: RequestBody?): Response<ResponseBody>
    @PUT suspend fun put(@Url url: String, @retrofit2.http.HeaderMap headers: Map<String, String>, @Body body: RequestBody?): Response<ResponseBody>
    @PATCH suspend fun patch(@Url url: String, @retrofit2.http.HeaderMap headers: Map<String, String>, @Body body: RequestBody?): Response<ResponseBody>
    @DELETE suspend fun delete(@Url url: String, @retrofit2.http.HeaderMap headers: Map<String, String>): Response<ResponseBody>
    @Multipart @POST suspend fun multipartPost(@Url url: String, @retrofit2.http.HeaderMap headers: Map<String, String>, @Part parts: List<MultipartBody.Part>): Response<ResponseBody>
    @Multipart @PUT suspend fun multipartPut(@Url url: String, @retrofit2.http.HeaderMap headers: Map<String, String>, @Part parts: List<MultipartBody.Part>): Response<ResponseBody>
    @Multipart @PATCH suspend fun multipartPatch(@Url url: String, @retrofit2.http.HeaderMap headers: Map<String, String>, @Part parts: List<MultipartBody.Part>): Response<ResponseBody>
}
