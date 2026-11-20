# Сборка и подпись релиза

Как собрать подписанный APK. Публикация релизов (GitHub Releases, манифест обновлений, проверка
хэшей) — в [GITHUB_PUBLISH.md](GITHUB_PUBLISH.md).

## Канал сборки

Один канал: `sideload` — сборка со встроенным обновлятором. `applicationId` —
`com.andharness.ru`, подпись — релизный ключ `CN=Meha` (см. `keystore.md`).

Своего сервера раздачи у проекта нет: обновления приходят из GitHub Releases.

## Как устроено

- Шов (интерфейс) в `main`: `data/update/InAppUpdater.kt`.
- Движок обновлятора — в `app/src/sideload/java/com/andharness/ru/data/update/`
  (`UpdateManager`, `UpdateClient`, `UpdateDownloader`, `ApkDownloader`, `ApkSignerVerifier`)
  + Hilt-модуль `app/src/sideload/java/com/andharness/ru/di/UpdaterModule.kt`.
- Адрес манифеста — одна константа `UpdateConfig.MANIFEST_URL`
  (`app/src/main/java/com/andharness/ru/data/update/UpdateConfig.kt`).
- Разрешение `REQUEST_INSTALL_PACKAGES` объявлено только в `app/src/sideload/AndroidManifest.xml`
  (в `main/AndroidManifest.xml` его нет): оно нужно, чтобы передать скачанный APK системному
  установщику.
- Product flavor один — `sideload` (`flavorDimensions = "channel"`, см. `app/build.gradle.kts`).

## Команды

```bash
./gradlew :app:assembleSideloadRelease     # релиз
./gradlew :app:assembleSideloadDebug       # debug
./gradlew :app:testSideloadDebugUnitTest   # тесты
./gradlew :app:detekt                      # статический анализ
```

Релизный APK: `app/build/outputs/apk/sideload/release/app-sideload-release.apk`

## Подпись

Ключ и пароли — в `keystore.properties` и `keystore/meha-release.jks` (на машине владельца, в git
не попадают). Если ключа нет, задача `validateReleaseSigning` **уронит** релизную сборку — это
защита от публикации неподписанного APK. Debug-сборка собирается и без ключа.

Проверить, чем подписан готовый APK:

```bash
~/Library/Android/sdk/build-tools/36.0.0/aapt2 dump badging \
  app/build/outputs/apk/sideload/release/app-sideload-release.apk | head -1
```

Проверить, что разрешение на месте:

```bash
~/Library/Android/sdk/build-tools/36.0.0/aapt2 dump permissions \
  app/build/outputs/apk/sideload/release/app-sideload-release.apk | grep -i INSTALL_PACKAGES
# ожидаемо REQUEST_INSTALL_PACKAGES
```

## Установка на устройство

Только «поверх», без удаления:

```bash
adb install -r app/build/outputs/apk/sideload/release/app-sideload-release.apk
```

`adb uninstall` стирает чаты, память, настройки, зашифрованную БД и её ключ — это крайняя мера
восстановления, а не способ обновления.
