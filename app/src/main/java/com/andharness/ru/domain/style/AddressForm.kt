package com.andharness.ru.domain.style

import kotlinx.serialization.Serializable

/** Which second-person form the user prefers when addressing the assistant. */
@Serializable
enum class AddressForm {
    /** Informal singular («ты»). */
    TY,

    /** Formal or plural («вы»). */
    VY,
}
