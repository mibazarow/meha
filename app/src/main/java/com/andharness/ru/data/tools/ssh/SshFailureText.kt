package com.andharness.ru.data.tools.ssh

import com.andharness.ru.data.ssh.SshFailure

/** Maps an [SshFailure] to an honest, actionable Russian message. */
internal class SshFailureText {

    fun describe(failure: SshFailure): String = when (failure.kind) {
        SshFailure.Kind.HOST_KEY_UNKNOWN -> failure.message.orEmpty()
        SshFailure.Kind.HOST_KEY_CHANGED -> failure.message.orEmpty()
        SshFailure.Kind.AUTH -> (failure.message ?: "") +
            "\nПроверьте user и сохранённый пароль/ключ: интерактивный ввод пароля не поддерживается, " +
            "поэтому сервер не может спросить его сам."
        SshFailure.Kind.SECRET_MISSING -> failure.message.orEmpty()
        SshFailure.Kind.TIMEOUT -> (failure.message ?: "") +
            "\nУвеличьте timeout_sec, если операция действительно длинная."
        else -> failure.message ?: "Ошибка SSH."
    }
}
