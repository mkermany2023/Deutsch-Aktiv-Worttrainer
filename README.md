# Deutsch Aktiv – Android Mobile

Diese Android-Studio-Version basiert auf der V6.1-Offline-App.

## Mobile Funktionen
- große, bildschirmfüllende Wortkarten
- feste Bottom-Navigation: Lernen / Üben / Sprechen / Wörter / Bücher
- Übungs-Auswahl als Bottom Sheet
- native Android-Dateiauswahl für `.wt` und Bilder
- nativer Android-Speicherdialog für `.wt`-Export
- native Android-Spracherkennung
- Mikrofonaufnahme manuell stoppen
- automatische Beendigung nach 50 Sekunden ohne erkannte Stimme
- mehrere Wörterbücher, Tags, Bilder und Eintrags-Editor bleiben erhalten

## APK in Android Studio bauen
1. Android Studio installieren und dieses Verzeichnis als Projekt öffnen.
2. Falls gefragt, Android SDK API 37 und Build Tools 36.0.0 installieren.
3. Gradle Sync ausführen.
4. Menü **Build > Generate App Bundles or APKs > Generate APKs** (oder die entsprechende APK-Funktion deiner Android-Studio-Version).
5. Für Tests reicht der Debug-Build.

Die App-ID lautet `com.deutschaktiv.worttrainer`.

## Hinweis zu KI
Die lokale Wörterbuch-, Lern-, Datei- und Mikrofonfunktion arbeitet ohne Python. Eine sichere direkte OpenAI-/Copilot-Anbindung sollte weiterhin über einen geschützten Backend-Endpunkt erfolgen, damit API-Schlüssel nicht in der APK liegen.
