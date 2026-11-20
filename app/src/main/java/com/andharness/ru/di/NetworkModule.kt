package com.andharness.ru.di

import com.andharness.ru.data.llm.LlmTimeouts
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * OkHttp client for streaming. readTimeout is the per-read INACTIVITY budget (not a total
     * response cap): it applies to each individual read IO operation, so as long as the server keeps
     * delivering bytes (tokens/chunks) the stream stays open. A 0 timeout would let a silent/hung
     * model block the agent forever, so we bound each silent gap to
     * [LlmTimeouts.DATA_WAIT_TIMEOUT_SEC] (the agent's "give up after ~17 min without progress"
     * policy).
     *
     * Reference (readTimeout applies to individual read IO operations, default 10 s):
     * https://square.github.io/okhttp/5.x/okhttp/okhttp3/-ok-http-client/-builder/read-timeout.html
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(LlmTimeouts.DATA_WAIT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
}
