***

# Abschlussprojekt Maze-Bot

## Organisatorisches

- Bearbeitungszeit: ~4 Wochen (Ferien ausgenommen)
- Abgabetermin: Dienstag 10.02.2026
- Teilt euch heute in Gruppen auf (1–2 Personen).
- Es wird empfohlen, die Abgabe in Git zu machen.
- Stellt Fragen in Teams und schaut auch so regelmäßig dort rein.
- Falls es Updates gibt, werden sie dort erscheinen.

## Inhalt

Im Abschlussprojekt geht es darum, einen kleinen Bot zu programmieren, der eigenständig durch einen Irrgarten (Maze) wandern kann und dabei zielgerichtet wertvolle Gegenstände findet und einsammelt. 

## Hintergrundgeschichte

Die Basis ist ein Projekt, welches an der Uni Koblenz im Rahmen einer Vorlesung von den Studenten entwickelt werden sollte. 
Das „Maze-Game“ sollte die Studenten spielerisch in die komplexeren Themen der Java-Programmierung einführen. 
Das Spiel ist als Multiplayer-Spiel ausgelegt und im Rahmen der Veranstaltung stetig gewachsen. 
Der Übungsleiter (Dr. Volker Riediger) hat den Server gestellt und die Studenten mussten nach und nach den Client eigenständig entwickeln. 

Folgende Dinge wurden (u.a.) von den Studenten entwickelt: 

- Serveranbindung unter Berücksichtigung des Protokolls
- Grafisches UI, um den Irrgarten, die Spieler und die Items (Baits) darzustellen
- Die Möglichkeit, eine Spielfigur über die Tastatur zu steuern
- Die Möglichkeit, eine selbstständig agierende Spielfigur (Bot) einzusetzen
- Die Programmierung eines möglichst effizienten Bots

Als Abschluss gab es dann ein Contest-Event, in dem die einzelnen Gruppen die Möglichkeit hatten, ihre Bots auf demselben Server gegeneinander antreten zu lassen. 
Gewonnen hatte die Gruppe, deren Bot nach einer vorher festgelegten Zeit die meisten Punkte sammeln konnte. 
Gemeinsames Ziel war es, den epischen Bot von Volker namens „ninja“ zu bezwingen („ninja“ heißt jetzt „ninja_ng“). 

Da das ganze Vorgeplänkel langweilig und zeitraubend war, fokussieren wir uns für diese Aufgabe auf den letzten Punkt. 

## Eure Aufgabe

Eure Aufgabe besteht darin, basierend auf einem vorgegebenen Maze-Client, einen autonomen Spieler (Bot) zu implementieren, der eigenständig am Spiel teilnehmen kann. 

Zum Bot gehören folgende Dinge: 

- Ein geeignetes Datenmodell, um das Maze abzuspeichern
- Die Strategie selbst
- Eine (einfache) Bot-Kontrolle
- Eine (einfache) Visualisierung

Ergänzend zur Implementierung müsst ihr eine kurze Ausarbeitung verfassen. 

- Umfang: ca. 8–10 Seiten
- Inhalt: Wie eure Strategie funktioniert, wie sie zustande kam und was ihr euch bei euren Designentscheidungen gedacht habt
- Sprache: komplett Deutsch oder komplett Englisch

Anders als in den letzten Jahren, ist sowohl der Server-Code als auch der Client-Code einsehbar. 
Beides wird euch aber nicht viel nutzen, da beides nicht mehr in Java geschrieben ist. 

## Materialien

Folgende Materialien stehen euch zur Verfügung: 

- Zugriff auf das Repository, in dem sowohl der Server als auch der Client implementiert sind:
    - <https://github.com/strauss/maze>
    - Hier findet ihr diverse Dokumentationsdateien.
- Aufgabenstellung und Hilfsmaterial
- Ein Template-Repository, welches euch als Basis für euren Code dient:
    - <https://github.com/strauss/maze-bots-template>
    - Das müsst ihr forken bzw. das Template anwenden.
    - Alternativ: als ZIP herunterladen.
- KDoc und zusätzlich Javadoc für den Client- und Common-Teil
- Server als Zip/Jar (Bauanleitung im Repository)
- Ein Dokument zur „Hilfestellung“, wie man am besten vorgeht (nach den Ferien)
    - Achtung: nur lesen, wenn ihr ohne Hilfe keinen Start findet.

Über das Kommunikationsprotokoll müsst ihr euch keine Gedanken machen. 
Das wird vom Client „weggekapselt“. 

## Spielregeln des Maze-Games

