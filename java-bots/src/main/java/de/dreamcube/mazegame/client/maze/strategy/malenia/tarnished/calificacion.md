## Matriz de evaluación (100%)

| Área evaluada                                     | Peso (sobre 100%) | Qué se mira / evidencia                                                                                                                                              | “Reglas duras” del documento                                                                                                                     |
| ------------------------------------------------- | ----------------: | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| **Comportamiento del bot (en el juego)**          |         **33,3%** | Si el bot se mueve de forma inteligente, busca objetivos, reacciona a cambios (baits que aparecen/desaparecen), evita pérdidas innecesarias (traps/colisiones), etc. | Si se comporta como “dummy” → **nota 5**. Si **no hace nada** → **nota 6**. (El resto es a criterio del evaluador.)                              |
| **Código (calidad técnica)**                      |         **33,3%** | Legibilidad, estructura, arquitectura, eficiencia, consistencia con la memoria escrita, etc.                                                                         | **Si no hay documentación útil (Javadoc/comentarios)**, el **bloque de código puede ser nota 5**, independientemente de lo bueno que sea el bot. |
| **Ausarbeitung / Memoria escrita (8–10 páginas)** |         **33,3%** | Explicar estrategia, cómo se llegó a ella y decisiones de diseño. Coherencia con el código y el comportamiento real. Incluir imágenes/diagramas ayuda.               | Debe ser **todo en alemán o todo en inglés** (según el documento). Debe “encajar” con lo implementado.                                           |

---

## Desglose interno del bloque “Código” (33,3%)

El documento sí define pesos internos dentro del **Código**. Convertido a % del total:

| Sub-área dentro de “Código”         | % del total | Qué se espera                                                                                                                                                                                   |
| ----------------------------------- | ----------: | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Implementación de la estrategia** |   **16,7%** | La lógica del bot: toma de decisiones, pathfinding, manejo de eventos, selección de objetivos, estabilidad (evitar “dudar” entre dos objetivos), etc.                                           |
| **Visualización**                   |   **11,1%** | Un elemento gráfico ligado al juego (p. ej., marcar el bait objetivo), dibujado **relativo al maze**, que use **offset** y escale con **zoom**.                                                 |
| **Control-Panel (UI de control)**   |    **5,6%** | Al menos un control que afecte activamente al bot (ej.: **botón de pausa/stop** que haga que el bot devuelva `DO_NOTHING`). Para aspirar a **nota 2**, esto es requisito mínimo según el texto. |

> Comprobación: 16,7% + 11,1% + 5,6% = **33,3%** (todo el bloque de “Código”).

---

## Checklist práctico (para que lo uses como “lista de evaluación”)

### A) Comportamiento del bot — 33,3%

* [ ] No se queda quieto (evita `DO_NOTHING` salvo pausa intencional).
* [ ] Decide objetivos (baits) de forma consistente (evita “Esel zwischen zwei Feigenbäumen” = cambiar de objetivo constantemente).
* [ ] Considera valor vs distancia (a veces conviene algo más valioso aunque esté más lejos).
* [ ] Tolera cambios del servidor: baits que desaparecen/ reaparecen; baits invisibles; items inaccesibles.
* [ ] Maneja traps y colisiones (teleports) sin “romperse” ni quedarse en bucle.
* [ ] Funciona con distintos tamaños de mapa.

### B) Código — 33,3% (calidad general)

**Criterios que nombra el documento (sin peso individual):**

* [ ] **Nombres en inglés** (variables, métodos, clases).
* [ ] **Legibilidad**: nombres claros, estructura entendible.
* [ ] **Documentación**: Javadoc + comentarios donde sea difícil. *(Ojo: sin esto te pueden poner nota 5 en el bloque de código.)*
* [ ] **Eficiencia**: uso razonable de CPU/memoria.
* [ ] **Arquitectura**:

    * [ ] División en varias clases con sentido (ni todo en una clase, ni “100 clases”).
    * [ ] Herencia/jerarquía con sentido.
    * [ ] Uso razonable de interfaces.
    * [ ] Estructura de paquetes clara (ni demasiado plana ni demasiado profunda).
* [ ] Consistencia entre lo que explicas en la memoria y lo que hace el código.

### B1) Implementación de estrategia — 16,7%

* [ ] `getNextMove()` bien implementado (sin bloqueos, sin decisiones incoherentes).
* [ ] Modelo de datos del maze adecuado.
* [ ] Uso correcto de eventos (por ejemplo `PlayerSnapshot` para integridad).
* [ ] Estrategia robusta ante cambios del juego.

### B2) Visualización — 11,1%

* [ ] Elemento visual relacionado con el juego (objetivo actual, ruta prevista, zona de peligro, etc.).
* [ ] Se dibuja **relativo al maze** con `offset`.
* [ ] Escala correctamente con `zoom`.

### B3) Control-Panel — 5,6%

* [ ] Existe panel (`JPanel`) accesible desde el botón del UI.
* [ ] Al menos **un control** que cambie el comportamiento en runtime.
* [ ] Ejemplo mínimo recomendado: botón **Start/Stop** (Stop ⇒ `DO_NOTHING`).

### C) Memoria escrita (Ausarbeitung) — 33,3%

* [ ] 8–10 páginas (aprox.).
* [ ] Explica **cómo funciona** la estrategia.
* [ ] Explica **cómo llegaste** a esa estrategia (alternativas, pruebas, decisiones).
* [ ] Explica decisiones de diseño (clases, datos, por qué así).
* [ ] Incluye diagramas/imágenes (recomendado).
* [ ] Coherente con el código y con el comportamiento real del bot.

---