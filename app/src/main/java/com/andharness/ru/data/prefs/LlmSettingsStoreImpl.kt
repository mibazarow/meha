package com.andharness.ru.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme
import androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme
import androidx.security.crypto.MasterKey
import com.andharness.ru.data.db.dao.LlmProviderDao
import com.andharness.ru.data.db.dao.SettingsDao
import com.andharness.ru.data.db.entity.LlmProviderEntity
import com.andharness.ru.data.db.entity.SettingsEntity
import com.andharness.ru.domain.llm.LlmAuthConfig
import com.andharness.ru.domain.llm.LlmAuthStyle
import com.andharness.ru.domain.locale.AppLanguage
import com.andharness.ru.domain.settings.LlmProvider
import com.andharness.ru.domain.settings.LlmSettings
import com.andharness.ru.domain.settings.LlmSettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

@Suppress("DEPRECATION")
class LlmSettingsStoreImpl @Inject constructor(
    @ApplicationContext context: Context,
    private val settingsDao: SettingsDao,
    private val providerDao: LlmProviderDao,
    private val modelContextPreferences: ModelContextPreferences,
) : LlmSettingsStore {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE,
        masterKey,
        PrefKeyEncryptionScheme.AES256_SIV,
        PrefValueEncryptionScheme.AES256_GCM
    )

    /**
     * Язык интерфейса — НЕ секрет, поэтому хранится в обычных (не шифрованных) prefs: значение
     * нужно синхронно прочитать при старте приложения, чтобы применить локаль до первой композиции.
     * Хранится как BCP-47-совместимый тег ("ru" | "en"); неизвестное значение нормализуется.
     */
    private val languagePrefs: SharedPreferences =
        context.getSharedPreferences(LANGUAGE_PREFS_FILE, Context.MODE_PRIVATE)

    private val languageFlow = MutableStateFlow(
        AppLanguage.normalize(languagePrefs.getString(LANGUAGE_PREF, null))
    )

    private suspend fun ensureDefaultRow() {
        val existing = settingsDao.get()
        if (existing == null) {
            settingsDao.upsert(DEFAULT_SETTINGS)
        }
    }

    /**
     * Serialises the first-run migration from the legacy single configuration into a provider
     * profile. Called from the start of every flow and mutator, so it runs once per install: when
     * the profile table is empty but the settings row already carries an endpoint, that legacy
     * configuration (plus its key and auth scheme) becomes the first active profile.
     */
    private val seedMutex = Mutex()

    private suspend fun ensureSeeded() {
        seedMutex.withLock {
            ensureDefaultRow()
            val row = settingsDao.get() ?: return@withLock
            val existing = providerDao.getAll()
            if (existing.isEmpty()) {
                if (row.endpoint.isBlank()) return@withLock
                val auth = readAuthPrefs()
                val id = providerDao.insert(
                    LlmProviderEntity(
                        name = "",
                        endpoint = row.endpoint,
                        model = row.model,
                        authStyle = auth.style.storageValue,
                        authHeaderName = auth.headerName,
                        authQueryParam = auth.queryParam,
                    )
                )
                // The legacy key (if any) belongs to this configuration; copy it under the new id.
                encryptedPrefs.getString(API_KEY_PREF, null)?.let { key ->
                    encryptedPrefs.edit().putString(apiKeyPref(id), key).apply()
                }
                settingsDao.update(row.copy(activeProviderId = id))
            } else if (row.activeProviderId == null) {
                // Rows exist but nothing is marked active (e.g. restored backup): pick the first.
                val first = existing.first()
                settingsDao.update(
                    row.copy(
                        activeProviderId = first.id,
                        endpoint = first.endpoint,
                        model = first.model,
                    )
                )
            }
        }
    }

    private fun apiKeyPref(providerId: Long): String = "llm_api_key_p$providerId"

    /**
     * Reads the auth scheme from the encrypted preferences. This is the legacy location that also
     * mirrors the active profile, so a store without profiles keeps working unchanged.
     */
    private fun readAuthPrefs(): LlmAuthConfig = LlmAuthConfig(
        style = LlmAuthStyle.fromStorage(encryptedPrefs.getString(AUTH_STYLE_PREF, null)),
        headerName = encryptedPrefs.getString(AUTH_HEADER_NAME_PREF, "").orEmpty(),
        queryParam = encryptedPrefs.getString(AUTH_QUERY_PARAM_PREF, "").orEmpty(),
    )

    private fun writeAuthPrefs(config: LlmAuthConfig) {
        encryptedPrefs.edit()
            .putString(AUTH_STYLE_PREF, config.style.storageValue)
            .putString(AUTH_HEADER_NAME_PREF, config.headerName)
            .putString(AUTH_QUERY_PARAM_PREF, config.queryParam)
            .apply()
    }

    private fun toDomain(entity: LlmProviderEntity): LlmProvider = LlmProvider(
        id = entity.id,
        name = entity.name,
        endpoint = entity.endpoint,
        model = entity.model,
        auth = LlmAuthConfig(
            style = LlmAuthStyle.fromStorage(entity.authStyle),
            headerName = entity.authHeaderName,
            queryParam = entity.authQueryParam,
        ),
    )

    private fun toSettings(entity: SettingsEntity): LlmSettings = LlmSettings(
        endpoint = entity.endpoint,
        model = entity.model,
        activeProviderId = entity.activeProviderId,
        contextCompressionTokens = entity.contextCompressionTokens,
        reasoningEffort = entity.reasoningEffort,
        // Старый столбец `embeddingModel` больше не читается: семантику включает только
        // встроенная модель и булев переключатель.
        semanticSearchEnabled = entity.semanticSearchEnabled
    )

    override val config: Flow<LlmSettings> = combine(
        settingsDao.observe(),
        languageFlow,
    ) { entity, language ->
        val base = if (entity == null) toSettings(DEFAULT_SETTINGS) else toSettings(entity)
        // Язык живёт в обычных prefs (не в строке настроек), поэтому подмешивается здесь: так UI
        // получает единый поток настроек, а смена языка мгновенно переиспускает config.
        base.copy(language = language)
    }.onStart { ensureSeeded() }

    override val providers: Flow<List<LlmProvider>> = providerDao.observeAll()
        .onStart { ensureSeeded() }
        .map { entities -> entities.map(::toDomain) }

    override suspend fun updateEndpoint(value: String) {
        ensureSeeded()
        val row = settingsDao.get() ?: DEFAULT_SETTINGS
        row.activeProviderId?.let { id ->
            providerDao.get(id)?.let { providerDao.update(it.copy(endpoint = value)) }
        }
        settingsDao.update(row.copy(endpoint = value))
    }

    override suspend fun updateModel(value: String) {
        ensureSeeded()
        val row = settingsDao.get() ?: DEFAULT_SETTINGS
        row.activeProviderId?.let { id ->
            providerDao.get(id)?.let { providerDao.update(it.copy(model = value)) }
        }
        settingsDao.update(row.copy(model = value))
    }

    override suspend fun updateReasoningEffort(value: String) {
        ensureDefaultRow()
        settingsDao.update(settingsDao.get()?.copy(reasoningEffort = value) ?: DEFAULT_SETTINGS)
    }

    override suspend fun updateSemanticSearchEnabled(value: Boolean) {
        ensureDefaultRow()
        settingsDao.update(settingsDao.get()?.copy(semanticSearchEnabled = value) ?: DEFAULT_SETTINGS)
    }

    override suspend fun updateContextCompressionTokens(value: Long) {
        ensureDefaultRow()
        settingsDao.update(
            settingsDao.get()?.copy(contextCompressionTokens = value)
                ?: DEFAULT_SETTINGS.copy(contextCompressionTokens = value)
        )
    }

    override suspend fun updateLanguage(value: String) {
        val normalized = AppLanguage.normalize(value)
        languagePrefs.edit().putString(LANGUAGE_PREF, normalized).apply()
        languageFlow.value = normalized
    }

    override fun currentLanguage(): String = languageFlow.value

    /**
     * «Выбран ли язык явно» читается из ВЫДЕЛЕННОГО булева ключа [LANGUAGE_CHOSEN_PREF], который
     * пишет только экран выбора ([markLanguageChosen]). Он намеренно НЕ выводится из присутствия
     * [LANGUAGE_PREF]: значение языка может существовать (в т.ч. по умолчанию «ru») до первого
     * явного выбора, из-за чего экран выбора ошибочно пропускался бы на первом запуске.
     */
    override fun hasChosenLanguage(): Boolean = languagePrefs.getBoolean(LANGUAGE_CHOSEN_PREF, false)

    override fun markLanguageChosen() {
        languagePrefs.edit().putBoolean(LANGUAGE_CHOSEN_PREF, true).apply()
    }

    override suspend fun getApiKey(): String? {
        ensureSeeded()
        val activeId = settingsDao.get()?.activeProviderId
        if (activeId != null) {
            encryptedPrefs.getString(apiKeyPref(activeId), null)?.let { return it }
        }
        // Legacy location (also the mirror of the active profile), used before profiles existed and
        // as a fallback for a profile whose key was never set separately.
        return encryptedPrefs.getString(API_KEY_PREF, null)
    }

    override suspend fun setApiKey(key: String) {
        ensureSeeded()
        val activeId = settingsDao.get()?.activeProviderId
        encryptedPrefs.edit().putString(API_KEY_PREF, key).apply()
        if (activeId != null) {
            encryptedPrefs.edit().putString(apiKeyPref(activeId), key).apply()
        }
    }

    override suspend fun clearApiKey() {
        val activeId = settingsDao.get()?.activeProviderId
        encryptedPrefs.edit().remove(API_KEY_PREF).apply()
        if (activeId != null) {
            encryptedPrefs.edit().remove(apiKeyPref(activeId)).apply()
        }
    }

    // The auth scheme lives in the same encrypted file as the API key; the active profile keeps
    // its own copy in `llm_providers`, mirrored here so profile-unaware readers see the same value.
    override suspend fun getAuthConfig(): LlmAuthConfig {
        ensureSeeded()
        val activeId = settingsDao.get()?.activeProviderId
        if (activeId != null) {
            providerDao.get(activeId)?.let { provider ->
                return LlmAuthConfig(
                    style = LlmAuthStyle.fromStorage(provider.authStyle),
                    headerName = provider.authHeaderName,
                    queryParam = provider.authQueryParam,
                )
            }
        }
        return readAuthPrefs()
    }

    override suspend fun setAuthConfig(config: LlmAuthConfig) {
        ensureSeeded()
        writeAuthPrefs(config)
        settingsDao.get()?.activeProviderId?.let { id ->
            providerDao.get(id)?.let { provider ->
                providerDao.update(
                    provider.copy(
                        authStyle = config.style.storageValue,
                        authHeaderName = config.headerName,
                        authQueryParam = config.queryParam,
                    )
                )
            }
        }
    }

    override suspend fun saveProvider(
        provider: LlmProvider,
        apiKey: String?,
        activate: Boolean,
    ): Long {
        ensureSeeded()
        val entity = LlmProviderEntity(
            id = provider.id,
            name = provider.name.trim(),
            endpoint = provider.endpoint.trim(),
            model = provider.model.trim(),
            authStyle = provider.auth.style.storageValue,
            authHeaderName = provider.auth.headerName.trim(),
            authQueryParam = provider.auth.queryParam.trim(),
        )
        val id = if (provider.id == LlmProvider.UNSAVED_ID) {
            providerDao.insert(entity.copy(id = 0L))
        } else {
            val createdAt = providerDao.get(provider.id)?.createdAt
                ?: System.currentTimeMillis()
            providerDao.update(entity.copy(createdAt = createdAt))
            provider.id
        }
        if (!apiKey.isNullOrBlank()) {
            encryptedPrefs.edit().putString(apiKeyPref(id), apiKey).apply()
        }
        if (activate) {
            activateInternal(id)
        }
        return id
    }

    override suspend fun activateProvider(id: Long) {
        ensureSeeded()
        activateInternal(id)
    }

    /** Makes [id] active: mirrors its endpoint/model/auth/key into the single effective config. */
    private suspend fun activateInternal(id: Long) {
        val provider = providerDao.get(id) ?: return
        val row = settingsDao.get() ?: DEFAULT_SETTINGS
        settingsDao.update(
            row.copy(
                activeProviderId = id,
                endpoint = provider.endpoint,
                model = provider.model,
            )
        )
        val auth = LlmAuthConfig(
            style = LlmAuthStyle.fromStorage(provider.authStyle),
            headerName = provider.authHeaderName,
            queryParam = provider.authQueryParam,
        )
        writeAuthPrefs(auth)
        // Keep the legacy key slot an exact mirror of the active profile, so the fallback in
        // [getApiKey] can never hand one provider's key to a different one.
        val key = encryptedPrefs.getString(apiKeyPref(id), null)
        if (key != null) {
            encryptedPrefs.edit().putString(API_KEY_PREF, key).apply()
        } else {
            encryptedPrefs.edit().remove(API_KEY_PREF).apply()
        }
    }

    override suspend fun deleteProvider(id: Long) {
        ensureSeeded()
        val wasActive = settingsDao.get()?.activeProviderId == id
        providerDao.delete(id)
        encryptedPrefs.edit().remove(apiKeyPref(id)).apply()
        if (wasActive) {
            val next = providerDao.getAll().firstOrNull()
            if (next != null) {
                activateInternal(next.id)
            } else {
                settingsDao.update(
                    (settingsDao.get() ?: DEFAULT_SETTINGS).copy(
                        activeProviderId = null,
                        endpoint = "",
                        model = "",
                    )
                )
                encryptedPrefs.edit().remove(API_KEY_PREF).apply()
            }
        }
    }

    override suspend fun providerApiKey(id: Long): String? {
        ensureSeeded()
        return encryptedPrefs.getString(apiKeyPref(id), null)
    }

    override suspend fun reset() {
        val providerIds = providerDao.getAll().map { it.id }
        providerDao.deleteAll()
        providerIds.forEach { encryptedPrefs.edit().remove(apiKeyPref(it)).apply() }
        settingsDao.upsert(DEFAULT_SETTINGS)
        clearApiKey()
        resetAuthConfig()
        modelContextPreferences.clear()
    }

    private fun resetAuthConfig() {
        encryptedPrefs.edit()
            .remove(AUTH_STYLE_PREF)
            .remove(AUTH_HEADER_NAME_PREF)
            .remove(AUTH_QUERY_PARAM_PREF)
            .apply()
    }

    override fun isConfigured(): Flow<Boolean> = config.map { settings ->
        // API-ключ не обязателен (OpenAI-совместимые серверы работают без него) — достаточно адреса
        // эндпоинта.
        settings.endpoint.isNotBlank()
    }

    companion object {
        const val PREFS_FILE = "andharness_encrypted_prefs"
        private const val API_KEY_PREF = "llm_api_key"
        private const val AUTH_STYLE_PREF = "llm_auth_style"
        private const val AUTH_HEADER_NAME_PREF = "llm_auth_header_name"
        private const val AUTH_QUERY_PARAM_PREF = "llm_auth_query_param"

        /** Обычные (не шифрованные) prefs: язык интерфейса — не секрет. */
        private const val LANGUAGE_PREFS_FILE = "andharness_locale_prefs"
        private const val LANGUAGE_PREF = "app_language"

        /**
         * Выделенный флаг «язык выбран ЯВНО в экране выбора». Отдельный от [LANGUAGE_PREF]:
         * значение языка может быть записано по умолчанию, а флаг — только явным выбором.
         */
        private const val LANGUAGE_CHOSEN_PREF = "language_chosen"

        private val DEFAULT_SETTINGS = SettingsEntity(
            id = 1,
            endpoint = "",
            model = "",
            contextCompressionTokens = LlmSettings.DEFAULT_CONTEXT_COMPRESSION_TOKENS,
            reasoningEffort = LlmSettings.DEFAULT_REASONING_EFFORT
        )
    }
}
