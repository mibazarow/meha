package com.andharness.ru.domain.retrieval

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M4 — benchmark of memory recall quality (BUGS.md).
 *
 * The three existing memory tests check intent detection, not retrieval quality. This one answers the
 * only question that matters before touching ranking: does `memory_search` find what it should?
 *
 * Method (ported from Oборбот's `tools/rag_bench.py`, but as a JVM test so it lives next to the code):
 * a fixed corpus of realistic memories and a fixed set of questions, each with the id of the memory a
 * human would expect first. We measure recall@1 (the expected memory is the top hit) and recall@5
 * (it is in the first five). Numbers are printed so a before/after comparison is a real measurement
 * instead of a belief.
 *
 * The corpus is deliberately Russian-first and paraphrase-heavy, because that is what a phone user
 * actually types. It contains distractors that share vocabulary with questions but are not the
 * answer — otherwise the benchmark would flatter itself.
 */
class MemoryRecallBenchmarkTest {

    private val now = 1_700_000_000_000L

    private fun mem(id: Long, text: String, tags: String = "", ageDays: Long = 0) = MemoryRecord(
        id = id,
        text = text,
        tags = tags,
        createdAt = now - ageDays * DAY
    )

    /** Fixed corpus: 24 memories across the domains a personal assistant actually stores. */
    private val corpus = listOf(
        mem(1, "Пользователь любит крепкий чёрный чай без сахара"),
        mem(2, "Рабочая встреча с ФНС по декларации за 2025 год", "налоговая, фнс, декларация"),
        mem(3, "Записался к стоматологу на вторник в 15:00"),
        mem(4, "Договорились с Мариной про аренду офиса на сентябрь", "марина, аренда"),
        mem(5, "День рождения жены 12 марта"),
        mem(6, "Машина Тойота Камри, техобслуживание каждые 10 тысяч километров", "авто, машина"),
        mem(7, "Проект Годот Энджин — сроки сдачи в конце ноября"),
        mem(8, "Аллергия на пенициллин"),
        mem(9, "Код домофона 4721"),
        mem(10, "Пароль от домашнего Wi-Fi лежит в блокноте на кухне"),
        mem(11, "Любимая пицца — пепперони с двойным сыром"),
        mem(12, "Начальника зовут Сергей Петрович", "работа"),
        mem(13, "Арендовал склад на Ленина для товаров", "аренда, склад"),
        mem(14, "В отпуск лечу в Сочи с 10 по 20 июля"),
        mem(15, "Сын ходит на плавание по субботам", "семья, дети"),
        mem(16, "Купил подарок маме — сертификат в спа", "семья, подарок"),
        mem(17, "Платил ипотеку в Сбербанке, 34 тысячи в месяц", "банк, ипотека"),
        mem(18, "Любит смотреть документальные фильмы про океан"),
        mem(19, "Врач прописал витамин D по 2000 единиц"),
        mem(20, "Соседа снизу зовут Виктор, жалуется на шум"),
        mem(21, "Рабочий ноутбук — MacBook Pro, серийник заканчивается на 7KL"),
        mem(22, "Хочу выучить испанский язык к весне"),
        mem(23, "Договорились с Аней созвониться в четверг вечером", "аня"),
        mem(24, "Держит ключи от дачи в верхнем ящике прихожей")
    )

    /** Question → the id a human would put first. */
    private val questions = listOf(
        "какой чай я люблю" to 1L,
        "что там с налоговой" to 2L,
        "когда у меня стоматолог" to 3L,
        "что мы решили по офису с Мариной" to 4L,
        "когда день рождения у жены" to 5L,
        "какое у меня авто" to 6L,
        "когда сдавать Годот" to 7L,
        "на что у меня аллергия" to 8L,
        "какой код домофона" to 9L,
        "какая моя любимая пицца" to 11L,
        "как зовут начальника" to 12L,
        "где я храню товары" to 13L,
        "когда я лечу в отпуск" to 14L,
        "чем занимается сын" to 15L,
        "сколько я плачу за ипотеку" to 17L,
        "какие фильмы мне нравятся" to 18L,
        "какие витамины мне прописали" to 19L,
        "как зовут соседа" to 20L,
        "что за ноутбук у меня на работе" to 21L,
        "какой язык я хочу выучить" to 22L,
        "когда мы созваниваемся с Аней" to 23L
    )

    @Test
    fun recallIsMeasuredAndStaysAboveTheFloor() {
        val hits1 = questions.count { (q, expected) ->
            MemoryRanker.rank(q, corpus, limit = 1, now = now).firstOrNull()?.id == expected
        }
        val hits5 = questions.count { (q, expected) ->
            MemoryRanker.rank(q, corpus, limit = 5, now = now).any { it.id == expected }
        }
        val recallAt1 = hits1.toDouble() / questions.size
        val recallAt5 = hits5.toDouble() / questions.size

        println(
            "MEMORY BENCHMARK: recall@1=${"%.0f".format(recallAt1 * 100)}% " +
                "($hits1/${questions.size}) recall@5=${"%.0f".format(recallAt5 * 100)}% " +
                "($hits5/${questions.size})"
        )
        questions.forEach { (q, expected) ->
            val ranked = MemoryRanker.rank(q, corpus, limit = 5, now = now)
            val at = ranked.indexOfFirst { it.id == expected }
            if (at < 0) println("  MISS  «$q» → ожидался #$expected, выдано ${ranked.map { it.id }}")
        }

        // Floors are deliberately below the measured baseline so the test catches a REGRESSION
        // without turning a small tuning experiment red.
        assertTrue("recall@1 упал ниже 60%: ${"%.0f".format(recallAt1 * 100)}%", recallAt1 >= 0.60)
        assertTrue("recall@5 упал ниже 85%: ${"%.0f".format(recallAt5 * 100)}%", recallAt5 >= 0.85)
    }

    @Test
    fun unrelatedQuestionDoesNotInventALexicalMatch() {
        val ranked = MemoryRanker.rank("прогноз погоды в токио", corpus, limit = 5, now = now)

        assertTrue("fallback must never be empty", ranked.isNotEmpty())
        assertTrue(
            "an unrelated query must not claim a lexical match",
            ranked.none { it.lexicallyMatched }
        )
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }

    /**
     * The same benchmark on a REALISTIC database: the memory table of a phone that has been running
     * notification triage for weeks is full of importance-2, often-recalled rows linked through the
     * person graph. Measured on the test device (900 rows), those rows scored 3.10 and pushed a
     * genuine «крепкий чай» match out of the top 10, so `memory_search` answered with «Теплички:
     * спасибо». A real lexical match must always beat a merely CONNECTED memory.
     */
    @Test
    fun graphReachedNoiseNeverOutranksAGenuineMatch() {
        val noise = (100L..139L).map {
            MemoryRecord(
                id = it,
                text = "Все Теплички: спасибо, поставить в крошки $it",
                tags = "уведомление,personal",
                importance = 2,
                accessCount = 5,
                createdAt = now
            )
        }
        val noisyCorpus = corpus + noise
        val noiseIds = noise.map { it.id }.toSet()

        val results = questions.map { (q, expected) ->
            val ranked = MemoryRanker.rank(q, noisyCorpus, limit = 3, now = now, graphIds = noiseIds)
            q to (ranked.any { it.id == expected } && ranked.first().id == expected)
        }
        val hits = results.count { it.second }
        val recall = hits.toDouble() / questions.size
        println(
            "MEMORY BENCHMARK (с шумом): recall@1=${"%.0f".format(recall * 100)}% " +
                "($hits/${questions.size})"
        )
        results.filterNot { it.second }.forEach { println("  MISS  «${it.first}»") }

        assertTrue(
            "с шумом recall@1 упал ниже 70%: ${"%.0f".format(recall * 100)}%",
            recall >= 0.70
        )
    }
}
