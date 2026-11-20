package com.andharness.ru.di

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt wiring for PdfBox-Android.
 *
 * [PDFBoxResourceLoader.init] must run exactly once, before any PdfBox API is used:
 * PdfBox loads its CMaps/font resources through an Android `AssetManager`, and without
 * this call it cannot resolve resource streams at runtime.
 *
 * Reference: https://github.com/TomRoush/PdfBox-Android
 */
@Module
@InstallIn(SingletonComponent::class)
object PdfModule {

    /**
     * Marker injected where PdfBox is used, guaranteeing [PDFBoxResourceLoader.init] ran first.
     * A plain `Unit`/`Boolean` cannot be a distinct Hilt binding, hence a dedicated type.
     */
    class PdfBoxInitializer

    @Provides
    @Singleton
    fun providePdfBoxInitializer(@ApplicationContext context: Context): PdfBoxInitializer {
        PDFBoxResourceLoader.init(context.applicationContext)
        return PdfBoxInitializer()
    }
}
