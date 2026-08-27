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
3. przelicza go na gramy według kalibracji dwupunktowej: `masa = (sygnał − zero) × skala`,
4. wykrywa stabilizację (odchylenie standardowe okna 45 próbek < 0,012) i po 0,9 s
   blokuje odczyt oraz zapisuje go w dzienniku.

Kalibracja i historia siedzą w `localStorage` pod kluczem `waga.s1.v1`.

## Tryby pracy

| Tryb | Kiedy | Wiarygodność |
|------|-------|--------------|
| `force` | ekran zwraca zmienną siłę nacisku (3D Touch / Force Touch, część Androidów) | pomiar po kalibracji |
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
