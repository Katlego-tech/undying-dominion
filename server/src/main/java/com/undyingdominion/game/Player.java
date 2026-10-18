package com.undyingdominion.game;

import java.util.ArrayList;
import java.util.List;

/** A connected necromancer: their resources, progression and score. */
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

    public Player(int id, String name, String color, int startingSoul) {
        this.id = id;
        this.name = name;
        this.color = color;
        this.soul = startingSoul;
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
