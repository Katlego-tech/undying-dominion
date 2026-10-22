package com.undyingdominion.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Someone playing in this world, as their commander (the necromancer in a campaign): their energy,
 * progression, score and ability cooldowns.
 */
public class Player {

    public static final int MAX_LEVEL = 10;

    private final int id;
    private final String name;
    private final String color;

    private Integer necromancerId;
    private long respawnAtTick = -1;

    private int soul;
    private int xp;
    private int level = 1;
    private int kills;
    private int deaths;

    private final List<String> notices = new ArrayList<>();

    /** Read by network threads to check a cast and to build a rejoin's welcome; written on the loop. */
    private volatile Commander commander;
    /** Who they'll rise as after a fall, or null to rise as themselves. */
    private Commander nextCommander;
    /** The tick each ability is ready again. */
    private final Map<String, Long> readyAt = new HashMap<>();
    /** Energy regained in tenths, for passives that give a fraction each second. */
    private int energyTenths;

    public Player(int id, String name, String color, int startingSoul) {
        this(id, name, color, startingSoul, Roster.builtIn().necromancer());
    }

    public Player(int id, String name, String color, int startingSoul, Commander commander) {
        this.id = id;
        this.name = name;
        this.color = color;
        this.soul = startingSoul;
        this.commander = commander;
    }

    public int legionCap()  { return 8 + 2 * level; }
    public int soulMax()    { return 100 + 25 * (level - 1); }

    /** Total xp needed to reach the level after the current one. */
    public int xpForNextLevel() {
        return 50 * level * (level + 1);
    }

    public void addSoul(int amount) {
        soul = Math.max(0, Math.min(soulMax(), soul + amount));
    }

    public boolean spendSoul(int amount) {
        if (soul < amount) return false;
        soul -= amount;
        return true;
    }

    /** Adds xp and reports whether this crossed into a new level. */
    public boolean addXp(int amount) {
        xp += amount;
        boolean levelled = false;
        while (level < MAX_LEVEL && xp >= xpForNextLevel()) {
            level++;
            levelled = true;
        }
        return levelled;
    }

    /** Carries progress over from an earlier world. */
    public void restoreProgress(int level, int xp, int kills, int deaths) {
        this.level = Math.max(1, Math.min(MAX_LEVEL, level));
        this.xp = xp;
        this.kills = kills;
        this.deaths = deaths;
        this.soul = Math.min(soul, soulMax());
    }

    /** Adds energy in tenths, paying out whole points as they add up. */
    public void addEnergyTenths(int tenths) {
        energyTenths += tenths;
        addSoul(energyTenths / 10);
        energyTenths %= 10;
    }

    public Commander commander()     { return commander; }
    public Commander nextCommander() { return nextCommander; }

    /** Saves a pick for the next rise. */
    public void pick(Commander next) {
        this.nextCommander = next;
    }

    /** Takes up the picked commander, if any, with every ability ready. Called as the player rises. */
    public void rise() {
        if (nextCommander != null) {
            commander = nextCommander;
            nextCommander = null;
        }
        clearCooldowns();
    }

    /** Ticks until the ability can be cast again; 0 when ready. */
    public long cooldownLeft(String abilityId, long tick) {
        return Math.max(0, readyAt.getOrDefault(abilityId, 0L) - tick);
    }

    public void startCooldown(String abilityId, long readyTick) {
        readyAt.put(abilityId, readyTick);
    }

    public void clearCooldown(String abilityId) {
        readyAt.remove(abilityId);
    }

    public void clearCooldowns() {
        readyAt.clear();
    }

    public void notice(String message) {
        notices.add(message);
    }

    /** Hands over every notice since the last call and forgets them. */
    public List<String> takeNotices() {
        List<String> taken = List.copyOf(notices);
        notices.clear();
        return taken;
    }

    public PlayerState capture() {
        return new PlayerState(id, name, color, necromancerId, respawnAtTick, soul, xp, level, kills, deaths);
    }

    /** The level goes back before the soul, because the soul is capped by the level. */
    static Player restore(PlayerState state) {
        Player player = new Player(state.id(), state.name(), state.color(), 0);
        player.necromancerId = state.necromancerId();
        player.respawnAtTick = state.respawnAtTick();
        player.level = state.level();
        player.xp = state.xp();
        player.kills = state.kills();
        player.deaths = state.deaths();
        player.setSoul(state.soul());
        return player;
    }

    public int getId()              { return id; }
    public String getName()         { return name; }
    public String getColor()        { return color; }
    public Integer getNecromancerId() { return necromancerId; }
    public void setNecromancerId(Integer id) { this.necromancerId = id; }
    public long getRespawnAtTick()  { return respawnAtTick; }
    public void setRespawnAtTick(long tick) { this.respawnAtTick = tick; }
    public int getSoul()            { return soul; }
    public void setSoul(int soul)   { this.soul = Math.max(0, Math.min(soulMax(), soul)); }
    public int getXp()              { return xp; }
    public int getLevel()           { return level; }
    public int getKills()           { return kills; }
    public void addKill()           { kills++; }
    public int getDeaths()          { return deaths; }
    public void addDeath()          { deaths++; }
}
