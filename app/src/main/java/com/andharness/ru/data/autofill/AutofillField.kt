package com.andharness.ru.data.autofill


/**
 * The only three things the minimal ANDharness autofill service can offer: the user's OWN name,
 * phone and e-mail. Addresses, cards and other-people data are intentionally out of scope (the
 * personal base stores other people and has no owner profile).
 */
enum class AutofillField { NAME, PHONE, EMAIL }
