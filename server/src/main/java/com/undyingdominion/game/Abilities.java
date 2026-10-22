package com.undyingdominion.game;

import com.undyingdominion.world.Position;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Casting: the checks a cast must pass, then each of the ability's effects in order. Kept apart from
 * {@link GameEngine} so the engine stays readable; it runs on the loop thread with the engine's state.
 * A cast that fails a check spends nothing, and the player hears why (docs/design/characters.md §6).
 */
final class Abilities {

    private final GameEngine engine;

    Abilities(GameEngine engine) {
        this.engine = engine;
    }

    void cast(Player player, Command.Cast cast) {
        Ability ability = player.commander().ability(cast.abilityId());
        // The server checks the kit before queueing a cast; a rise as someone else can come between.
        if (ability == null) return;
        String label = ability.label();
        Unit caster = engine.commanderOf(player);
        if (caster == null) {
            player.notice("The fallen cannot use " + label + ".");
            return;
        }
        if (caster.has(Status.SILENCED)) {
            player.notice("You are silenced.");
            return;
        }
        long tick = engine.getTick();
        long left = player.cooldownLeft(ability.id(), tick);
        if (left > 0) {
            int perSecond = engine.getRules().ticksPerSecond();
            player.notice(label + " is ready in " + (left + perSecond - 1) / perSecond + "s.");
            return;
        }
        if (player.getSoul() < ability.cost()) {
            player.notice("Not enough " + player.commander().energy() + " for " + label + ".");
            return;
        }

        Unit target = null;
        Position tile = caster.getPosition();
        switch (ability.target()) {
            case SELF, AROUND_SELF -> { }
            case TILE -> {
                tile = cast.tile();
                if (tile == null || !engine.getMap().inBounds(tile)) return;
                if (!inRange(player, ability, caster, tile)) return;
            }
            case ENEMY, ENEMY_COMMANDER, ALLY -> {
                target = cast.targetId() == null ? null : engine.getUnit(cast.targetId());
                if (target == null || !target.isAlive()) {
                    player.notice("That target is gone.");
                    return;
                }
                if (!fits(ability.target(), caster, target)) {
                    player.notice(label + " needs " + switch (ability.target()) {
                        case ALLY -> "an ally";
                        case ENEMY_COMMANDER -> "an enemy commander";
                        default -> "an enemy";
                    } + ".");
                    return;
                }
                tile = target.getPosition();
                if (!inRange(player, ability, caster, tile)) return;
                boolean inSight = engine.sightOf(player).shows(target)
                        && (caster.getPosition().distanceTo(tile) <= 1
                            || engine.getMap().hasLineOfSight(caster.getPosition(), tile));
                if (!inSight) {
                    player.notice("You can't see that target.");
                    return;
                }
            }
            case CORPSE, SELECTION -> {
                return;  // the necromancer's raise, fuse and explode keep their own commands
            }
        }

        player.spendSoul(ability.cost());
        player.startCooldown(ability.id(), tick + ability.cooldownTicks());
        boolean wasStealthed = caster.has(Status.STEALTHED);
        caster.remove(Status.STEALTHED);  // casting gives the caster away
        engine.event(GameEvent.cast(caster, ability, target, tile));

        Resolution r = new Resolution(player, ability, caster, target, tile, wasStealthed);
        for (Effect effect : ability.effects()) r.resolve(effect);
    }

    private static boolean fits(Ability.Target kind, Unit caster, Unit target) {
        return switch (kind) {
            case ALLY -> target.getOwnerId() == caster.getOwnerId();
            case ENEMY -> caster.isHostileTo(target);
            case ENEMY_COMMANDER -> caster.isHostileTo(target) && target.getType().isCommander();
            default -> false;
        };
    }

    private static boolean inRange(Player player, Ability ability, Unit caster, Position tile) {
        if (caster.getPosition().distanceTo(tile) <= ability.range()) return true;
        player.notice("Too far for " + ability.label() + ". Move within " + ability.range() + " tiles.");
        return false;
    }

    /** One cast's effects, in order, sharing where the area is centred and what the cast placed. */
    private final class Resolution {
        private final Player player;
        private final Ability ability;
        private final Unit caster;
        private final Unit target;
        private final boolean wasStealthed;
        /** Areas centre here: the target tile, or the caster, wherever a dash left it. */
        private Position centre;
        private Unit summoned;

        Resolution(Player player, Ability ability, Unit caster, Unit target, Position tile, boolean wasStealthed) {
            this.player = player;
            this.ability = ability;
            this.caster = caster;
            this.target = target;
            this.wasStealthed = wasStealthed;
            this.centre = ability.target() == Ability.Target.TILE || target != null ? tile : caster.getPosition();
        }

