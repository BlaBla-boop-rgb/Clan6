# Clans 2.0 (Paper 1.21.11)

Clan-System, das komplett über ein Menü (`/clan`) bedient wird. Mit Custom-Icons aus einem Resource Pack.

## Bauen
Java 21 + Maven: `mvn clean package` -> `target/Clans-2.0.0.jar` in `plugins/`.
Oder einfach das Projekt auf GitHub hochladen: die Action in `.github/workflows/build.yml` baut die JAR.

## Resource Pack (Custom-Icons)
Das Pack steckt in der JAR und liegt nach dem ersten Start unter `plugins/Clans/ClansPack.zip`.
Es muss einmal öffentlich erreichbar sein. Trage den Direktlink bei `resource-pack.url` ein
(Standard: die Datei aus dem GitHub-Repo per raw.githubusercontent.com).

Danach `/clan reload` bzw. Server neustarten. Spieler bekommen das Pack beim Join.
Wer das Pack ablehnt, sieht dasselbe Menü mit normalen Minecraft-Items (mode `auto`).

## Eigene Icons
`python3 tools/make_pack.py src/main/resources/ClansPack.zip` erzeugt das Pack neu.
Alle Texturen sind 16x16 und liegen in `assets/clans/textures/item/` (Namen siehe `make_pack.py`).
Du kannst sie in der Zip durch eigene PNGs ersetzen, solange die Dateinamen gleich bleiben.

## Befehle
- `/clan` - Menü (alles andere läuft darüber)
- `/cc <Nachricht>` - Clan-Chat
- `/clan pack` - Icon-Pack erneut senden
- `/clan reload` - Config neu laden (`clans.admin`)

PlaceholderAPI: `%clan_name%`, `%clan_tab%`, `%clan_tag%`, `%clan_kills%`, `%clan_deaths%`, `%clan_blocks%`, `%clan_members%`, `%clan_leader%`

## Design

- Dunkles Panel im Stil von Mineville: rote Kante unter der Titelleiste, Tabs mit Icon und Text, große 2x2-Icon-Karten mit Beschriftung, Scrollleiste. Texturen sind 4-fach hochauflösend (64 px pro Slot) mit 3D-Licht; Schrift: Silkscreen (OFL).
- Die GUI ist statisch (keine Animationen) und sendet nur geänderte Slots - dadurch kein Flackern.
- Das Pack ersetzt `assets/minecraft/textures/gui/container/generic_54.png` (Truhen-/Große-Truhen-Hintergrund). Dadurch
  sehen auch normale Truhen dunkel aus. Willst du das nicht: Datei aus dem Pack löschen (dann hat die Clan-GUI den grauen
  Vanilla-Rahmen) und die ClansPack.zip neu hochladen.
