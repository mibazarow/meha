package com.andharness.ru.data.tools.misc

/** One message reduced to what the batch prompt needs: who wrote it and its text. */
internal data class TranslateChatLine(val author: String, val text: String)
