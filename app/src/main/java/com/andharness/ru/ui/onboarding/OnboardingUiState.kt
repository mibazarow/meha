package com.andharness.ru.ui.onboarding

data class OnboardingUiState(
    val endpoint: String = "",
    val model: String = "",
    val apiKey: String = "",
    val isSaving: Boolean = false,
    val isConfigured: Boolean = false,
    val error: String? = null,
    /**
     * Каталог моделей из `GET {baseUrl}/models` (облачный путь). Пусто, пока список не загружен;
     * ручное поле «Имя модели» остаётся фолбэком, если провайдер список не отдаёт.
     */
    val models: List<String> = emptyList(),
    val modelsLoading: Boolean = false,
    val modelsError: String? = null,
    val modelsFetched: Boolean = false
) {

    /** «Продолжить» is allowed on the cloud path once every input is validated. */
    val canContinue: Boolean get() = !isSaving
}
