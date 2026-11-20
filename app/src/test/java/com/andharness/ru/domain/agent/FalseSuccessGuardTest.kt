package com.andharness.ru.domain.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FalseSuccessGuardTest {

    @Test
    fun actionRequestDetectsImperativesAndActionNouns() {
        assertTrue("создай задачу", FalseSuccessGuard.looksLikeActionRequest("создай задачу на 9:00"))
        assertTrue("добавь контакт", FalseSuccessGuard.looksLikeActionRequest("добавь контакт Ивана"))
        assertTrue("поставь напоминание", FalseSuccessGuard.looksLikeActionRequest("поставь напоминание на час"))
        assertTrue("удали файл", FalseSuccessGuard.looksLikeActionRequest("удали этот файл"))
        assertTrue("отправь смс", FalseSuccessGuard.looksLikeActionRequest("отправь смс маме"))
        assertTrue("archived", FalseSuccessGuard.looksLikeActionRequest("заархивируй папку"))
        assertTrue("запомни", FalseSuccessGuard.looksLikeActionRequest("запомни, что я люблю кофе"))
        assertTrue("каждый час", FalseSuccessGuard.looksLikeActionRequest("проверяй почту каждый час"))
        assertTrue("по расписанию", FalseSuccessGuard.looksLikeActionRequest("запускай это по расписанию"))
        assertTrue("case insensitive", FalseSuccessGuard.looksLikeActionRequest("СОЗДАЙ папку"))
    }

    @Test
    fun actionRequestRejectsCasualChatAndBlanks() {
        assertFalse("приветствие", FalseSuccessGuard.looksLikeActionRequest("привет, как дела?"))
        assertFalse("обычный вопрос", FalseSuccessGuard.looksLikeActionRequest("какая сегодня погода?"))
        assertFalse("болтовня", FalseSuccessGuard.looksLikeActionRequest("расскажи анекдот"))
        assertFalse("пустая строка", FalseSuccessGuard.looksLikeActionRequest(""))
        assertFalse("пробелы", FalseSuccessGuard.looksLikeActionRequest("   "))
    }

    @Test
    fun successClaimDetectsCompletionWording() {
        assertTrue("создана", FalseSuccessGuard.claimsActionDone("Задача создана, id 42."))
        assertTrue("готово", FalseSuccessGuard.claimsActionDone("Готово!"))
        assertTrue("выполнено", FalseSuccessGuard.claimsActionDone("Всё выполнено."))
        assertTrue("сделал", FalseSuccessGuard.claimsActionDone("Я сделал то, что ты просил."))
        assertTrue("успешно", FalseSuccessGuard.claimsActionDone("Отправлено успешно."))
        assertTrue("включил", FalseSuccessGuard.claimsActionDone("Фонарик включил."))
        assertTrue("case insensitive", FalseSuccessGuard.claimsActionDone("ЗАДАЧА СОЗДАНА"))
    }

    @Test
    fun successClaimRejectsNeutralReplies() {
        assertFalse("вопрос", FalseSuccessGuard.claimsActionDone("Что именно ты хочешь сделать?"))
        assertFalse("план без выполнения", FalseSuccessGuard.claimsActionDone("Я могу это сделать, если хочешь."))
        assertFalse("пустая строка", FalseSuccessGuard.claimsActionDone(""))
        assertFalse("пробелы", FalseSuccessGuard.claimsActionDone("   "))
    }

    @Test
    fun correctsWhenNoToolCalledButReplyClaimsSuccess() {
        assertTrue(
            "no tool + claim + action request",
            FalseSuccessGuard.shouldCorrect(
                userText = "создай задачу на 9:00",
                assistantText = "Задача создана, id 7.",
                toolCallsInTurn = 0,
                lastToolErrored = false
            )
        )
    }

    @Test
    fun doesNotCorrectWhenToolSucceeded() {
        assertFalse(
            "successful tool call + claim",
            FalseSuccessGuard.shouldCorrect(
                userText = "создай задачу на 9:00",
                assistantText = "Задача создана, id 7.",
                toolCallsInTurn = 1,
                lastToolErrored = false
            )
        )
    }

    @Test
    fun correctsWhenLastToolErroredButReplyClaimsSuccess() {
        assertTrue(
            "tool error + claim",
            FalseSuccessGuard.shouldCorrect(
                userText = "создай задачу на 9:00",
                assistantText = "Готово, задача создана.",
                toolCallsInTurn = 1,
                lastToolErrored = true
            )
        )
    }

    @Test
    fun doesNotCorrectUnrelatedChat() {
        assertFalse(
            "unrelated chat",
            FalseSuccessGuard.shouldCorrect(
                userText = "расскажи анекдот",
                assistantText = "Колобок повесился.",
                toolCallsInTurn = 0,
                lastToolErrored = false
            )
        )
    }

    @Test
    fun doesNotCorrectHonestFailureReportWithoutSuccessClaim() {
        assertFalse(
            "honest inability without a success claim",
            FalseSuccessGuard.shouldCorrect(
                userText = "создай задачу на 9:00",
                assistantText = "У меня нет подходящего инструмента для этого.",
                toolCallsInTurn = 0,
                lastToolErrored = false
            )
        )
    }

    @Test
    fun destructiveClaimDetectsAccomplishedDeleteWording() {
        assertTrue("deleted contacts", FalseSuccessGuard.claimsDestructiveDone("Удалил все контакты."))
        assertTrue("cleared", FalseSuccessGuard.claimsDestructiveDone("Корзина очищена."))
        assertTrue("files removed", FalseSuccessGuard.claimsDestructiveDone("Файлы удалены."))
        assertTrue("case insensitive", FalseSuccessGuard.claimsDestructiveDone("ВСЁ УДАЛЕНО"))
        assertFalse("plan, not accomplished", FalseSuccessGuard.claimsDestructiveDone("Я удалю эти файлы."))
        assertFalse("question", FalseSuccessGuard.claimsDestructiveDone("Что именно удалить?"))
        assertFalse("created", FalseSuccessGuard.claimsDestructiveDone("Заметка создана."))
        assertFalse("blank", FalseSuccessGuard.claimsDestructiveDone("  "))
    }

    @Test
    fun destructiveClaimWithoutConfirmedToolNeedsCorrection() {
        assertTrue(
            "claimed delete but no destructive tool confirmed it",
            FalseSuccessGuard.shouldCorrectDestructive("Удалил все контакты.", destructiveToolConfirmed = false)
        )
        assertFalse(
            "a destructive tool returned success => confirmed evidence",
            FalseSuccessGuard.shouldCorrectDestructive("Удалил все контакты.", destructiveToolConfirmed = true)
        )
        assertFalse(
            "non-destructive claim is not this guard's business",
            FalseSuccessGuard.shouldCorrectDestructive("Заметка создана.", destructiveToolConfirmed = false)
        )
    }

    @Test
    fun isDestructiveToolRecognisesDeleteAndTrash() {
        assertTrue("contact_delete", FalseSuccessGuard.isDestructiveTool("contact_delete"))
        assertTrue("files_trash", FalseSuccessGuard.isDestructiveTool("files_trash"))
        assertTrue("files_trash_empty", FalseSuccessGuard.isDestructiveTool("files_trash_empty"))
        assertTrue("name-pattern fallback", FalseSuccessGuard.isDestructiveTool("sms_delete_conversation"))
        assertTrue("_clear suffix", FalseSuccessGuard.isDestructiveTool("history_clear"))
        assertFalse("search is not destructive", FalseSuccessGuard.isDestructiveTool("contact_search"))
        assertFalse("list is not destructive", FalseSuccessGuard.isDestructiveTool("files_list"))
        assertFalse("create is not destructive", FalseSuccessGuard.isDestructiveTool("note_create"))
    }
}
