# Толстый Тилкайо — ПК-порт

Порт Android-игры «Толстый Тилкайо» на Windows. Оригинальный репозиторий `tilcayo`
не изменяется: весь код порта живёт здесь, а игровая логика подключается из
`../tilcayo/app/src/main/java` как единый источник правды.

## Что внутри

- `src/main/kotlin/android/**` — тонкий слой совместимости: мини-реализация
  `android.graphics` (Java2D), `android.view`, `android.content`,
  `android.media` (javax.sound) и т.д. Игровые `.kt`-файлы компилируются как есть.
- `src/main/kotlin/com/tilcayo/pc/DesktopMain.kt` — точка входа: окно Swing,
  ввод мышью/клавиатурой, файл сохранения, автономный режим снимков `--shot`.
- `src/main/kotlin/com/tilcayo/fat/R.kt` — замена сгенерированного `R`.
- `src/main/resources/` — ассеты игры (`tilcayo.png`, `boss.png`, `chunks.txt`)
  и `save.xml` — стартовое сохранение, перенесённое с телефона.
- `packaging/tilcayo.ico` — иконка `.exe`.

## Сборка

Требуется JDK 17+ (проверено на Eclipse Temurin 21).

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'

# запускаемый jar
.\gradlew.bat fatJar           # -> build\libs\tilcayo.jar

# автономный .exe со встроенной JVM
.\gradlew.bat packageExe       # -> dist\Tilcayo\Tilcayo.exe
```

## Запуск

```powershell
java -jar build\libs\tilcayo.jar
# или
.\dist\Tilcayo\Tilcayo.exe
```

Оба варианта запускают одно и то же приложение.

## Управление

| Действие | Клавиша / мышь |
| --- | --- |
| Прыжок вперёд / выбор | ЛКМ (клик) |
| Рывок в сторону | ЛКМ и протащить влево/вправо |
| Тап (прыжок / выбор) | Пробел — синтетический тап по центру сверху |
| Назад / пауза | Esc |

## Сохранение

Формат файла совместим с Android (`SharedPreferences` XML). Лежит по адресу
`%APPDATA%\tilcayo\tilcayo.xml`. При первом запуске создаётся из
`src/main/resources/save.xml` (сохранение, снятое с телефона).

## Режим снимков (для проверки порта)

```powershell
java -jar build\libs\tilcayo.jar --w 450 --h 1000 --frames 30 --shot menu.png
```

Поддерживаемые аргументы: `--w`, `--h`, `--frames N`, `--shot FILE`,
`--tap X,Y`, `--key esc|space`. Аргументы обрабатываются по порядку, поэтому
можно снимать цепочку экранов за один запуск.

## Известные ограничения

- Эмодзи рендерятся монохромно (ограничение Java2D / доступных системных шрифтов).
- Часть эмодзи может отображаться пустыми квадратами, если глифа нет в шрифте.
