package com.andharness.ru.ui.license

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.andharness.ru.R
import com.andharness.ru.ui.components.AccentPanel
import com.andharness.ru.ui.components.EnterAnimationTracker
import com.andharness.ru.ui.components.PrimaryGradientButton
import com.andharness.ru.ui.components.enterAnimation
import com.andharness.ru.ui.settings.SettingsHelperText
import com.andharness.ru.ui.settings.SettingsSection

/**
 * Первый экран приложения: лицензионное соглашение (EULA). Показывается до онбординга при первом
 * запуске и после каждого изменения текста, и требует явного принятия.
 *
 * Дизайн повторяет онбординг (`OnboardingScreen`): без app bar, заголовок слева; тело —
 * вертикальный скролл, приведённый к единому стилю внутренних экранов (партия 4) — предупреждение
 * и условия оформлены [SettingsSection]-карточками со скруглённой `surfaceContainerLow`-поверхностью
 * и `titleMedium`-заголовками; внизу — основной CTA [PrimaryGradientButton] и «выход» при отказе в
 * цвете `error`. Блоки входят каскадом через [enterAnimation]; вся копия локализована
 * (`stringResource`, RU/EN).
 *
 * Прямо над CTA стоит заметная акцентная плашка [AccentPanel] (иконка `Icons.Filled.Info`) —
 * честное предупреждение, что после установки докачается ~1 ГБ моделей для офлайн-функций
 * (семантический поиск и распознавание речи), лучше по Wi-Fi. Тот же акцентный стиль и те же строки
 * (`onboarding_download_notice*`), что и у заметки на финальном шаге онбординга
 * (`OnboardingActionsSection`); это только вывод, на логику согласия он не влияет.
 *
 * «Отказаться и выйти» завершает приложение: [Activity.finishAffinity] закрывает текущую активити
 * и все, что ниже неё в задаче, поэтому приложение нельзя использовать дальше.
 * Ссылка (официальная документация):
 * https://developer.android.com/reference/android/app/Activity#finishAffinity()
 *
 * @param onAccepted вызывается после фиксации согласия; хосту следует перейти к онбордингу и
 *   убрать экран лицензии из back stack.
 *
 * Docs:
 * - String resources in Compose (`stringResource`) —
 *   https://developer.android.com/develop/ui/compose/resources#string-resources
 * - Material 3 typography roles (`headlineSmall` / `bodyMedium` / `bodySmall`) —
 *   https://m3.material.io/styles/typography/type-scale-tokens
 * - Material icon vectors (`Icons.Filled.Info`) —
 *   https://developer.android.com/develop/ui/compose/graphics/images/material#icons
 */
@Composable
fun LicenseScreen(
    onAccepted: () -> Unit,
    viewModel: LicenseViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    // Трекер входной анимации: контент входит один раз за визит экрана.
    val tracker = remember { EnterAnimationTracker() }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Заголовок анимируется один раз за визит экрана; переключатель темы живёт в «Настройках».
            Text(
                text = stringResource(R.string.license_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.enterAnimation(
                    index = 0,
                    tracker = tracker,
                    itemKey = "lic_title"
                )
            )

            SettingsSection(
                title = stringResource(R.string.license_warning_title),
                modifier = Modifier.enterAnimation(
                    index = 1,
                    tracker = tracker,
                    itemKey = "lic_warning"
                )
            ) {
                Text(
                    text = stringResource(R.string.license_warning_body),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }

            SettingsSection(
                title = stringResource(R.string.license_conditions_title),
                modifier = Modifier.enterAnimation(
                    index = 2,
                    tracker = tracker,
                    itemKey = "lic_terms"
                )
            ) {
                val terms = listOf(
                    R.string.license_term_1,
                    R.string.license_term_2,
                    R.string.license_term_3,
                    R.string.license_term_4,
                    R.string.license_term_5,
                    R.string.license_term_6,
                    R.string.license_term_7,
                    R.string.license_term_8
                )
                terms.forEach { term ->
                    Text(
                        text = stringResource(term),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            SettingsHelperText(
                text = stringResource(R.string.license_footer),
                modifier = Modifier.enterAnimation(
                    index = 3,
                    tracker = tracker,
                    itemKey = "lic_footer"
                )
            )

            // Заметная акцентная плашка прямо над кнопкой согласия: честно предупреждаем, что после
            // установки докачается ~1 ГБ моделей для офлайн-функций (семантический поиск по устройству
            // и распознавание речи), лучше по Wi-Fi. Тот же акцентный стиль и строки, что и на
            // финальном шаге онбординга; логику согласия не затрагивает.
            AccentPanel(
                label = stringResource(R.string.onboarding_download_notice_label),
                icon = Icons.Filled.Info,
                modifier = Modifier
                    .fillMaxWidth()
                    .enterAnimation(
                        index = 4,
                        tracker = tracker,
                        itemKey = "lic_download_notice"
                    )
            ) {
                Text(
                    text = stringResource(R.string.onboarding_download_notice),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            PrimaryGradientButton(
                text = stringResource(R.string.license_accept),
                onClick = {
                    viewModel.accept()
                    onAccepted()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .enterAnimation(
                        index = 4,
                        tracker = tracker,
                        itemKey = "lic_accept"
                    )
            )

            // Отказ — явный выход: приложение закрывается и не может использоваться.
            OutlinedButton(
                onClick = { exitApplication(context) },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .enterAnimation(
                        index = 4,
                        tracker = tracker,
                        itemKey = "lic_decline"
                    )
            ) {
                Text(
                    text = stringResource(R.string.license_decline),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/** Закрывает приложение: завершает текущую активити и всю задачу. */
private fun exitApplication(context: Context) {
    val activity = context.findActivity() ?: return
    activity.finish()
    activity.finishAffinity()
}

/** Разворачивает [ContextWrapper]-обёртки Compose до реальной [Activity]. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
