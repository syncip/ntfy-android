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
- Ausgelöst: beim App-Start, beim Herunterziehen der Liste, beim Anlegen/Ändern eines Benutzers,
  stündlich im Hintergrund und bei jedem Verbindungsaufbau zum Server (höchstens alle 5 Minuten;
  das Funkmodul ist dann ohnehin aktiv).
- Abschaltbar: Einstellungen → Allgemein → „Themen aus Konto synchronisieren“.

## Anmeldung per Token
- Neue Klasse `app/src/main/java/io/heckel/ntfy/msg/AccountTokens.kt`.
- Beim Anlegen eines Benutzers meldet sich die App einmal mit dem Kennwort an, erstellt ein
  Zugangs-Token (`POST /v1/account/token`, Bezeichnung „ntfy Sync (Gerätename)“) und speichert nur
  das Token. Das Kennwort wird nie gespeichert.
- Tokens werden als `Authorization: Bearer` gesendet.
- Beim Löschen oder Ändern eines Benutzers wird das alte Token auf dem Server widerrufen.
- Bereits gespeicherte Kennwörter werden beim nächsten Sync automatisch durch ein Token ersetzt.
- Schlägt die Anmeldung fehl, wird der Benutzer nicht gespeichert. Nur wenn ein Server gar keine
  Tokens kennt (404), bleibt das Kennwort als einzige Möglichkeit gespeichert.

## Ruhezeiten nach Priorität
- Einstellungen → Benachrichtigungen → „Ruhezeiten“ (Standard: aus, 22:00–07:00).
- Im Zeitfenster kommen Benachrichtigungen unterhalb der gewählten Priorität lautlos (sichtbar,
  aber ohne Ton/Vibration). „Trotzdem mit Ton“: Standard ist hohe und dringende Priorität.
- Wird nur beim Eintreffen einer Nachricht geprüft – keine Timer, keine Wecker, kein Akkuverbrauch.
- Logik: `app/src/main/java/io/heckel/ntfy/msg/QuietHours.kt`, Tests in `app/src/test/...`.

## Alle als gelesen markieren
- Häkchen-Symbol oben auf der Hauptseite, nur sichtbar, wenn es ungelesene Nachrichten gibt.
- Entfernt alle offenen Benachrichtigungen und die „Neu“-Zähler aller Themen.

## Konto-Seite
- Hauptmenü → „Konto“ oder Einstellungen → „Konto“ (`ui/AccountActivity.kt`, `msg/AccountApi.kt`).
- Zeigt Benutzer, Rolle/Tarif, Nutzung mit Limits (nur Zeilen mit Limit), reservierte Themen
  (anlegen/aufheben), Zugangs-Tokens (fremde widerrufen; das Token dieser App ist geschützt) und
  „Kennwort ändern“.
- Daten werden nur geladen, solange die Seite offen ist (Herunterziehen = neu laden).

## Aufgeräumt
- „Fehler melden“ im Menü ausgeblendet (würde zum Original-Projekt führen).

## Akku / Zuverlässigkeit
- Standard-Verbindungsprotokoll ist WebSocket statt JSON-Stream (Client-Ping alle 3 Minuten statt
  Server-Keepalive-Text alle 45 s).
- Wakelock bei eingehenden Nachrichten wird immer freigegeben (`try/finally`) und ist auf 1 Minute
  statt 10 Minuten begrenzt.

## Design
- Dynamische Farben (Material You, Android 12+) standardmäßig an.
- Eigenes App-Icon (Vektor): ntfy-Sprechblase mit Sync-Pfeilen auf petrolblauem Verlauf.
  Dateien: `app/src/main/res/drawable/ic_launcher_{foreground,background,monochrome}.xml`.

## Sonstiges
- App-ID `com.wagnermartin.ntfysync`, Name „ntfy Sync“ – läuft parallel zur Original-App.
- Standard-Server: `https://ntfy.1337733.xyz` (`app_base_url` in `res/values/values.xml`).

## Signierschlüssel (wichtig: Backup!)
Release-Builds werden mit einem eigenen Schlüssel signiert. Er liegt **außerhalb** des Repos:

```
C:\Users\Martin\.ntfy-sync-signing\
    ntfy-sync-release.jks     (Schlüssel)
    keystore.properties       (Pfad, Alias und Passwort)
```

Diesen Ordner komplett sichern (z. B. Passwort-Manager oder verschlüsselter USB-Stick). Ohne ihn
lassen sich installierte Apps nicht mehr aktualisieren, nur deinstallieren und neu installieren.
Anderen Speicherort nutzen: in `local.properties` `ntfySync.keystoreProperties=<pfad>` eintragen.

## Updates vom Original übernehmen
```
git fetch upstream
git merge upstream/main
```

## Bauen
```
./gradlew assembleFdroidRelease
```
APK nach dem Bauen: `app/build/outputs/apk/fdroid/release/app-fdroid-release.apk`

Fertig gebaute APK zum Installieren: `apk/ntfy-sync.apk` (nicht im Git; nach einem Build von Hand aus dem Build-Ordner kopieren)
