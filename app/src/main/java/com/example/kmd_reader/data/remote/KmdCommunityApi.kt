package com.example.kmd_reader.data.remote

import com.example.kmd_reader.data.remote.dto.ReviewRequestDto
import com.example.kmd_reader.data.remote.dto.ReviewResponseDto
import com.example.kmd_reader.data.remote.dto.ScriptIssueDto
import com.example.kmd_reader.data.remote.dto.WorkDto
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface KmdCommunityApi {
    @GET("works")
    suspend fun getWorks(): List<WorkDto>

    @GET("works/{id}")
    suspend fun getWork(@Path("id") id: String): WorkDto

    @GET("works/{id}/source")
    suspend fun getWorkSource(@Path("id") id: String): ResponseBody

    @GET("works/{id}/revisions/{revisionId}/source")
    suspend fun getRevisionSource(
        @Path("id") id: String,
        @Path("revisionId") revisionId: String
    ): ResponseBody

    @GET("works/{id}/issues")
    suspend fun getIssues(@Path("id") id: String): List<ScriptIssueDto>

    @POST("reviews")
    suspend fun submitReview(@Body request: ReviewRequestDto): ReviewResponseDto
}
