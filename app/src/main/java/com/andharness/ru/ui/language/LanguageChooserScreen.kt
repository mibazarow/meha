package com.andharness.ru.ui.language

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andharness.ru.R
import com.andharness.ru.domain.locale.AppLanguage
import com.andharness.ru.ui.components.EnterAnimationTracker
import com.andharness.ru.ui.components.enterAnimation

// Тексты выбора языка ЖЁСТКО двуязычны (RU + EN): локаль ещё не выбрана, поэтому этот экран
// обязан быть понятен носителю любого из двух языков сразу, а не переводиться под текущую локаль.
private const val CHOOSER_TITLE = "Выберите язык / Choose language"
private const val CHOOSER_HINT_RU = "Язык интерфейса можно изменить позже в настройках."
private const val CHOOSER_HINT_EN = "You can change the interface language later in Settings."

/**
 * Самый первый экран приложения на первом запуске: аккуратное центрированное «окошко» с выбором
 * языка интерфейса («Русский» / «English») — ПОКАЗЫВАЕТСЯ ДО лицензии, чтобы соглашение и весь
 * дальнейший поток сразу отрисовались на выбранном языке.
 *
 * Оформлено на токенах темы: карточка — `Surface` формы `shapes.extraLarge` с цветом
 * `surfaceContainerLow` и тонкой рамкой `outlineVariant`, бренд-название — `headlineMedium` в
 * `primary`, заголовок — `titleMedium`; варианты — крупные полноширинные карточки
 * `surfaceContainerHigh` (`shapes.large`); подсказка — muted `bodySmall`/`onSurfaceVariant`.
 * Карточка и варианты входят каскадом ([enterAnimation]), как на лицензии и онбординге.
 *
 * Экран ДВУЯЗЫЧНЫЙ и НЕ использует `stringResource`: локаль ещё не выбрана, поэтому заголовок
 * («Выберите язык / Choose language» одной строкой) и подсказка (строка RU, затем строка EN)
 * заданы фиксированными константами (см. `CHOOSER_TITLE` / `CHOOSER_HINT_*`), чтобы быть понятными
 * сразу на обоих языках. Названия языков — самоназвания («Русский» / «English»).
 *
 * Выбор применяется немедленно в [LanguageChooserViewModel.choose]: локаль переключается, активность
 * пересоздаётся, а [LaunchedEffect] по [LanguageChooserViewModel.chosen] уводит дальше (на лицензию) —
 * в том числе после пересоздания, когда восстановленный back stack снова показывает этот экран.
 *
 * @param onChosen вызывается ровно один раз после выбора языка; хост переходит к лицензии.
 *
 * Docs:
 * - Per-app language preferences —
 *   https://developer.android.com/guide/topics/resources/app-languages
 * - Material 3 components (`Scaffold` / `Surface`) —
 *   https://developer.android.com/develop/ui/compose/components ,
 *   https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary#Surface(androidx.compose.ui.Modifier,androidx.compose.ui.graphics.Shape,androidx.compose.ui.graphics.Color,androidx.compose.ui.graphics.Color,androidx.compose.ui.unit.Dp,androidx.compose.ui.unit.Dp,androidx.compose.foundation.BorderStroke,kotlin.Function0)
 * - `Modifier.clickable` (`role` / `onClick` overload) —
 *   https://developer.android.com/reference/kotlin/androidx/compose/foundation/package-summary#(androidx.compose.ui.Modifier).clickable(kotlin.Boolean,kotlin.String,androidx.compose.ui.semantics.Role,kotlin.Function0)
 * - String resources in Compose (`stringResource`) —
 *   https://developer.android.com/develop/ui/compose/resources#string-resources
 * - Text styling / one-line truncation (`maxLines`, `softWrap`, `overflow`, `TextOverflow`) —
 *   https://developer.android.com/develop/ui/compose/text/style-text ,
 *   https://developer.android.com/reference/kotlin/androidx/compose/ui/text/style/TextOverflow ,
 *   https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary#Text(kotlin.String,androidx.compose.ui.Modifier,androidx.compose.ui.graphics.Color,androidx.compose.ui.unit.TextUnit,androidx.compose.ui.text.font.FontStyle,androidx.compose.ui.text.font.FontWeight,androidx.compose.ui.text.font.FontFamily,androidx.compose.ui.unit.TextUnit,androidx.compose.ui.text.style.TextDecoration,androidx.compose.ui.text.style.TextAlign,androidx.compose.ui.unit.TextUnit,androidx.compose.ui.text.style.TextOverflow,kotlin.Boolean,kotlin.Int,kotlin.Int,kotlin.collections.Map,kotlin.Function1)
 * - `collectAsStateWithLifecycle` —
 *   https://developer.android.com/reference/kotlin/androidx/lifecycle/compose/package-summary#collectAsStateWithLifecycle(androidx.compose.runtime.State,kotlin.Any,kotlin.coroutines.CoroutineContext)
 */
@Composable
fun LanguageChooserScreen(
    onChosen: () -> Unit,
    viewModel: LanguageChooserViewModel = hiltViewModel(),
) {
    val chosen by viewModel.chosen.collectAsStateWithLifecycle()
    val tracker = remember { EnterAnimationTracker() }

    // Одна и та же реакция и при первом выборе, и после пересоздания активности (VM сохраняется):
    // как только язык выбран — уходим дальше по потоку.
    LaunchedEffect(chosen) {
        if (chosen) onChosen()
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .enterAnimation(index = 0, tracker = tracker, itemKey = "lang_card"),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    // Двуязычный заголовок ОДНОЙ строкой: крупнее — и «Выберите язык / Choose language»
                    // уже не помещается по ширине на 411dp-экране, поэтому берём titleMedium и жёстко
                    // запрещаем перенос (`maxLines = 1`, `softWrap = false`) — вместо кривого переноса
                    // на две строки текст при нехватке места аккуратно усечётся многоточием.
                    Text(
                        text = CHOOSER_TITLE,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val options = listOf(
                            AppLanguage.RUSSIAN to stringResource(R.string.language_russian),
                            AppLanguage.ENGLISH to stringResource(R.string.language_english)
                        )
                        options.forEachIndexed { index, (tag, label) ->
                            LanguageOption(
                                label = label,
                                onClick = { viewModel.choose(tag) },
                                modifier = Modifier.enterAnimation(
                                    index = index + 1,
                                    tracker = tracker,
                                    itemKey = "lang_option_$tag"
                                )
                            )
                        }
                    }

                    // Подсказка ПОД вариантами, ровно двумя строками (RU, затем EN), приглушённая и
                    // мелкая. 11sp без трекинга — чтобы более широкая кириллица гарантированно уложилась
                    // в одну строку и не «разъехалась» на третью (на 411dp-экране bodySmall 12sp её рвёт).
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = CHOOSER_HINT_RU,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            letterSpacing = 0.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = CHOOSER_HINT_EN,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            letterSpacing = 0.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

/**
 * Один крупный полноширинный вариант языка: скруглённая карточка `shapes.large` на нейтральном
 * `surfaceContainerHigh` с центрированным самоназванием языка. Вся карточка — один тап-таргет
 * (`Modifier.clickable` с ролью кнопки), поэтому выбрать можно с любого места строки.
 */
@Composable
private fun LanguageOption(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp)
        )
    }
}