- Der Irrgarten ist zufällig generiert.
- Die Spielfiguren werden zufällig im Irrgarten verteilt.
- Die Figuren können sich pro „Tick“
    - nach links drehen (TURN_L)
    - nach rechts drehen (TURN_R)
    - oder sich vorwärts in Blickrichtung bewegen (STEP).
- Der Server gibt den Takt vor (geschieht automatisch, Geschwindigkeit kann sich ändern).
- Es ist auch erlaubt, nichts zu tun (DO_NOTHING), aber damit gewinnt man nicht.
- Jeder Client/jede Spielfigur „sieht“
    - den kompletten Irrgarten
    - die Position aller anderen Spielfiguren
    - und alle Baits.

### Baits und Punkte

Die Baits haben unterschiedliche Werte (Buchstaben in Klammern auf dem Spielfeld): 

- (G)em: 314
- (C)offee: 42
- (F)ood: 13
- (T)rap: −128

Sammelt eine Figur einen Bait ein (nimmt sie die Position eines Baits ein), dann: 

- wird die entsprechende Punktzahl zum Punktestand addiert
- bei einer Trap werden 128 Punkte abgezogen

Nachdem ein Bait eingesammelt wurde, generiert der Server an einer zufälligen Position ein zufälliges neues Bait. 
Die Anzahl der Traps bleibt dabei gering. 

Wenn ein Spieler eine Trap einsammelt: 

- werden ihm 128 Punkte abgezogen
- er wird zufällig an eine andere Position im Maze teleportiert

Es gibt unsichtbare Baits, die nur der Server kennt (insbesondere gemein bei Traps). 
Damit soll erreicht werden, dass selbst die ausgefeilteste Strategie fehlschlagen kann. 

### Kollisionen und weitere Regeln

- Wenn zwei Spieler miteinander kollidieren, werden beide Spieler zufällig an eine andere Position im Maze teleportiert. 
- Je nach Serverkonfiguration kann der Verursacher zusätzlich bestraft werden:
    - er „verliert“ ein zufälliges Bait, was am Unfallort liegen bleibt. 
- Der Server kann Baits jederzeit entfernen und neu generieren (Bots müssen damit klarkommen). 
- Bots müssen damit klarkommen, wenn sie nicht jedes Bait erreichen können. 
- Testet das mit einer geeigneten Map (liegt dem Server bei). 

## Wie entwickelt man eine eigene Strategie?

### Die eigentliche Strategie-Klasse

Die Klasse `Strategy` ist die abstrakte Basisklasse aller Strategien. 
Sie enthält Mechaniken, um sich in den Client einzufügen und auf Server-Ereignisse zu reagieren. 
Um eine eigene Strategie zu entwickeln, muss eine konkrete Implementierung dieser Klasse erzeugt werden. 

Wichtige Methoden: 

- `getNextMove()`
    - wird aufgerufen, wann immer der Client den nächsten Move an den Server senden möchte
    - muss in jeder Strategie implementiert werden
- `initializeStrategy()`
    - kann überschrieben werden
    - wird aufgerufen, sobald der `MazeClient` initialisiert und gesetzt wurde

Zulässige Moves: 

- `TURN_L`: nach links drehen
- `TURN_R`: nach rechts drehen
- `STEP`: vorwärts gehen
- `DO_NOTHING`: nichts tun (nur Client-Seite, kein Kommando an Server)

### Events und Event-Listener

Um auf Ereignisse im Spiel reagieren zu können, müssen Event-Listener implementiert werden. 

Wichtige Listener: 

- `MazeEventListener`
    - wichtig, um das Maze zu parsen
    - ihr braucht ein Datenmodell, um das Maze abzulegen
- `BaitEventListener`
    - wichtig, um mitzubekommen, wenn Baits erscheinen oder verschwinden
    - Alternative: `getBaits()`
- `PlayerMovementListener`
    - wichtig, um zu erkennen, wenn Spieler erscheinen, verschwinden oder sich bewegen
    - Alternative: `getPlayers()`

Es gibt weitere Event-Listener; dafür bitte die Doku lesen. 

Damit die eigene Strategie „gefunden“ wird, muss die Klasse mit der Annotation `@Bot("name")` versehen werden (vgl. `DumbStrategy`). 

## Der Maze-Client

Die Klasse `Strategy` enthält eine Referenz auf den `MazeClient`. 
Diese Klasse enthält nützliche Funktionen, ist aber dank der Event-Listener nicht mehr so wichtig wie früher. 

Über den `MazeClient` könnt ihr u.a.: 

