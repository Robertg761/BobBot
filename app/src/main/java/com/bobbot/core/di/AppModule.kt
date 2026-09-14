package com.bobbot.core.di

import com.bobbot.core.net.HermesClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun okHttp(): OkHttpClient = HermesClient.buildOkHttp()
}
