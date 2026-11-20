package com.andharness.ru.domain.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleAnalyzerTest {

    @Test
    fun blankMessageProducesEmptySignal() {
        assertEquals(StyleSignal.EMPTY, StyleAnalyzer.analyze(""))
        assertEquals(StyleSignal.EMPTY, StyleAnalyzer.analyze("   \n\t "))
    }

    @Test
    fun detectsInformalAddress() {
        assertEquals(AddressForm.TY, StyleAnalyzer.analyze("Ты где?").address)
        assertEquals(AddressForm.TY, StyleAnalyzer.analyze("скинь мне твой адрес").address)
    }

    @Test
    fun detectsFormalAddress() {
        assertEquals(AddressForm.VY, StyleAnalyzer.analyze("Вы где?").address)
        assertEquals(AddressForm.VY, StyleAnalyzer.analyze("Подскажите, ваша цена?").address)
    }

    @Test
    fun mixedOrAbsentAddressIsUnknown() {
        assertNull(StyleAnalyzer.analyze("Ты и вы одновременно").address)
        assertNull(StyleAnalyzer.analyze("Где ближайшая аптека?").address)
    }

    @Test
    fun detectsEmojiIncludingSurrogatePairs() {
        assertTrue(StyleAnalyzer.analyze("Привет 🔥").hasEmoji)
        assertTrue(StyleAnalyzer.analyze("люблю ❤️").hasEmoji)
        assertFalse(StyleAnalyzer.analyze("© 2026 Все права защищены").hasEmoji)
        assertFalse(StyleAnalyzer.analyze("обычный текст").hasEmoji)
    }

    @Test
    fun detectsSlangTokensButNotLongerWords() {
        assertTrue(StyleAnalyzer.analyze("норм, короче").hasSlang)
        assertTrue(StyleAnalyzer.analyze("Ну ок").hasSlang)
        assertFalse(StyleAnalyzer.analyze("это нормально").hasSlang)
    }

    @Test
    fun passesProfanityHitsThroughAndClampsNegatives() {
        assertEquals(2, StyleAnalyzer.analyze("да похуй", profanityHits = 2).profanityHits)
        assertEquals(0, StyleAnalyzer.analyze("текст", profanityHits = -5).profanityHits)
    }

    @Test
    fun detectsRelaxedPunctuation() {
        assertTrue(StyleAnalyzer.analyze("привет, как дела?").informalPunctuation)
        assertTrue(StyleAnalyzer.analyze("Привет!!!").informalPunctuation)
        assertTrue(StyleAnalyzer.analyze("Привет...").informalPunctuation)
        assertTrue(
            StyleAnalyzer.analyze("это довольно длинное сообщение без точки в конце")
                .informalPunctuation
        )
    }

    @Test
    fun shortMessagesWithoutFinalPunctuationAreNotFlagged() {
        assertFalse(StyleAnalyzer.analyze("ок").informalPunctuation)
        assertFalse(StyleAnalyzer.analyze("Привет.").informalPunctuation)
    }

    @Test
    fun detectsDirectiveRequests() {
        assertTrue(StyleAnalyzer.analyze("Открой настройки").isDirective)
        assertTrue(StyleAnalyzer.analyze("Пожалуйста, открой настройки").isDirective)
        assertTrue(StyleAnalyzer.analyze("давай сходим в кино").isDirective)
        assertFalse(StyleAnalyzer.analyze("Я открою окно").isDirective)
    }

    @Test
    fun lengthIsMeasuredOnTrimmedText() {
        assertEquals(2, StyleAnalyzer.analyze("  ок  ").length)
    }
}