- auf Spieler zugreifen
- auf Baits zugreifen
- mit `broadcast` und `whisper` Nachrichten verschicken (Chat-Funktion, sparsam nutzen)

## Modellierung von Baits

- Baits bewegen sich nicht. 
- Ein Objekt der Klasse `Bait` verändert sich inhaltlich nicht, sobald es generiert wurde. 
- Es kann höchstens verschwinden, wenn es eingesammelt wurde. 

Daher ist die Modellierung von Baits relativ simpel. 

## Modellierung von Spielern

- Ein Spieler wird durch die Klasse `Player` dargestellt. 
- Spieler bewegen sich; ihre Werte ändern sich ständig und können geändert werden. 

Um unzulässige Änderungen zu verhindern: 

- `PlayerView`
    - read-only Zugriff auf `Player`
    - nicht thread-sicher, aber immer aktuelle Daten
- `PlayerSnapshot`
    - Schnappschuss von `PlayerView`
    - wird in allen Events verwendet
    - garantiert Integrität bei sich verändernden Daten
    - Nachteil: Daten können leicht veraltet sein

Bitte mit `PlayerSnapshot` arbeiten. 
Falls der aktuelle Wert benötigt wird, enthält `PlayerSnapshot` eine Referenz auf die `PlayerView`, aus der der Schnappschuss entstanden ist. 

## Bot-Kontrolle (Control Panel)

Die Klasse `Strategy` erlaubt es, mit `getControlPanel()` ein `JPanel` bereitzustellen. 
Damit kann ein kleines Control-Panel ins UI gelegt werden. 

- Es erscheint unten rechts, wenn man auf den kleinen Button in der Statusleiste klickt.
- Der Button ist nur sichtbar, wenn irgendeine zusätzliche Kontrolle verfügbar ist (Server-Kontrolle, Bot-Kontrolle, Visualisierung). 

Über dieses Panel könnt ihr zur Laufzeit Attribute eures Bots ändern. 
Teil der Aufgabe ist es, ein solches Control-Panel zu erstellen. 

Beispielanforderung: 

- Wenn euch nichts einfällt, reicht ein Button, der den Bot „anhalten“ lässt.
- „Anhalten“ bedeutet, dass der Bot nur noch `DO_NOTHING` liefert.
- Beim erneuten Drücken läuft der Bot normal weiter.

## Visualisierung

Die Klasse `Strategy` erlaubt es, mit `getVisualizationComponent()` eine `VisualizationComponent` bereitzustellen. 

- Ihr müsst eine Implementierung dieser Klasse anfertigen.
- Ihr könnt beliebig kreativ sein.
- Minimalanforderung: z.B. das „anvisierte Bait“ mit eurer Spielerfarbe markieren. 

Wichtig: 

- Visualisierung muss sich auf Spielelemente beziehen.
- Sie muss relativ zum Maze gezeichnet werden (benötigt den `offset`-Punkt).
- Sie muss korrekt zoomen (mit `zoom` skalieren).

Eine gute Visualisierung hilft beim Debugging und bei der Analyse/Optimierung der Strategie. 

## Starthilfe (nach den Ferien)

Ihr erhaltet ein ausführlicheres Dokument mit einer möglichen Vorgehensweise zur Lösungsfindung. 

- Dieses Dokument kommt später.
- Lest es nur, wenn ihr keinen Plan habt, wie ihr anfangen sollt.
- Ob ihr es nutzt oder nicht, beeinflusst die Note nicht, kann aber euren Lernerfolg reduzieren. 

## Regeln für die Abgabe

- Eure Strategieklasse muss in ein Unterpaket von  
  `de.dreamcube.mazegame.client.maze.strategy` gelegt werden. 
- Hintergrund: Alle Strategien werden beim Betreuer in einem Projekt zusammengeführt. 
- Wenn ihr per ZIP-Datei abgebt, gebt bitte das *ganze* Projekt ab. 
- Innerhalb eurer Strategie dürft ihr alles anwenden, was ihr gelernt habt bzw. euch anlernt. 
- Eure Strategie muss mit unterschiedlichen Spielfeldgrößen klarkommen. 
    - Der Server ist variabel.
    - Die Spielfeldgröße ändert sich im Lauf des Spiels nicht (fix beim Serverstart). 

## Tipps für die Abgabe

- Zum Vergleich von Varianten könnt ihr mehrere Strategien registrieren. 
    - Strategien brauchen einen parameterlosen Konstruktor.
    - Parameter können über einen Superklassen-Konstruktor gesteuert werden (Ableitung).
