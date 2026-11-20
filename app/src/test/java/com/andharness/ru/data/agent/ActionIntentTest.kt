package com.andharness.ru.data.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionIntentTest {

    @Test
    fun actionRequestDetectsImperativeDeviceActions() {
        assertTrue("включи фонарик", looksLikeActionRequest("включи фонарик"))
        assertTrue("выключи фонарик", looksLikeActionRequest("выключи фонарик"))
        assertTrue("отключи bluetooth", looksLikeActionRequest("отключи bluetooth"))
        assertTrue("скачай файл", looksLikeActionRequest("скачай https://x/f.zip в папку ANDharness"))
        assertTrue("создай папку", looksLikeActionRequest("создай папку Documents/Projects"))
        assertTrue("отправь смс", looksLikeActionRequest("отправь смс на номер 12345 текст привет"))
        assertTrue("english: turn on", looksLikeActionRequest("turn on the flashlight"))
        assertTrue("english: open Documents", looksLikeActionRequest("open the Documents folder"))
    }

    @Test
    fun actionRequestRejectsQuestionsAndCasualMessages() {
        assertFalse("вопрос про состояние", looksLikeActionRequest("включён ли сейчас фонарик?"))
        assertFalse("вопрос с ?", looksLikeActionRequest("какая сейчас громкость?"))
        assertFalse("приветствие", looksLikeActionRequest("привет, как дела?"))
        assertFalse("пустая строка", looksLikeActionRequest(""))
    }

    @Test
    fun explicitRefusalDetectsHonestInability() {
        assertTrue("не удалось включить", looksLikeExplicitRefusal("Не удалось включить фонарик: система не подтвердила."))
        assertTrue("сделайте в настройках", looksLikeExplicitRefusal("Сделайте это в системных настройках вручную."))
        assertTrue("не могу сам", looksLikeExplicitRefusal("Я не могу сам выключить Wi-Fi на этой версии Android."))
        assertTrue("нет инструмента", looksLikeExplicitRefusal("У меня нет инструмента для открытия папки во внешнем менеджере."))
        assertFalse("успех", looksLikeExplicitRefusal("Фонарик выключен."))
        assertFalse("включён", looksLikeExplicitRefusal("Фонарик включён, всё готово."))
    }
}