        void resolve(Effect effect) {
            long tick = engine.getTick();
            switch (effect) {
                case Effect.Damage d -> {
                    int amount = wasStealthed ? d.amount() + d.amount() * d.stealthBonus() / 100 : d.amount();
                    for (Unit u : recipients(d)) engine.abilityDamage(caster, u, amount);
                }
                case Effect.Heal h -> recipients(h).forEach(u -> u.heal(h.amount()));
                case Effect.Shield s -> recipients(s).forEach(u -> u.shield(s.amount(), tick + s.ticks()));
                case Effect.Apply a -> {
                    for (Unit u : recipients(a)) {
                        if (a.afterTicks() > 0) {
                            engine.schedule(u, a.status(), a.magnitude(), a.ticks(), tick + a.afterTicks(), caster);
                        } else {
                            engine.applyStatus(u, a.status(), a.magnitude(), tick + a.ticks(), caster);
                        }
                    }
                }
                case Effect.Taunt t -> {
                    Unit taunter = t.towardSummoned() ? summoned : caster;
                    if (taunter == null) return;
                    for (Unit u : recipients(t)) engine.applyStatus(u, Status.TAUNTED, 0, tick + t.ticks(), taunter);
                }
                case Effect.Dash d -> dash(d);
                case Effect.Push p -> recipients(p).forEach(u -> push(u, p.tiles()));
                case Effect.Summon s -> summon(s);
                case Effect.Cleanse c -> recipients(c).forEach(Unit::cleanse);
                case Effect.ResetCooldowns ignored -> {
                    for (Ability other : player.commander().abilities()) {
                        if (!other.id().equals(ability.id())) player.clearCooldown(other.id());
                    }
                }
                case Effect.Reveal r -> engine.addReveal(new Visibility.Reveal(player.getId(), centre, ability.radius(),
                        tick + r.ticks()));
                case Effect.Native ignored -> { }
            }
        }

        private List<Unit> recipients(Effect effect) {
            Ability.Affects affects = effect.affects() != null ? effect.affects() : ability.affects();
            List<Unit> out = new ArrayList<>();
            switch (affects) {
                case CASTER -> out.add(caster);
                case TARGET -> {
                    if (target != null) out.add(target);
                    else if (ability.target() == Ability.Target.SELF || ability.target() == Ability.Target.AROUND_SELF) out.add(caster);
                }
                case ENEMIES, OWN -> {
                    for (Unit u : engine.getUnits()) {
                        if (!u.isAlive() || u.getPosition().distanceTo(centre) > ability.radius()) continue;
                        if ((affects == Ability.Affects.OWN) == (u.getOwnerId() == caster.getOwnerId())) out.add(u);
                    }
                }
            }
            out.removeIf(u -> !u.isAlive());
            return out;
        }

        /**
         * Along the path, at most {@code tiles} steps, in one tick, hitting each enemy passed beside
         * once. Over terrain it flies straight and lands on the nearest free tile.
         */
        private void dash(Effect.Dash d) {
            Position from = caster.getPosition();
            Position goal = target != null ? target.getPosition() : centre;
            Position landing;
            List<Position> passed = new ArrayList<>();
            if (d.overTerrain()) {
                Position end = from;
                for (Position p : line(from, goal)) {
                    if (passed.size() >= d.tiles()) break;
                    passed.add(p);
                    end = p;
                }
                landing = end.equals(from) ? null : engine.freeTileNear(end, 2);
            } else {
                landing = from;
                for (Position step : engine.pathFor(caster, goal)) {
                    if (passed.size() >= d.tiles() || !engine.isFree(step)) break;
                    passed.add(step);
                    landing = step;
                }
                if (landing.equals(from) && from.distanceTo(goal) > 1) landing = null;
            }
            if (landing == null) {
                player.notice("No room to land.");
                return;
            }
            if (!landing.equals(from)) engine.place(caster, landing);
            caster.setPost(landing);  // an idle commander holds where it landed, not where it started
            centre = caster.getPosition();
            if (d.damage() <= 0) return;
            Set<Unit> hit = new HashSet<>();
            for (Position p : passed) {
                for (Unit u : engine.getUnits()) {
                    if (u.isAlive() && caster.isHostileTo(u) && u.getPosition().distanceTo(p) <= 1) hit.add(u);
                }
            }
            for (Unit u : hit) engine.abilityDamage(caster, u, d.damage());
        }

        /** Straight away from the caster, stopping at anything in the way. */
        private void push(Unit unit, int tiles) {
            if (unit.getType().stationary || unit == caster) return;
            int dx = Integer.signum(unit.getPosition().x() - caster.getPosition().x());
            int dy = Integer.signum(unit.getPosition().y() - caster.getPosition().y());
            if (dx == 0 && dy == 0) return;
            Position to = unit.getPosition();
            for (int i = 0; i < tiles; i++) {
                Position next = to.offset(dx, dy);
                if (!engine.isFree(next)) break;
                to = next;
            }
            if (!to.equals(unit.getPosition())) engine.place(unit, to);
        }

        private void summon(Effect.Summon s) {
            if (caster.has(Status.SEALED)) {
                player.notice("You are sealed and cannot " + player.commander().verb().toLowerCase() + ".");
                return;
            }
            int placed = 0;
            for (int i = 0; i < s.count(); i++) {
                if (engine.legionSizeOf(player) >= player.legionCap()) break;
                Position spot = engine.freeTileNear(centre, 3);
                if (spot == null) break;
                summoned = engine.summonFree(s.unit(), player, spot, s.ticks());
                placed++;
            }
            int lost = s.count() - placed;
            if (lost > 0) player.notice("Your legion is full: " + lost + " " + s.unit().label + " could not answer.");
        }
    }

    /** The tiles from {@code a} to {@code b}, excluding {@code a}, one step at a time. */
    static List<Position> line(Position a, Position b) {
        List<Position> out = new ArrayList<>();
        int dx = Math.abs(b.x() - a.x()), dy = -Math.abs(b.y() - a.y());
        int sx = a.x() < b.x() ? 1 : -1, sy = a.y() < b.y() ? 1 : -1;
        int err = dx + dy, x = a.x(), y = a.y();
        while (x != b.x() || y != b.y()) {
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x += sx; }
            if (e2 <= dx) { err += dx; y += sy; }
            out.add(new Position(x, y));
        }
        return out;
    }
}
