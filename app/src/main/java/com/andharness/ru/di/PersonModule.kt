package com.andharness.ru.di

import com.andharness.ru.data.person.InteractionRepositoryImpl
import com.andharness.ru.data.person.PersonAliasRepositoryImpl
import com.andharness.ru.data.person.PersonIdentityRepositoryImpl
import com.andharness.ru.data.person.PersonRepositoryImpl
import com.andharness.ru.domain.person.InteractionRepository
import com.andharness.ru.domain.person.PersonAliasRepository
import com.andharness.ru.domain.person.PersonIdentityRepository
import com.andharness.ru.domain.person.PersonRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the unified-personal-base repositories (update.md §4.3). */
@Module
@InstallIn(SingletonComponent::class)
abstract class PersonModule {

    @Binds
    @Singleton
    abstract fun bindPersonRepository(impl: PersonRepositoryImpl): PersonRepository

    @Binds
    @Singleton
    abstract fun bindPersonIdentityRepository(
        impl: PersonIdentityRepositoryImpl
    ): PersonIdentityRepository

    @Binds
    @Singleton
    abstract fun bindInteractionRepository(impl: InteractionRepositoryImpl): InteractionRepository

    @Binds
    @Singleton
    abstract fun bindPersonAliasRepository(impl: PersonAliasRepositoryImpl): PersonAliasRepository
}
