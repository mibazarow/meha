package com.andharness.ru.domain.localserver

/**
 * What the free-space check decides before a model download starts.
 *
 * - [OK] — there is enough room (at least 1.25x the download).
 * - [LOW] — the download fits, but without the recommended 25% headroom; the UI should warn.
 * - [INSUFFICIENT] — the download does not fit; it must not start.
 */
enum class FreeSpaceDecision { OK, LOW, INSUFFICIENT }
