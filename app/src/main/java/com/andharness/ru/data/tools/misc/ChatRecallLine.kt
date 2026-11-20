package com.andharness.ru.data.tools.misc


/** One message reduced to what the recall prompt needs: who wrote it and its flattened text. */
internal data class ChatRecallLine(val author: String, val text: String)
