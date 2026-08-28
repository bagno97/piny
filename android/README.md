# Waga S-1 — aplikacja natywna na Androida

Waga korzystająca z czujnika nacisku ekranu dotykowego. Wersja natywna powstała,
bo przeglądarka normalizuje `PointerEvent.pressure` i na wielu telefonach oddaje
stałe 1,0 — `MotionEvent.getPressure()` w kodzie natywnym podaje wartość, którą
naprawdę zwraca sterownik ekranu.

## Budowanie

```bash
export ANDROID_HOME=/ścieżka/do/android-sdk
gradle :app:assembleDebug        # APK do side-loadingu
gradle :app:testDebugUnitTest    # 81 testów, bez emulatora
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

Gotowy plik do zainstalowania leży w `dist/waga-1.5.apk` (podpisany, minSdk 24).
Suma kontrolna jest obok, w pliku `.sha256`.

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
| `Fft.kt` | przekształcenie Fouriera o podstawie 2, okno Hanna |
| `Resonance.kt` | szczyt widma i przeliczenie częstotliwości na masę |
| `Tilt.kt` | uśredniony kierunek grawitacji i przeliczenie przechyłu na masę |
| `Fusion.kt` | regresja liniowa łącząca kanały czujników |
| `SensorScaleActivity.kt` | waga czujnikowa: przechył, rezonans, obecność |

## Dwie niezależne metody pomiaru

**Waga ekranowa** czyta nacisk z ekranu dotykowego. Wymaga kontaktu
przewodzącego, więc mierzy tylko to, co naciska palec albo rysik. Przedmiot
bierny jest dla ekranu pojemnościowego niewidzialny.

**Waga czujnikowa** (`SensorScaleActivity`) mierzy przedmiot **leżący** na
telefonie, bez żadnego dotyku. Łączy dwa niezależne kanały:

*Odczyt jest ciągły.* Akcelerometr chodzi przez cały czas, gdy ekran czujnikowy
jest otwarty, a wskazanie liczone jest z ruchomego okna ostatnich ~3 s i
odświeżane pięć razy na sekundę. Położenie przedmiotu podnosi liczbę, która
zostaje, dopóki przedmiot leży — waga nie mierzy w zrywach na żądanie.
Przycisk „Zeruj teraz" ustawia punkt odniesienia w bieżącym położeniu.

*Przechył.* Przedmiot położony poza środkiem ugina miękkie podłoże
nierównomiernie i obraca telefon o kąt proporcjonalny do momentu siły, czyli do
masy. Kierunek grawitacji wyznacza uśredniony wektor z akcelerometru — przy 400
próbkach szum maleje z pierwiastkiem ich liczby, więc wykrywalne są setne części
stopnia. Sygnał jest **statyczny**: trwa, dopóki przedmiot leży, więc odczyt nie
znika po odjęciu ręki. Zapis, w którym telefon był poruszany (rozrzut powyżej
0,6°), jest odrzucany.

*Rezonans.* Telefon na miękkim podłożu jest układem masa–sprężyna: po impulsie
wibracji drga z częstotliwością f = (1/2π)·√(k/M), a dołożona masa ją obniża.
Sygnał z akcelerometru (~400 Hz) przechodzi przez okno Hanna i FFT o podstawie 2,
a szczyt widma jest doprecyzowany interpolacją paraboliczną. Szczyt musi wystawać
trzykrotnie ponad tło, inaczej pomiar jest odrzucany jako szum.

*Czujnik zbliżeniowy* potwierdza, że coś na telefonie leży — nie wchodzi do
obliczeń, ale odróżnia pusty telefon od obciążonego.

*Łączenie kanałów.* Model uczy się na wzorcach o znanej masie — regresja liniowa
z regularyzacją grzbietową, a nie sieć neuronowa: przy kilku wzorcach tylko ona
się nie przeucza, a jej współczynniki da się obejrzeć. Trzy wzorce i więcej
uruchamiają oba kanały naraz; przy mniejszej liczbie model schodzi do prostej
proporcji na tym kanale, który dał wyraźniejszy sygnał.

Zasięg metody rezonansowej dla telefonu ~200 g przy 40 Hz i niepewności 0,05 Hz:

| masa | spadek częstotliwości | wynik |
|------|----------------------|-------|
| 0,4 g (2 karaty) | 0,04 Hz | ginie w szumie |
| 1 g | 0,10 Hz | na granicy |
| 5 g | 0,49 Hz | mierzalne |
| 20 g | 1,86 Hz | pewnie |
| 100 g | 7,34 Hz | pewnie |

Kanał przechyłowy bywa czulszy, bo jego rozdzielczość zależy od miękkości
podłoża i odległości przedmiotu od środka — im dalej od środka, tym większy
przechył. Aplikacja podaje zmierzony przechył i spadek częstotliwości przy
każdym wzorcu, więc widać, który kanał niesie sygnał.

## Czego nie da się użyć

Czytnik linii papilarnych pod ekranem jest optyczny i teoretycznie reaguje na
ugięcie szkła, ale Android udostępnia go wyłącznie przez `BiometricPrompt`,
który zwraca wynik uwierzytelnienia — nie ma API do surowego obrazu ani sygnału.
Żyroskop mierzy prędkość kątową, więc przy nieruchomym telefonie nie niesie
informacji; kierunek grawitacji z akcelerometru daje to samo lepiej.

## Skala sterownika nie jest znana z góry

Dokumentacja Androida opisuje `MotionEvent.getPressure()` jako wartość w okolicy
1,0 dla zwykłego dotknięcia, ale sterowniki trzymają się tego luźno: spotykane
są ekrany, na których mocny docisk to 0,0006, a lekki 0,0002. Taki ekran
**rozróżnia nacisk trzykrotnie**, więc nadaje się do ważenia — pod warunkiem, że
aplikacja nie zakłada z góry skali 0–1.

Dlatego wszystkie progi w kodzie są względne:

- rozpoznanie czujnika porównuje rozpiętość do maksimum (`relativeSpan`), a nie
  do stałej; przy progu bezwzględnym ekran o własnej skali uchodziłby za
  pozbawiony czujnika
- kalibracja wstępna skaluje się do **największego sygnału, jaki ekran realnie
  oddał** (`Store.observedFullScale`), a nie do umownej jedynki
- minimalny odstęp między wzorcami to 1 % zakresu krzywej, nie stała liczba —
  inaczej na ekranie o małej skali wszystkie wzorce byłyby „tym samym punktem"
- odczyty są dodatkowo sprowadzane do zakresu deklarowanego przez sterownik
  (`InputDevice.getMotionRange(AXIS_PRESSURE)`), gdy ten deklaruje własną skalę

Zakres uczy się sam: mocniejszy docisk niż dotąd widziany rozciąga skalę i
zostaje zapamiętany dla danego narzędzia. Diagnostyka pokazuje wszystkie te
liczby, a przycisk „Ucz zakresu od nowa" kasuje naukę.

## Wynik zostaje po zdjęciu palca

Ekran pojemnościowy przestaje cokolwiek widzieć w chwili, gdy palec odchodzi —
bez zatrzymania wskazania odczyt spadałby do zera dokładnie wtedy, gdy chce się
go odczytać. Ostatni ustabilizowany pomiar zostaje więc na wyświetlaczu ze stanem
„ostatni pomiar", dopóki nie zacznie się nowy nacisk albo nie naciśnie się Tary.

## Pomiar wymaga oddania ekranu

Każdy pomiar, do którego trzeba nacisnąć ekran — kalibracja wzorcem i test
czujnika — zamyka arkusz i zbiera próbki na głównym ekranie, prowadząc
użytkownika banerem z odliczaniem. Arkusz `BottomSheetDialog` ma własne okno:
zasłania pole pomiarowe i przechwytuje dotknięcia, więc pomiar uruchamiany
„zza" niego nie zbierał ani jednej próbki. Dwa testy Robolectrica pilnują, że
arkusz znika na czas pomiaru i że próbki z pola pomiarowego trafiają na krzywą.

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
