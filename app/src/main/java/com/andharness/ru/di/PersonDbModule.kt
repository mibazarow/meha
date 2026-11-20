package com.andharness.ru.di

import com.andharness.ru.data.db.AppDatabase
import com.andharness.ru.data.db.dao.InteractionDao
import com.andharness.ru.data.db.dao.PersonAliasDao
import com.andharness.ru.data.db.dao.PersonDao
import com.andharness.ru.data.db.dao.PersonIdentityDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Unified-personal-base DAO providers (persons, identities, interactions, aliases). */
@Module
@InstallIn(SingletonComponent::class)
object PersonDbModule {

    @Provides
    fun providePersonDao(database: AppDatabase): PersonDao = database.personDao()

    @Provides
    fun providePersonIdentityDao(database: AppDatabase): PersonIdentityDao =
        database.personIdentityDao()

    @Provides
    fun provideInteractionDao(database: AppDatabase): InteractionDao = database.interactionDao()

    @Provides
    fun providePersonAliasDao(database: AppDatabase): PersonAliasDao = database.personAliasDao()
}
