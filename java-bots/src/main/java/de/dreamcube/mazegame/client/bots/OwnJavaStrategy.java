package de.dreamcube.mazegame.client.bots;

import de.dreamcube.mazegame.client.maze.Bait;
import de.dreamcube.mazegame.client.maze.PlayerSnapshot;
import de.dreamcube.mazegame.client.maze.events.*;
import de.dreamcube.mazegame.client.maze.strategy.Bot;
import de.dreamcube.mazegame.client.maze.strategy.Move;
import de.dreamcube.mazegame.client.maze.strategy.Strategy;
import de.dreamcube.mazegame.common.maze.PlayerPosition;
import de.dreamcube.mazegame.common.maze.TeleportType;
import de.dreamcube.mazegame.common.maze.ViewDirection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Bot("Tarnished")
public class OwnJavaStrategy extends Strategy
        implements MazeEventListener,
        BaitEventListener,
        PlayerMovementListener,
        PlayerConnectionListener {

    private int width, height;
    private boolean[] walkable;

    private final Map<Long, Bait> baits = new ConcurrentHashMap<>();
    private final Map<Integer, PlayerSnapshot> players = new ConcurrentHashMap<>();

    private Integer myPlayerId = null;
    private volatile PlayerSnapshot me;

    private final Deque<Move> plan = new ArrayDeque<>();
    private volatile boolean paused = false;
    private long tick = 0;
    private final Map<Long, Long> dangerUntilTick = new ConcurrentHashMap<>(); // cellKey -> expiryTick


    @Override
    public void onMazeReceived(int width, int height, @NotNull List<String> mazeLines) {
        this.width = width;
        this.height = height;
        this.walkable = new boolean[width * height];

        // '.' caminable; '#', '-' y otros = no caminable
        for (int y = 0; y < height; y++) {
            String line = mazeLines.get(y);
            for (int x = 0; x < width; x++) {
                char c = line.charAt(x);
                walkable[idx(x, y)] = (c == '.');
            }
        }

        plan.clear();
    }

    @Override
    public void onBaitAppeared(@NotNull Bait bait) {
        baits.put(key(bait.getX(), bait.getY()), bait);
        plan.clear(); // replanificar
    }

    @Override
    public void onBaitVanished(@NotNull Bait bait) {
        baits.remove(key(bait.getX(), bait.getY()));
        plan.clear(); // replanificar
    }

    @Override
    public void onOwnPlayerLogin(@NotNull PlayerSnapshot snap) {
        myPlayerId = snap.getId();
        me = snap;
        players.put(snap.getId(), snap);
        plan.clear();
    }

    @Override
    public void onPlayerAppear(@NotNull PlayerSnapshot snap) {
        players.put(snap.getId(), snap);
        updateMeIfOwn(snap);
    }

    @Override
    public void onPlayerVanish(@NotNull PlayerSnapshot snap) {
        players.remove(snap.getId());
        if (myPlayerId != null && snap.getId() == myPlayerId) {
            me = null;
            myPlayerId = null;
        }
        plan.clear();
    }

    @Override
    public void onPlayerStep(@NotNull PlayerPosition oldPos, @NotNull PlayerSnapshot snap) {
        players.put(snap.getId(), snap);
        updateMeIfOwn(snap);
    }

    @Override
    public void onPlayerTurn(@NotNull PlayerPosition oldPos, @NotNull PlayerSnapshot snap) {
        players.put(snap.getId(), snap);
        updateMeIfOwn(snap);
    }

    @Override
    public void onPlayerTeleport(
            @NotNull PlayerPosition oldPos,
            @NotNull PlayerSnapshot snap,
            TeleportType teleportType,
            Integer causingPlayerId
    ) {
        players.put(snap.getId(), snap);
        updateMeIfOwn(snap);

        // Si me teletransportaron por TRAP, “aprendo” que la zona es peligrosa
        if (myPlayerId != null && snap.getId() == myPlayerId) {
            plan.clear();

            if (teleportType == TeleportType.TRAP) {
                // Marcamos la celda anterior como peligrosa
                markDanger(oldPos.getX(), oldPos.getY());

                // Y también la celda "frente a mí" desde oldPos, por si el trigger fue al entrar
                int[] front = frontCell(oldPos.getX(), oldPos.getY(), oldPos.getViewDirection());
                if (front != null) {
                    markDanger(front[0], front[1]);
                }
            }
        }
    }

    private void updateMeIfOwn(PlayerSnapshot snap) {
        if (myPlayerId != null && snap.getId() == myPlayerId) {
            me = snap;
        }
    }


    @Nullable
    @Override
    protected Move getNextMove() {
        tick++;

        if (paused || walkable == null || me == null || myPlayerId == null) {
            return Move.DO_NOTHING;
        }

        // Si el próximo paso está bloqueado (otro jugador/trap/danger), replanifica
        if (!plan.isEmpty() && plan.peekFirst() == Move.STEP) {
            if (!canStepForward(me)) {
                plan.clear();
            }
        }

        if (plan.isEmpty()) {
            boolean planned = planToBestTarget();
            if (!planned) {
                // Exploración simple: intenta avanzar, si no puedes, gira a la derecha
                return canStepForward(me) ? Move.STEP : Move.TURN_R;
            }
        }

        return plan.pollFirst();
    }

    private boolean planToBestTarget() {
        Set<Long> occupied = currentOccupiedCellsByOthers();

        BfsResult bfsStrict = bfsFrom(me.getX(), me.getY(), true, occupied);
        Bait target = chooseBestTarget(bfsStrict.dist);

        if (target == null) {
            BfsResult bfsRelaxed = bfsFrom(me.getX(), me.getY(), false, occupied);
            target = chooseBestTarget(bfsRelaxed.dist);
            if (target == null) return false;
            return buildPlanFromBfs(bfsRelaxed, target);
        }

        return buildPlanFromBfs(bfsStrict, target);
    }

    private boolean buildPlanFromBfs(BfsResult bfs, Bait target) {
        int startIdx = idx(me.getX(), me.getY());
        int goalIdx = idx(target.getX(), target.getY());

        if (goalIdx < 0 || goalIdx >= bfs.dist.length) return false;
        if (bfs.dist[goalIdx] < 0) return false; // inalcanzable

        List<Integer> path = reconstructPath(startIdx, goalIdx, bfs.parent);
        if (path.isEmpty()) return false;

        plan.clear();
        appendMovesForPath(plan, path, me.getX(), me.getY(), me.getViewDirection());
        return !plan.isEmpty();
    }



    private enum Kind { GEM, LETTER, COFFEE, FOOD, OTHER, TRAP }

    private Bait chooseBestTarget(int[] dist) {
        if (dist == null || dist.length == 0) return null;

        Bait best = null;
        long bestScore = Long.MIN_VALUE;

        for (Bait bait : baits.values()) {
            Kind kind = classify(bait);

            // nunca ir por traps
            if (kind == Kind.TRAP) continue;

            int d = dist[idx(bait.getX(), bait.getY())];
            if (d < 0) continue; // no alcanzable

            // penaliza si la celda es peligrosa (si aún está en dangerUntilTick)
            long dangerPenalty = isDanger(bait.getX(), bait.getY()) ? 1_000_000L : 0;

            // prioridad por “clase” (gema > letra > coffee > food)
            long classBase = switch (kind) {
                case GEM -> 3_000_000L;
                case LETTER -> 2_000_000L;
                case COFFEE -> 1_000_000L;
                case FOOD -> 500_000L;
                default -> 100_000L;
            };

            // utilidad: base + score*1000 - dist*10 - penalty
            long util = classBase + ((long) bait.getScore()) * 1000L - (long) d * 10L - dangerPenalty;

            if (util > bestScore) {
                bestScore = util;
                best = bait;
            }
        }

        return best;
    }

    private Kind classify(Bait bait) {
        try {
            Object type = bait.getType();
            if (type != null) {
                String t = type.toString().toUpperCase(Locale.ROOT);
                if (t.contains("TRAP")) return Kind.TRAP;
                if (t.contains("GEM")) return Kind.GEM;
                if (t.contains("COFFEE")) return Kind.COFFEE;
                if (t.contains("FOOD")) return Kind.FOOD;
                if (t.contains("LETTER") || t.contains("CHAR")) return Kind.LETTER;
            }
        } catch (Exception ignored) {
            // si Bait no expone getType(), no pasa nada
        }

        // fallback por score
        int s = bait.getScore();
        if (s < 0) return Kind.TRAP;
        if (s == 0) return Kind.LETTER;
        if (s >= 300) return Kind.GEM;
        if (s >= 40) return Kind.COFFEE;
        if (s > 0) return Kind.FOOD;
        return Kind.OTHER;
    }

    private static final class BfsResult {
        final int[] dist;
        final int[] parent;

        BfsResult(int[] dist, int[] parent) {
            this.dist = dist;
            this.parent = parent;
        }
    }

    private BfsResult bfsFrom(int startX, int startY, boolean avoidDanger, Set<Long> occupiedByOthers) {
        int n = width * height;
        int[] dist = new int[n];
        int[] parent = new int[n];
        Arrays.fill(dist, -1);
        Arrays.fill(parent, -1);

        int start = idx(startX, startY);
        if (start < 0 || start >= n) return new BfsResult(dist, parent);

        int[] queue = new int[n];
        int head = 0, tail = 0;

        dist[start] = 0;
        queue[tail++] = start;

        final int[] dx = { 1, -1, 0, 0 };
        final int[] dy = { 0, 0, 1, -1 };

        while (head < tail) {
            int cur = queue[head++];
            int cx = cur % width;
            int cy = cur / width;

            for (int i = 0; i < 4; i++) {
                int nx = cx + dx[i];
                int ny = cy + dy[i];

                if (inBounds(nx, ny)) continue;
                int ni = idx(nx, ny);
                if (dist[ni] != -1) continue;

                if (!isCellAllowed(nx, ny, avoidDanger, occupiedByOthers)) continue;

                dist[ni] = dist[cur] + 1;
                parent[ni] = cur;
                queue[tail++] = ni;
            }
        }

        return new BfsResult(dist, parent);
    }

    private boolean isCellAllowed(int x, int y, boolean avoidDanger, Set<Long> occupiedByOthers) {
        if (inBounds(x, y)) return false;

        int i = idx(x, y);
        if (!walkable[i]) return false;

        // evitar trap visible: si hay un bait trap en esa celda, la tratamos como no pisable
        Bait b = baits.get(key(x, y));
        if (b != null && classify(b) == Kind.TRAP) return false;

        // evitar otros jugadores
        if (occupiedByOthers != null && occupiedByOthers.contains(key(x, y))) return false;

        // evitar “zona peligrosa” aprendida
        if (avoidDanger && isDanger(x, y)) return false;

        return true;
    }

    private List<Integer> reconstructPath(int startIdx, int goalIdx, int[] parent) {
        List<Integer> rev = new ArrayList<>();
        int cur = goalIdx;

        while (cur != -1 && cur != startIdx) {
            rev.add(cur);
            cur = parent[cur];
        }
        if (cur != startIdx) return List.of(); // no hay camino

        Collections.reverse(rev);
        return rev;
    }


    private void appendMovesForPath(Deque<Move> out, List<Integer> path, int startX, int startY, ViewDirection startDir) {
        int cx = startX;
        int cy = startY;
        ViewDirection dir = startDir;

        for (int cellIdx : path) {
            int nx = cellIdx % width;
            int ny = cellIdx / width;

            int dx = nx - cx;
            int dy = ny - cy;

            ViewDirection desired = directionFromDelta(dx, dy);
            if (desired == null) {
                out.clear();
                return;
            }

            // girar lo mínimo hasta apuntar al desired
            List<Move> turns = minimalTurns(dir, desired);
            for (Move m : turns) {
                out.addLast(m);
                dir = applyTurn(dir, m);
            }

            out.addLast(Move.STEP);

            cx = nx;
            cy = ny;
        }
    }

    private ViewDirection applyTurn(ViewDirection d, Move m) {
        if (m == Move.TURN_R) return d.turnRight();
        if (m == Move.TURN_L) return d.turnLeft();
        return d;
    }

    private List<Move> minimalTurns(ViewDirection from, ViewDirection to) {
        int right = 0;
        ViewDirection d = from;
        while (!d.equals(to) && right < 4) {
            d = d.turnRight();
            right++;
        }

        int left = 0;
        d = from;
        while (!d.equals(to) && left < 4) {
            d = d.turnLeft();
            left++;
        }

        if (right <= left) {
            List<Move> res = new ArrayList<>(right);
            for (int i = 0; i < right; i++) res.add(Move.TURN_R);
            return res;
        } else {
            List<Move> res = new ArrayList<>(left);
            for (int i = 0; i < left; i++) res.add(Move.TURN_L);
            return res;
        }
    }

    private ViewDirection directionFromDelta(int dx, int dy) {
        if (dx == 1 && dy == 0) return ViewDirection.EAST;
        if (dx == -1 && dy == 0) return ViewDirection.WEST;
        if (dx == 0 && dy == 1) return ViewDirection.SOUTH;
        if (dx == 0 && dy == -1) return ViewDirection.NORTH;
        return null;
    }

    private boolean canStepForward(PlayerSnapshot snap) {
        int[] front = frontCell(snap.getX(), snap.getY(), snap.getViewDirection());
        if (front == null) return false;

        Set<Long> occupied = currentOccupiedCellsByOthers();

        return isCellAllowed(front[0], front[1], true, occupied);
    }

    private int[] frontCell(int x, int y, ViewDirection dir) {
        int dx = 0, dy = 0;

        if (dir == ViewDirection.NORTH) dy = -1;
        else if (dir == ViewDirection.SOUTH) dy = 1;
        else if (dir == ViewDirection.EAST) dx = 1;
        else if (dir == ViewDirection.WEST) dx = -1;
        else return null;

        int nx = x + dx;
        int ny = y + dy;

        if (inBounds(nx, ny)) return null;
        return new int[]{nx, ny};
    }

    private Set<Long> currentOccupiedCellsByOthers() {
        if (myPlayerId == null) return Set.of();
        Set<Long> occ = new HashSet<>();
        for (PlayerSnapshot p : players.values()) {
            if (p == null) continue;
            if (p.getId() == myPlayerId) continue;
            occ.add(key(p.getX(), p.getY()));
        }
        return occ;
    }

    private void markDanger(int x, int y) {
        if (inBounds(x, y)) return;
        dangerUntilTick.put(key(x, y), tick + 250);
    }

    private boolean isDanger(int x, int y) {
        Long until = dangerUntilTick.get(key(x, y));
        if (until == null) return false;
        if (tick >= until) {
            dangerUntilTick.remove(key(x, y));
            return false;
        }
        return true;
    }

    private boolean inBounds(int x, int y) {
        return x < 0 || y < 0 || x >= width || y >= height;
    }

    private int idx(int x, int y) {
        return y * width + x;
    }

    private long key(int x, int y) {
        return (((long) x) << 32) ^ (y & 0xffffffffL);
    }
}
