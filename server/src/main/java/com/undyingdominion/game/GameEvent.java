package com.undyingdominion.game;

import com.undyingdominion.world.Position;

/**
 * Something that happened during a tick, sent to clients so they can draw it.
 * Positions are recorded at the moment it happened, so an event can be drawn even if the unit
 * involved has since died. Field meaning depends on the kind; unused fields are zero.
 */
public record GameEvent(Kind kind, int a, int b, int x, int y, int x2, int y2, String ability, int radius) {

    public GameEvent(Kind kind, int a, int b, int x, int y, int x2, int y2) {
        this(kind, a, b, x, y, x2, y2, null, 0);
    }

    public enum Kind {
        /** a = attacker id, b = target id, from x,y to x2,y2 */
        ATTACK,
        /** a = dead unit id, at x,y */
        DEATH,
        /** a = new unit id, at x,y */
        SUMMON,
        /** a = raised unit id, at x,y */
        RAISE,
        /** a = Skeleton King id, at x,y */
        FUSE,
        /** a = unit that finished mending, at x,y */
        MEND,
        /** a = warden id, trap laid at x,y */
        TRAP_SET,
        /** a = unit that stepped on it, at x,y */
        TRAP_SPRUNG,
        /** a = player id, b = new level */
        LEVEL_UP,
        /** a = player id, b = blast radius, at x,y */
        EXPLOSION,
        /** a = caster id, b = target unit id (0 for a tile or self), caster at x,y, target tile x2,y2; ability, radius */
        CAST
    }

    public static GameEvent attack(Unit attacker, Unit target) {
        Position from = attacker.getPosition(), to = target.getPosition();
        return new GameEvent(Kind.ATTACK, attacker.getId(), target.getId(), from.x(), from.y(), to.x(), to.y());
    }

    public static GameEvent at(Kind kind, Unit unit) {
        return at(kind, unit, unit.getPosition());
    }

    public static GameEvent at(Kind kind, Unit unit, Position where) {
        return new GameEvent(kind, unit.getId(), 0, where.x(), where.y(), 0, 0);
    }

    public static GameEvent cast(Unit caster, Ability ability, Unit target, Position tile) {
        Position from = caster.getPosition();
        return new GameEvent(Kind.CAST, caster.getId(), target == null ? 0 : target.getId(), from.x(), from.y(),
                tile.x(), tile.y(), ability.id(), ability.radius());
    }

    public static GameEvent levelUp(Player player) {
        return new GameEvent(Kind.LEVEL_UP, player.getId(), player.getLevel(), 0, 0, 0, 0);
    }
}
