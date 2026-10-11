package com.undyingdominion.game;

/**
 * Tunable numbers that are not tied to a single unit type. Built once before the engine starts,
 * with fluent setters, e.g. {@code GameRules.defaults().maxNeutrals(0)}.
 */
public final class GameRules {

    private int ticksPerSecond = 10;
    private int startingSoul = 80;
    /** Soul energy regained every second while the necromancer lives. */
    private int soulRegenPerSecond = 2;
    /** How far from the necromancer a corpse can be raised. */
    private int raiseRange = 6;
    private int fuseCount = 5;
    private int fuseSoulCost = 40;
    /** How tightly the warriors must stand together to fuse, in tiles from their centre. */
    private int fuseRadius = 3;
    private int corpseDecayTicks = 300;
    private int respawnTicks = 60;
    /** Robot Worlds' shield repair time: a mending unit is rooted this long, then fully healed. */
    private int mendTicks = 30;
    /** Robot Worlds' mine set time. */
    private int trapSetTicks = 30;
    /** Robot Worlds' mine took three hits. */
    private int trapDamage = 30;
    private int maxTrapsPerPlayer = 8;
    private int explodeSoulCost = 10;
    /** A corpse blast hurts enemies for this percentage of the dead creature's life. */
    private int explodeDamagePercent = 40;
    private int explodeRadius = 2;
    private int maxNeutrals = 14;
    private int neutralSpawnIntervalTicks = 40;
    /** Wild creatures never spawn closer than this to a necromancer. */
    private int neutralSpawnClearance = 8;

    public static GameRules defaults() {
        return new GameRules();
    }

    public int ticksPerSecond()            { return ticksPerSecond; }
    public int startingSoul()              { return startingSoul; }
    public int soulRegenPerSecond()        { return soulRegenPerSecond; }
    public int raiseRange()                { return raiseRange; }
    public int fuseCount()                 { return fuseCount; }
    public int fuseSoulCost()              { return fuseSoulCost; }
    public int fuseRadius()                { return fuseRadius; }
    public int corpseDecayTicks()          { return corpseDecayTicks; }
    public int respawnTicks()              { return respawnTicks; }
    public int mendTicks()                 { return mendTicks; }
    public int trapSetTicks()              { return trapSetTicks; }
    public int trapDamage()                { return trapDamage; }
    public int maxTrapsPerPlayer()         { return maxTrapsPerPlayer; }
    public int explodeSoulCost()           { return explodeSoulCost; }
    public int explodeDamagePercent()      { return explodeDamagePercent; }
    public int explodeRadius()             { return explodeRadius; }
    public int maxNeutrals()               { return maxNeutrals; }
    public int neutralSpawnIntervalTicks() { return neutralSpawnIntervalTicks; }
    public int neutralSpawnClearance()     { return neutralSpawnClearance; }

    public GameRules startingSoul(int value)       { startingSoul = value; return this; }
    public GameRules soulRegenPerSecond(int value) { soulRegenPerSecond = value; return this; }
    public GameRules raiseRange(int value)         { raiseRange = value; return this; }
    public GameRules corpseDecayTicks(int value)   { corpseDecayTicks = value; return this; }
    public GameRules respawnTicks(int value)       { respawnTicks = value; return this; }
    public GameRules mendTicks(int value)          { mendTicks = value; return this; }
    public GameRules trapSetTicks(int value)       { trapSetTicks = value; return this; }
    public GameRules maxNeutrals(int value)        { maxNeutrals = value; return this; }
}
