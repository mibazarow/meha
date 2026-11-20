package com.andharness.ru.ui.activity


/** One day's rows under a sticky header. [label] is «Сегодня» / «Вчера» / `dd.MM.yyyy`. */
data class AgentActivityGroup(
    val label: String,
    val items: List<AgentActivityItem>,
)
