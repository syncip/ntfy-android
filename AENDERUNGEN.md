# ntfy Sync – Änderungen gegenüber ntfy-android

Basis: https://github.com/binwiederhier/ntfy-android (Apache 2.0)

## Konto-Synchronisierung
- Neue Klasse `app/src/main/java/io/heckel/ntfy/msg/AccountSyncer.kt`.
- Für jeden in der App hinterlegten Benutzer wird `GET <server>/v1/account` abgefragt und alle dort
  gespeicherten Abos (dieselbe Liste wie in der ntfy-Web-App) werden automatisch abonniert
  (Sofortzustellung, alte Nachrichten werden still nachgeladen, ohne Benachrichtigungs-Flut).
- Wird ein Topic im Konto entfernt, verschwindet es auch aus der App – aber nur Topics, die der Sync
  selbst angelegt hat. Manuell angelegte Topics und UnifiedPush-Abos bleiben unangetastet.
- Ist der Server nicht erreichbar, wird nichts gelöscht.
- In der App abonnierte Topics werden ins Konto übernommen (`POST /v1/account/subscription`),
  gelöschte dort entfernt (`DELETE /v1/account/subscription`).
- Ausgelöst: beim App-Start, beim Herunterziehen der Liste, beim Anlegen/Ändern eines Benutzers
  und stündlich im Hintergrund (bestehender PollWorker, keine zusätzlichen Weckvorgänge).
- Abschaltbar: Einstellungen → Allgemein → „Themen aus Konto synchronisieren“.

## Akku / Zuverlässigkeit
- Standard-Verbindungsprotokoll ist WebSocket statt JSON-Stream (Client-Ping alle 3 Minuten statt
  Server-Keepalive-Text alle 45 s).
- Wakelock bei eingehenden Nachrichten wird immer freigegeben (`try/finally`) und ist auf 1 Minute
  statt 10 Minuten begrenzt.

## Design
- Dynamische Farben (Material You, Android 12+) standardmäßig an.

## Sonstiges
- App-ID `com.wagnermartin.ntfysync`, Name „ntfy Sync“ – läuft parallel zur Original-App.
- Release-Build wird mit dem lokalen Debug-Schlüssel signiert (nur für den Eigengebrauch).

## Bauen
```
./gradlew assembleFdroidRelease
```
APK nach dem Bauen: `app/build/outputs/apk/fdroid/release/app-fdroid-release.apk`

Fertig gebaute APK zum Installieren: `apk/ntfy-sync.apk`
