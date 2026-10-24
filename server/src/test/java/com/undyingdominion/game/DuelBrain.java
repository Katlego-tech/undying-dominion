package com.undyingdominion.game;

import com.undyingdominion.world.Position;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A scripted commander for balance duels (docs/design/characters.md §10). It isn't skill: it plays
 * every kit the same plain way, so a kit that loses to it everywhere is broken, not outplayed.
 *
 * <ul>
 *   <li>spend energy on the retinue up to the cap, keeping back the signature's cost;</li>
 *   <li>cast the basic and the utility off cooldown at the nearest valid target, and a heal only
 *       when one of its own is below 70 %;</li>
 *   <li>cast the signature when 3 or more enemies are in its radius, or the enemy commander is under half;</li>
 *   <li>attack-move everything toward the enemy commander, once two of the retinue have answered or
 *       twenty seconds have passed, so a commander doesn't walk in alone;</li>
 *   <li>a commander that fights from range steps back from an enemy within two tiles.</li>
 * </ul>
 */
public final class DuelBrain {

    /** How far off an enemy can be for a self-cast buff or an aura to be worth it now. */
    private static final int FIGHTING = 6;
    private static final int ORDER_EVERY = 10;
    private static final int MUSTER = 2;
    private static final long MUSTER_TICKS = 200;
    private static final int TOO_CLOSE = 2;

    private final GameEngine engine;
    private final Player me;
    private final Player rival;
    private long lastOrder = Long.MIN_VALUE / 2;

    public DuelBrain(GameEngine engine, Player me, Player rival) {
        this.engine = engine;
        this.me = me;
        this.rival = rival;
    }

    /** Queues this tick's commands. */
    public void act() {
        Unit self = engine.commanderOf(me);
        Unit foe = engine.commanderOf(rival);
        if (self == null || foe == null) return;
        Commander kit = me.commander();
        long tick = engine.getTick();

        int reserve = 0;
        for (Ability ability : kit.abilities()) {
            if (ability.slot() == Ability.Slot.SIGNATURE) reserve = ability.cost();
        }
        int soul = me.getSoul();
        for (Ability ability : kit.abilities()) {
            if (me.cooldownLeft(ability.id(), tick) > 0 || soul < ability.cost()) continue;
            // What the server would refuse, a player doesn't press: silenced, or a dash while rooted.
            if (self.has(Status.SILENCED)
                    || self.has(Status.ROOTED) && ability.effects().stream().anyMatch(e -> e instanceof Effect.Dash)) continue;
            boolean signature = ability.slot() == Ability.Slot.SIGNATURE;
            Command.Cast cast = aim(ability, self, foe, signature);
            if (cast == null) continue;
            engine.submit(cast);
            soul -= ability.cost();
        }

        int left = soul, keep = reserve;
        if (engine.legionSizeOf(me) < me.legionCap()) {
            UnitType best = kit.retinue().stream()
                    .filter(t -> left >= t.summonCost + keep)
                    .max(Comparator.comparingInt(t -> t.summonCost)).orElse(null);
            if (best != null) engine.submit(new Command.Summon(me.getId(), best));
        }

        if (tick - lastOrder >= ORDER_EVERY) {
            lastOrder = tick;
            List<Integer> mine = new ArrayList<>();
            for (Unit unit : engine.getUnits()) {
                if (unit.isAlive() && unit.getOwnerId() == me.getId()) mine.add(unit.getId());
            }
            if (engine.legionSizeOf(me) >= MUSTER || tick >= MUSTER_TICKS) {
                engine.submit(new Command.Move(me.getId(), mine, foe.getPosition(), true));
            }
            Unit close = nearest(enemies(), self.getPosition());
            if (self.getType().range >= 3 && close != null && self.getPosition().distanceTo(close.getPosition()) <= TOO_CLOSE) {
                Position from = close.getPosition(), at = self.getPosition();
                Position away = clamp(new Position(at.x() + 3 * Integer.signum(at.x() - from.x()),
                        at.y() + 3 * Integer.signum(at.y() - from.y())));
                engine.submit(new Command.Move(me.getId(), List.of(self.getId()), away, false));
            }
        }
    }

