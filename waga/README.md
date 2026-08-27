# Waga Ekranowa S-1

Aplikacja mobilna (PWA), która zamienia ekran telefonu w wagę: czyta siłę nacisku
zwracaną przez ekran dotykowy i po kalibracji przelicza ją na gramy.

## Uruchomienie

Wystarczy serwer plików statycznych — nie ma żadnego kroku budowania:

```bash
python3 -m http.server 8080   # w katalogu waga/
```

Potem otwórz `http://<ip-komputera>:8080/` na telefonie. Service worker i instalacja
na ekranie głównym wymagają HTTPS albo `localhost`.

## Jak to działa

Przeglądarka podaje nacisk jako liczbę bez jednostki od 0 do 1
(`PointerEvent.pressure`, na iOS dodatkowo `Touch.force`). Aplikacja:

1. sumuje nacisk ze wszystkich punktów styku,
2. filtruje sygnał medianą z 7 próbek i filtrem wykładniczym (α = 0,28),
3. przelicza go na gramy **kalibracją wielopunktową** — punkty tworzą krzywą
   odcinkami liniową, bo odpowiedź ekranu nie jest liniowa; jeden wzorzec daje
   zwykłą prostą, powyżej ekstrapoluje nachylenie ostatniego odcinka,
4. wykrywa stabilizację (odchylenie standardowe okna bieżącego kontaktu < 0,012)
   i po 0,9 s blokuje odczyt oraz zapisuje go w dzienniku; zmiana obciążenia
   o ponad 3 działki albo 3 % zwalnia blokadę,
5. zaokrągla wskazanie do działki 0,1 g z histerezą, żeby ostatnia cyfra nie migotała.

Tara działa **w dziedzinie masy** (`netto = brutto − tara`), więc nigdy nie rusza
punktu zerowego kalibracji; kasuje się po zdjęciu obciążenia z ekranu.
Kalibracja i historia siedzą w `localStorage` pod kluczem `waga.s1.v2`
(ustawienia z wersji 1 są migrowane automatycznie).

## Przelicznik

Ekran „Karaty" przelicza na żywo między miligramem, gramem, karatem metrycznym
(0,2 g), granem, pennyweightem oraz uncją trojańską i handlową — wpisanie wartości
w dowolnym polu przelicza pozostałe. Przycisk wstawia tam ostatni pomiar z wagi.
Osobny blok tłumaczy karat złota (próbę) na masę czystego kruszcu, bo to zupełnie
inna wielkość niż karat metryczny.

## Rozpoznawanie czujnika

Dowodem na czujnik siły jest **zakres** odczytów, a nie pojedyncza wartość: ekran
bez czujnika zwraca stałe 1 (albo 0,5), które potrafi drgać na ostatnim bicie.
Tryb `force` włącza się, gdy rozpiętość nacisku przekroczy 0,06 przy co najmniej
6 próbkach albo pojawią się 4 różne poziomy. Wbudowany test czujnika (6 s) pokazuje
zmierzone minimum, maksimum i liczbę poziomów, po czym sam ustawia właściwy tryb.
Nasycenie sygnału (≥ 0,995) jest zgłaszane jako przeciążenie.

## Tryby pracy

| Tryb | Kiedy | Wiarygodność |
|------|-------|--------------|
| `force` | ekran zwraca zmienną siłę nacisku (3D Touch / Force Touch, część Androidów) | pomiar ilościowy po kalibracji |
| `pole styku` | brak czujnika siły, ale znany jest promień styku | orientacyjny, tylko dla palca |
| `niedostępny` | nacisk stale 0 albo 1 | brak pomiaru |

Realny czujnik siły mają iPhone 6s – XS, Apple Watch, Huawei Mate S i ZTE Axon mini.
Apple usunął 3D Touch w iPhonie 11 — Haptic Touch mierzy czas przytrzymania, nie siłę.

## Ograniczenia

Ekran mierzy **nacisk**, nie masę. Przedmiot położony luźno na leżącym telefonie
prawie nic nie naciska, więc sensowny odczyt dostaniesz, dociskając przedmiot palcem
albo opierając go o ekran trzymany pionowo. To narzędzie orientacyjne, nie waga
handlowa.

## Pliki

- `index.html` — cała aplikacja (styl, widok, logika)
- `manifest.webmanifest`, `sw.js`, `icon*.svg` — warstwa PWA

## Testy

`waga/../test` nie istnieje jako osobny pakiet — zestaw testów end‑to‑end
(Playwright) sprawdza kalibrację na symulowanym czujniku nieliniowym, przelicznik,
tarę, przeciążenie, tryb zapasowy i auto‑test. Uruchomienie wymaga Playwrighta
oraz Chromium wskazanego przez `executablePath`.
