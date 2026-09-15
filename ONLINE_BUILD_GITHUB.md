# APK online mit GitHub bauen – ohne Android Studio

Diese Projektversion enthält bereits einen GitHub-Actions-Workflow.

## Einmalige Einrichtung

1. Öffne https://github.com und melde dich an.
2. Klicke oben rechts auf **+** und dann **New repository**.
3. Name z. B. `Deutsch-Aktiv-Worttrainer`.
4. Erstelle das Repository.
5. Öffne dein Repository und wähle **Add file > Upload files**.
6. Lade **den Inhalt dieses Projektordners** hoch. Wichtig: Auch der versteckte Ordner `.github` muss im Repository landen.
7. Klicke unten auf **Commit changes**.

## APK bauen

1. Öffne im Repository den Reiter **Actions**.
2. Wähle links **Build Android APK**.
3. Klicke rechts auf **Run workflow** und nochmals auf **Run workflow**.
4. Öffne nach erfolgreichem Build den neuesten Workflow-Lauf.
5. Unten im Bereich **Artifacts** erscheint **Deutsch-Aktiv-APK**.
6. Lade dieses Artefakt herunter und entpacke es.
7. Darin befindet sich `app-debug.apk`.

## Auf dem Android-Handy installieren

Übertrage `app-debug.apk` auf dein Smartphone und öffne die Datei. Android kann dich auffordern, für den verwendeten Dateimanager/Browser die Installation aus unbekannten Quellen zu erlauben.

## Wichtig

`app-debug.apk` ist für dein eigenes Testen und Installieren geeignet. Für Google Play braucht man später einen signierten Release-Build bzw. normalerweise ein `.aab`-Paket. Das kann als zweiter Workflow ergänzt werden.
