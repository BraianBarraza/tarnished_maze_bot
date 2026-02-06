Buenísimo: con lo que tienes ahora, el “esqueleto” está bien, pero todavía faltan piezas clave para que **realmente se mueva, elija objetivos y cumpla con los requisitos de la nota** (Control-Panel y Visualización).

Abajo te dejo una **lista simple** de lo que falta y **cómo implementarlo** (paso a paso, sin complicarte).

---

## 0) (Muy importante) Asegurar que el profe “encuentre” tu Strategy

En el enunciado se pide que **la Strategy esté en un subpaquete de** `de.dreamcube.mazegame.client.maze.strategy` .
Ahora tu Strategy está en `de.dreamcube.mazegame.client.bots...` .

**Qué hacer (elige 1):**

* **Opción A (recomendada):** mueve `BfsScoringStrategy` (y si quieres todo el bot) a `de.dreamcube.mazegame.client.maze.strategy.malenia.tarnished...`
* **Opción B (wrapper):** crea una clase mínima dentro de `de.dreamcube.mazegame.client.maze.strategy.malenia.tarnished` que solo delegue a tu implementación actual.

*(Esto evita sorpresas al juntar todas las estrategias en un solo proyecto.)*

---

## 1) Conectar el botón Pause/Resume para que “haga algo”

Tu panel crea el botón pero **no cambia el estado**.
Y `WorldState` ya tiene `paused`.
El profe espera que el control panel influya en el bot.

**Implementación mínima:**

* En `SimpleBotControlPanel`, agrega un `ActionListener`:

    * `state.paused = !state.paused`
* En `getNextMove()`:

    * si `worldState.paused` → `return Move.DO_NOTHING;`

Con eso ya cumples el requisito de Control-Panel de forma clara.

---

## 2) Llenar el MazeModel (si no, no hay pathfinding)

Tu `MazeModel.updateFromMaze(...)` está vacío.

**Qué implementar:**

* Guardar `width`, `height`
* Crear `walkable = new boolean[width][height]`
* Parsear `lines` (cada string es una fila):

    * poner `walkable[x][y] = true/false` según el char de esa celda

**Cómo hacerlo sin adivinar el formato:**

* Busca en el proyecto dónde se pinta el maze (renderer) o dónde se procesa el laberinto para la UI; ahí verás qué char significa “muro” vs “suelo”.
* Copia esa misma lógica para `MazeModel`.

---

## 3) Mantener lista de baits actualizada en WorldState

`WorldState.baits` existe, pero nunca se llena/actualiza.

**Dos opciones simples:**

* usar un listener de baits y actualizar `worldState.baits` cuando cambian.

**MVP:** con la opción A ya puedes avanzar rápido.

---

## 4) Implementar `OrientedBfs.computeFrom(...)` + almacenar resultados

Tu BFS ahora mismo devuelve `MAX_VALUE` y `DO_NOTHING`.

**Qué necesitas dentro de `OrientedBfs`:**

* Estructuras para:

    * `dist[state]` (distancia mínima)
    * `prev[state]` o `prevMove[state]` (para reconstruir el primer movimiento)
* Un método `computeFrom(startX, startY, dir)` que:

    * inicializa arrays,
    * hace BFS sobre estados `(x,y,dir)`,
    * considera vecinos:

        * TURN_L, TURN_R (cambian dir, mismo x,y)
        * STEP (cambia x,y según dir si `mazeModel.walkable`)

**Tip KISS (rápido y eficiente):**

* Codifica el estado como un `int`:

    * `idx = ((y * width) + x) * 4 + dirIndex`
* Usa `ArrayDeque<Integer>` como cola.

---

## 5) Selección de objetivo por score (decisión)

Aún no eliges target, `currentTarget` no se usa.

**Qué implementar en `BfsScoringStrategy.getNextMove()`:**

1. Si paused → `DO_NOTHING`
2. Obtener tu posición y dirección actual (del API de Strategy/Player snapshot)
3. `bfs.computeFrom(myX, myY, myDir)`
4. Para cada bait:

    * `d = bfs.distanceTo(bx, by)`
    * `value =` según tipo de bait
    * `score = value - lambda * d`
5. Escoger el bait con score más alto y guardarlo como `worldState.currentTarget`
6. Devolver `bfs.firstMoveTo(targetX, targetY)`

Los valores de baits están definidos (Gem 314, Coffee 42, Food 13, Trap -128).

---

## 6) Anti ping-pong (para que no haga “izq-der-izq-der”)

El enunciado advierte explícitamente del problema de cambiar de objetivo y quedarse oscilando.

**Regla simple (suficiente):**

* Mantén `currentTarget` mientras:

    * siga existiendo en la lista,
    * y sea alcanzable (`distance != INF`)
* Cambia de objetivo solo si:

    * desaparece / inalcanzable, o
    * el nuevo target tiene score > `oldScore * 1.2` (20% mejor)

---

## 7) Fallback cuando no hay baits alcanzables

Tu bot debe soportar casos raros y no “morirse” (y no conviene quedarse quieto siempre). El doc comenta que a veces quedarse quieto pasa, pero para nota suele ser mejor tener un fallback básico.

**Fallback KISS:**

* Si no hay target válido:

    * intenta `STEP`
    * si no se puede, `TURN_L`
      Esto ya evita “no hace nada”.

---

## 8) Visualización mínima real (cumplir requisitos)

Tu `TargetVisualization` no dibuja nada aún.
El profe pide que sea **relativo al maze**, use `offset` y reaccione a `zoom`.

**Implementación mínima:**

* Pasa `WorldState` al `TargetVisualization` (para leer `currentTarget`)
* En `paintComponent(Graphics g)`:

    * si `currentTarget != null`:

        * calcula posición en píxeles usando:

            * `offset` (para que se mueva con el maze)
            * `zoom` (para que escale)
        * dibuja un rectángulo/círculo sobre la celda objetivo

*(No necesitas dibujar el camino todavía: con marcar el target ya cumple.)*

---

# Orden recomendado (para ir “viendo progreso” rápido)

1. **Pause real** (botón + if en getNextMove)
2. **Fallback simple** (STEP / TURN) → ya “se mueve”
3. **Actualizar baits** (aunque sea leyendo cada tick)
4. **MazeModel updateFromMaze**
5. **OrientedBfs.computeFrom + distanceTo**
6. **Scoring + elegir target**
7. **firstMoveTo (reconstrucción) + anti ping-pong**
8. **Visualización del target (offset + zoom)**

---

Si me pegas (o subes) **el archivo base** donde el juego entrega el maze (por ejemplo el `MazeEventListener` o la parte de UI que renderiza el laberinto), te digo exactamente **qué caracteres** significan pared/suelo y te doy el `updateFromMaze()` listo sin suposiciones.
