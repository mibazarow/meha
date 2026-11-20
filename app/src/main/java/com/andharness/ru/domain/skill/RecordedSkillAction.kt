package com.andharness.ru.domain.skill


/**
 * Pure half of the skill recorder (M2 slice 3, SPEC §24.2): turns the actions captured by the
 * Android-bound `SkillActionCapture` into ordered [SkillStep]s. It has NO Android imports, so the
 * whole conversion policy — semantic selector preferred, honest skip for anything that cannot be
 * expressed — is exercised on the JVM (`SkillRecordingConverterTest`).
 *
 * Preference order for a target is deliberate and mirrors the run engine's resolver priority:
 * an explicit selector the agent already used wins, then the identifying fields of the node that was
 * on screen when the action ran (`resourceId` -> `contentDescription` -> `text`). Raw coordinates can
 * NOT be expressed as a [SkillStep], so such an action is skipped with an honest note rather than
 * silently recorded as something that would replay at the wrong place.
 */

/** One recorded UI action as handed over by the recorder. */
data class RecordedSkillAction(
    val tool: String,
    val argsJson: String,
    /** Semantic view of the on-screen node captured at record time, when it could be read. */
    val node: RecordedNode? = null,
)