- Manchmal ist es besser, ein wertvolleres, aber weiter entferntes Item zuerst einzusammeln. 
- Manchmal lohnt sich ein weit entferntes Item nicht, wenn jemand anderes schneller dort ist. 

Testideen: 

- Mehrere identische Strategien unter verschiedenen Namen gleichzeitig laufen lassen (mehrere Client-Instanzen/Run-Configurations).
- Gegen ein anderes Team/einen anderen Teilnehmer auf demselben Server antreten. 

Server zum Testen: 

- Es wird ein Server bereitgestellt, der aus dem Internet erreichbar ist.
- Termin/Details über den Teams-Channel. 

Achtung bei Zielwahl: 

- Manchmal erscheinen zwei Items gleichermaßen verführerisch.
- Vermeidet das „Esel zwischen zwei Feigenbäumen“-Problem (ständiges Hin- und Herdrehen zwischen zwei Zielen).

## Lernziele

In diesem Projekt lernt ihr: 

- Wie man Code gegen eine gegebene Library entwickelt, die ihr nicht ändern könnt.
- Wie man mit Dokumentation umgeht, die nicht immer 100% klar ist.
- Wie man sich eigenständig in neue Konzepte einarbeitet (z.B. UI-Anteil).

## Bewertungsrichtlinie

### Botverhalten

- Macht 1/3 der Gesamtnote aus. 
- Wenn der Bot sich wie ein „Dummy“ verhält → Note 5. 
- Wenn er nichts tut → Note 6. 
- Der Rest liegt im Ermessen des Betreuers. 

### Code

Der Code zählt 1/3 zur Note. 

Wichtige Kriterien: 

- Lesbarkeit (Variablennamen, Strukturierung)
- Namen müssen auf Englisch sein
- Dokumentation im Code (Javadoc und Kommentare an schwer verständlichen Stellen)
- Effizienz (Speicher- und Laufzeitverhalten)
- Architektur:
    - sinnvolle Verteilung auf mehrere Klassen (nicht zu viele, nicht zu wenige)
    - sinnvolle Vererbungshierarchie
    - sinnvolle Verwendung von Interfaces
    - übersichtliche Paketstruktur (nicht zu viele, nicht zu wenige)
- Konsistenz zwischen Ausarbeitung und Umsetzung

Warnung: 

- Falls keine sinnvolle oder überhaupt keine Dokumentation im Code vorhanden ist, kann der Code-Teil mit Note 5 bewertet werden – unabhängig davon, wie gut der Code ist. 
- Javadoc kann auf Deutsch oder Englisch sein. 

### Control-Panel

- Für Note „2“ wird mindestens ein UI-Element erwartet, das aktiv Einfluss auf den Bot nimmt (z.B. Bot stoppen). 
- Das Control-Panel zählt 1/6 des Code-Teils (also 1/18 der Gesamtnote). 

### Visualisierung

- Für Note „2“ wird mindestens ein grafisches Element erwartet, das: 
    - relativ zum Maze gezeichnet wird (bewegt sich mit dem Maze),
    - auf den Zoom reagiert (wächst/schrumpft),
    - mit dem Spielgeschehen zu tun hat (z.B. Markierung des aktuellen Ziels). 

- Die Visualisierung zählt 1/3 des Code-Teils (1/9 der Gesamtnote). 

### Strategie (Implementierung)

- Die Strategie zählt 1/2 des Code-Teils (1/6 der Gesamtnote). 

### Schriftliche Ausarbeitung

- Zählt 1/3 der Gesamtnote. 
- Ihr müsst eine sinnvolle Balance zwischen „zu knapp“ und „zu viel“ finden. 
- 8–10 Seiten sind in der Regel angemessen. 
- Bilder und Diagramme sind ausdrücklich erwünscht. 
- Ausarbeitung und Code/Verhalten müssen zusammenpassen. 

## Bonus: Contest-Event

Nach der Abgabe wird ein Contest-Event veranstaltet. 

- Termin und Ort werden im Teams-Channel bekannt gegeben.
- Eure Bots treten gegeneinander auf demselben Server an.
- Es kann Überraschungen geben. 
- `ninja_ng` wird mitlaufen. 
- Der Betreuer wird selbst einen Bot stellen; ggf. auch weitere Kollegen. 
- Es gibt zwei Runden: 
    - Runde 1: nur Kursteilnehmer treten gegeneinander an.
    - Runde 2: jeder kann antreten, der einen Bot hat.
- Preise gibt es nur in Runde 1 (für den besten Bot unter den Kursteilnehmern). 

***