    /** Where to point the ability now, or null if it isn't worth casting. */
    private Command.Cast aim(Ability ability, Unit self, Unit foe, boolean signature) {
        List<Unit> enemies = enemies();
        Unit nearest = nearest(enemies, self.getPosition());
        if (nearest == null) return null;
        boolean foeLow = foe.getHp() * 2 < foe.getType().maxHp;
        boolean hostile = hurtsEnemies(ability);
        if (heals(ability) && !anyHurt(self, Math.max(ability.range(), ability.radius()))) return null;
        return switch (ability.target()) {
            case ENEMY -> {
                Unit target = nearestInRange(enemies, self, ability.range());
                yield target == null || signature && !foeLow ? null : cast(ability, target.getId(), null);
            }
            case ENEMY_COMMANDER -> self.getPosition().distanceTo(foe.getPosition()) <= ability.range()
                    && (!signature || foeLow || enemies.size() >= 3) ? cast(ability, foe.getId(), null) : null;
            case ALLY -> {
                Unit hurt = mostHurt(self, ability.range(), dashes(ability));
                yield hurt == null ? null : cast(ability, hurt.getId(), null);
            }
            case TILE -> {
                Position at = hostile ? (signature && !foeLow ? crowd(enemies, self, ability) : nearest.getPosition())
                        : self.getPosition();
                if (at == null || self.getPosition().distanceTo(at) > ability.range()) {
                    if (!dashes(ability)) yield null;
                    at = toward(self.getPosition(), foe.getPosition(), ability.range());
                }
                if (!hostile && self.getPosition().distanceTo(nearest.getPosition()) > FIGHTING) yield null;
                yield cast(ability, null, at);
            }
            case SELF, AROUND_SELF -> {
                int reach = ability.target() == Ability.Target.AROUND_SELF && hostile ? ability.radius() : FIGHTING;
                int near = countNear(enemies, self.getPosition(), reach);
                if (near == 0) yield null;
                if (signature && !foeLow && near < 3) yield null;
                yield cast(ability, null, null);
            }
            case CORPSE, SELECTION -> null;
        };
    }

    /** The tile within range where the most enemies stand within the ability's radius. */
    private Position crowd(List<Unit> enemies, Unit self, Ability ability) {
        Position best = null;
        int most = 2;
        for (Unit enemy : enemies) {
            if (self.getPosition().distanceTo(enemy.getPosition()) > ability.range()) continue;
            int count = countNear(enemies, enemy.getPosition(), ability.radius());
            if (count > most) {
                most = count;
                best = enemy.getPosition();
            }
        }
        return best;
    }

    private Position clamp(Position p) {
        int w = engine.getMap().getWidth(), h = engine.getMap().getHeight();
        return new Position(Math.max(0, Math.min(w - 1, p.x())), Math.max(0, Math.min(h - 1, p.y())));
    }

    private static boolean hurtsEnemies(Ability ability) {
        if (ability.affects() == Ability.Affects.ENEMIES) return true;
        for (Effect effect : ability.effects()) {
            if (effect instanceof Effect.Damage || effect instanceof Effect.Dash || effect instanceof Effect.Reveal
                    || effect instanceof Effect.Summon) {
                return true;
            }
        }
        return false;
    }

    private static boolean heals(Ability ability) {
        return ability.effects().stream().anyMatch(e -> e instanceof Effect.Heal);
    }

    /** Whether one of its own units within {@code reach} of the commander is below 70 % health. */
    private boolean anyHurt(Unit self, int reach) {
        for (Unit unit : engine.getUnits()) {
            if (unit.isAlive() && unit.getOwnerId() == me.getId()
                    && self.getPosition().distanceTo(unit.getPosition()) <= reach
                    && unit.getHp() * 10 < unit.getType().maxHp * 7) {
                return true;
            }
        }
        return false;
    }

    private static boolean dashes(Ability ability) {
        return ability.effects().stream().anyMatch(e -> e instanceof Effect.Dash);
    }

    private static Position toward(Position from, Position to, int tiles) {
        int dx = to.x() - from.x(), dy = to.y() - from.y();
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        if (steps <= tiles) return to;
        return new Position(from.x() + dx * tiles / steps, from.y() + dy * tiles / steps);
    }

    private List<Unit> enemies() {
        Visibility sight = engine.sightOf(me);
        List<Unit> out = new ArrayList<>();
        for (Unit unit : engine.getUnits()) {
            if (unit.isAlive() && unit.getOwnerId() != me.getId() && sight.shows(unit)) out.add(unit);
        }
        return out;
    }

    private Unit mostHurt(Unit self, int range, boolean othersOnly) {
        Unit best = null;
        double worst = 0.9;
        for (Unit unit : engine.getUnits()) {
            if (!unit.isAlive() || unit.getOwnerId() != me.getId() || othersOnly && unit == self) continue;
            if (self.getPosition().distanceTo(unit.getPosition()) > range) continue;
            double share = (double) unit.getHp() / unit.getType().maxHp;
            if (share < worst) {
                worst = share;
                best = unit;
            }
        }
        return best;
    }

    private static Unit nearest(List<Unit> units, Position from) {
        return units.stream().min(Comparator.comparingInt(u -> u.getPosition().distanceTo(from))).orElse(null);
    }

    private Unit nearestInRange(List<Unit> enemies, Unit self, int range) {
        Unit nearest = nearest(enemies, self.getPosition());
        if (nearest == null || self.getPosition().distanceTo(nearest.getPosition()) > range) return null;
        boolean sees = self.getPosition().distanceTo(nearest.getPosition()) <= 1
                || engine.getMap().hasLineOfSight(self.getPosition(), nearest.getPosition());
        return sees ? nearest : null;
    }

    private static int countNear(List<Unit> units, Position at, int radius) {
        int count = 0;
        for (Unit unit : units) {
            if (unit.getPosition().distanceTo(at) <= radius) count++;
        }
        return count;
    }

    private Command.Cast cast(Ability ability, Integer target, Position tile) {
        return new Command.Cast(me.getId(), ability.id(), target, tile);
    }
}
