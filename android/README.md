# Waga S-1 — aplikacja natywna na Androida

Waga korzystająca z czujnika nacisku ekranu dotykowego. Wersja natywna powstała,
bo przeglądarka normalizuje `PointerEvent.pressure` i na wielu telefonach oddaje
stałe 1,0 — `MotionEvent.getPressure()` w kodzie natywnym podaje wartość, którą
naprawdę zwraca sterownik ekranu.

## Budowanie

```bash
export ANDROID_HOME=/ścieżka/do/android-sdk
gradle :app:assembleDebug        # APK do side-loadingu
gradle :app:testDebugUnitTest    # 46 testów, bez emulatora
```

Wymagania: JDK 17+, Android SDK z `platforms;android-35` i `build-tools;35.0.0`.
Projekt korzysta z Gradle z systemu (sprawdzony na 8.14.3) i AGP 8.9.1.

### Wersja podpisana

```bash
gradle :app:assembleRelease \
  -PwagaKeystore=/ścieżka/waga.jks \
  -PwagaStorePassword=… -PwagaKeyAlias=waga -PwagaKeyPassword=…
```

Klucz podpisujący **nie jest** trzymany w repozytorium (`keystore/` jest w
`.gitignore`). Android wiąże tożsamość aplikacji z kluczem: aktualizacja podpisana
innym kluczem nie nadpisze wcześniejszej instalacji, trzeba wtedy odinstalować
starą wersję. Własny klucz robi się raz:

```bash
keytool -genkeypair -keystore waga.jks -storetype PKCS12 \
  -alias waga -keyalg RSA -keysize 4096 -validity 10950
```

Gotowy plik do zainstalowania leży w `dist/waga-1.1.apk` (1,7 MB, podpisany,
minSdk 24). Suma kontrolna jest obok, w pliku `.sha256`.

## Testy

39 testów uruchamianych na JVM, bez emulatora:

- `CalibrationTest` — krzywa na symulowanym czujniku nieliniowym, limit
  ekstrapolacji, odrzucanie błędnych wzorców
- `ScaleEngineTest` — tara, zatrzymanie odczytu i jego zwalnianie, przeciążenie,
  brak migotania ostatniej cyfry
- `UnitsTest` — przeliczniki wobec definicji (20 dwt = 1 ozt, 480 gr = 1 ozt)
- `PressureProbeTest` — stały nacisk z drgającym bitem nie uchodzi za czujnik
- `MainActivityTest` — Robolectric: start aktywności, prawdziwe zdarzenia
  `MotionEvent` z naciskiem, odczyt masy, dziennik, arkusze, wybór kanału

## Układ kodu

Logika pomiaru jest oddzielona od Androida, dzięki czemu testuje się ją na JVM
bez emulatora.

| Plik | Odpowiedzialność |
|------|------------------|
| `Units.kt` | jednostki masy, przeliczniki, formatowanie po polsku |
| `Calibration.kt` | krzywa kalibracji, interpolacja, limit ekstrapolacji |
| `SignalFilter.kt` | mediana + filtr wykładniczy + ocena stabilności |
| `ScaleEngine.kt` | maszyna stanu: tara, zatrzymanie odczytu, przeciążenie |
| `PressureProbe.kt` | rozstrzyga, czy ekran naprawdę mierzy siłę |
| `PanView.kt` | odczyt `MotionEvent`, wybór kanału, rysowanie pola pomiarowego |
| `Store.kt` | profile kalibracji (osobno palec i rysik) oraz dziennik |
| `MainActivity.kt` | pętla 60 Hz, interfejs, arkusze |

## Kalibracja jest nieobowiązkowa

Waga działa od pierwszego dotknięcia. Bez własnych wzorców liczy z **krzywej
wstępnej** — prostej od zera do pełnego wychylenia czujnika, przyjmującej
500 g dla maksymalnego nacisku (mocny docisk palcem to 3–5 N, a zakres rysika
S Pen jest rzędu 500 gf). Taki odczyt jest oznaczony na ekranie jako
„szacunkowo · bez wzorca" i nie udaje pomiaru.

Zmierzenie jednego przedmiotu o znanej masie zamienia szacunek w pomiar,
a trzy wzorce układają krzywą. Profile są **osobne dla palca i dla rysika**,
bo to zupełnie inne charakterystyki nacisku — aplikacja przełącza je sama,
rozpoznając narzędzie po `MotionEvent.getToolType()`. Rysik jest tu lepszym
źródłem danych: S Pen podaje nacisk w 4096 poziomach, podczas gdy zwykły
ekran pojemnościowy często nie różnicuje go wcale.

## Jak liczona jest masa

1. `PanView` sumuje nacisk ze wszystkich punktów styku. Czyta też próbki
   historyczne z paczki `MotionEvent`, więc filtr dostaje więcej danych niż
   wynikałoby z liczby zdarzeń.
2. `SignalFilter` przepuszcza sygnał przez medianę z 7 próbek i filtr
   wykładniczy (α = 0,28).
3. `Calibration` zamienia sygnał na gramy **monotoniczną interpolacją sześcienną**
   (Fritsch–Carlson) przez punkty wzorcowe. Łamana przez te same punkty myli się
   kilkukrotnie bardziej na szerokich odcinkach; monotoniczność gwarantuje, że
   masa nigdy nie maleje przy rosnącym nacisku.
4. Powyżej najcięższego wzorca odczyt jest ekstrapolacją ograniczoną do 1,5×
   masy tego wzorca i oznaczoną na ekranie jako „poza zakresem wzorców".
5. `ScaleEngine` liczy netto jako `brutto − tara`, zatrzymuje odczyt po 0,9 s
   stabilizacji i zwalnia go, gdy obciążenie zmieni się o 3 działki albo 3 %.

## Rozpoznawanie czujnika

Dowodem na czujnik siły jest **zakres** odczytów, nie pojedyncza wartość: ekran
bez czujnika zwraca stałe 1,0, które potrafi drgać na ostatnim bicie i wygląda
wtedy jak pomiar. Tryb pomiarowy włącza się przy rozpiętości ≥ 0,06 na co najmniej
6 próbkach albo przy 4 różnych poziomach nacisku.

Diagnostyka pokazuje też, co deklaruje sam sterownik ekranu
(`InputDevice.getMotionRange(AXIS_PRESSURE)`) — minimum, maksimum, rozdzielczość
i szum osi nacisku. Wbudowany test 6-sekundowy mierzy realny zakres i sam ustawia
tryb pracy.

Gdy czujnika siły nie ma, aplikacja przechodzi na kanał powierzchniowy: liczy pole
elipsy styku (`getTouchMajor` × `getTouchMinor`) przeliczone na mm² przez gęstość
ekranu. Działa to dla palca i tylko orientacyjnie — twardy przedmiot ma stałą
powierzchnię styku.

## Ograniczenie, którego nie da się obejść kodem

Ekran mierzy **nacisk, nie masę**. Przedmiot położony luźno na leżącym telefonie
prawie nic nie naciska. Sensowny odczyt dostaniesz, dociskając przedmiot palcem
albo opierając go o ekran trzymany pionowo. Aplikacja mówi wprost, w którym z
trzech stanów jest sprzęt, zamiast pokazywać wymyślone liczby.
