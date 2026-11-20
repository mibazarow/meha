package com.andharness.ru.domain.permission

/**
 * Pure decision for `device_admin_enable`: the tool may only report success when the component is
 * actually active, and must say so plainly otherwise (BUG-021).
 *
 * The single source of truth for the real admin state is
 * `DevicePolicyManager.isAdminActive(ComponentName)`, not the intent launch:
 * https://developer.android.com/reference/android/app/admin/DevicePolicyManager#isAdminActive(android.content.ComponentName)
 *
 * Kept free of Android APIs so every branch is covered by a JVM unit test.
 */
object DeviceAdminDecision {

    const val ALREADY_ACTIVE = "Меха уже является администратором устройства."
    const val ACTIVATED = "Меха включена как администратор устройства."
    const val NOT_CONFIRMED =
        "Экран активации администратора закрыт, но активация не подтверждена. " +
            "Включите Меху в системном списке «Администраторы устройства» и повторите."
    const val CANCELLED =
        "Запрос активации администратора отменён: экран закрыт до подтверждения."
    const val UNEXPECTED = "Неожиданный ответ интерфейса при активации администратора."

    /** The component is already active, so nothing needs to be shown. */
    fun alreadyActive(): DeviceAdminEnableResult = DeviceAdminEnableResult(true, ALREADY_ACTIVE)

    /**
     * @param adminActiveBefore whether the component was already active before the request.
     * @param outcome what the UI bridge reported for the activation screen.
     * @param adminActiveAfter the real state re-read after the screen closed.
     * @param failureMessage the bridge's honest failure text (e.g. no foreground Activity), if any.
     */
    fun enable(
        adminActiveBefore: Boolean,
        outcome: DeviceAdminUiOutcome,
        adminActiveAfter: Boolean,
        failureMessage: String? = null,
    ): DeviceAdminEnableResult = when {
        adminActiveBefore -> DeviceAdminEnableResult(true, ALREADY_ACTIVE)
        outcome == DeviceAdminUiOutcome.COMPLETED && adminActiveAfter ->
            DeviceAdminEnableResult(true, ACTIVATED)
        outcome == DeviceAdminUiOutcome.COMPLETED ->
            DeviceAdminEnableResult(false, NOT_CONFIRMED)
        outcome == DeviceAdminUiOutcome.CANCELLED ->
            DeviceAdminEnableResult(false, CANCELLED)
        outcome == DeviceAdminUiOutcome.FAILED ->
            DeviceAdminEnableResult(false, failureMessage ?: UNEXPECTED)
        else -> DeviceAdminEnableResult(false, UNEXPECTED)
    }
}
