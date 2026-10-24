package com.undyingdominion;

import com.undyingdominion.game.Player;
import com.undyingdominion.net.Gateway;
import com.undyingdominion.net.GameServer;
import com.undyingdominion.net.ProtocolException;
import com.undyingdominion.persistence.SaveDao;
import com.undyingdominion.persistence.SaveService;
import com.undyingdominion.persistence.SaveSummary;
import com.undyingdominion.story.Campaign;
import com.undyingdominion.story.StoryDirector;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Every mode at once (docs/design/modes.md): one free-for-all for everyone, and a private campaign
 * world for each player who starts or continues the story, made on demand, autosaved as
 * {@code {player}-auto} when they leave, and closed. The Gateway asks it where each player belongs.
 */
public final class Hub implements Gateway.Router {

    private static final Logger log = LoggerFactory.getLogger(Hub.class);
    /** A campaign world nobody has joined within this long (a refused join, a closed tab) is closed. */
    private static final long UNCLAIMED_SECONDS = 60;

    /** A running campaign world, and the save it continues (null for a new campaign). */
    private record CampaignWorld(GameServer server, StoryDirector story, SaveService saves, String save) {}

    private final GameServer freeForAll;
    private final Campaign campaign;
    private final SaveDao dao;
    private final int maxCampaigns;
    private final Duration grace;
    private final LongSupplier nanoClock;
    private final List<CampaignWorld> campaigns = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService keeper =
            Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().daemon().name("hub-keeper").unstarted(r));

    /** {@code campaign} is null when the server has none to offer. */
    public Hub(GameServer freeForAll, Campaign campaign, SaveDao dao, int maxCampaigns, Duration grace, LongSupplier nanoClock) {
        this.freeForAll = freeForAll;
        this.campaign = campaign;
        this.dao = dao;
        this.maxCampaigns = maxCampaigns;
        this.grace = grace;
        this.nanoClock = nanoClock;
    }

    /** The campaign in {@code folder}, or null (logged) if there's none there or it won't load. */
    public static Campaign campaignIn(Path folder) {
        if (folder == null || !folder.resolve("campaign.json").toFile().isFile()) return null;
        try {
            return Campaign.load(folder);
        } catch (IOException | RuntimeException e) {
            log.warn("No campaign on this server: {} would not load ({})", folder, e.getMessage());
            return null;
        }
    }

    @Override
    public GameServer forJoin(JSONObject join) throws ProtocolException {
        String mode = join.optString("mode", "free-for-all").trim().toLowerCase();
        return switch (mode) {
            case "free-for-all" -> freeForAll;
            case "campaign" -> campaignWorld(join.optString("name", ""), join.has("save") ? join.optString("save") : null);
            case "match" -> throw new ProtocolException("Matches are coming");
            default -> throw new ProtocolException("There is no " + mode + " here");
        };
    }

    /** A new campaign world for this player, or one continuing {@code save}. */
    private synchronized GameServer campaignWorld(String name, String save) throws ProtocolException {
        if (campaign == null) throw new ProtocolException("No campaign on this server");
        if (save != null && campaigns.stream().anyMatch(c -> save.equals(c.save()))) {
            throw new ProtocolException("\"" + save + "\" is being played right now.");
        }
        if (campaigns.size() >= maxCampaigns) throw new ProtocolException("All the campaign worlds are taken. Try again soon.");

        StoryDirector story;
        SaveService.Resumed resumed = null;
        if (save != null) {
            try {
                resumed = SaveService.resume(dao, save);
            } catch (IllegalArgumentException e) {
                throw new ProtocolException(e.getMessage());
            }
            story = resumed.director();
        } else {
            story = new StoryDirector(campaign, new Random());
        }
        GameServer world = new GameServer(story.firstEngine(), 1, grace, nanoClock).mode("campaign");
        if (resumed != null) Dominion.holdSeatFor(world, resumed);
        story.attach(world);
        SaveService saves = new SaveService(world, story, dao).autosaveAs(autosaveName(name));
        world.setSaveHandler((playerId, saveName, force) -> saves.save(saveName, force, message -> {
            Player player = world.engine().getPlayer(playerId);
            if (player != null) player.notice(message);
        }));
        story.onChapterBegins(chapter -> world.runAfterStep(() -> saves.autosave(message ->
                world.engine().getPlayers().forEach(player -> player.notice(message)))));
        CampaignWorld entry = new CampaignWorld(world, story, saves, save);
        // Its one player gone for good: save where they were, then close the world.
        world.addLeaveListener(playerId -> world.runOnLoop(() -> saves.saveOnLeaving(() -> close(entry))));
        world.startLoop();
        campaigns.add(entry);
        keeper.schedule(() -> {
            if (campaigns.contains(entry) && entry.server().joinedCount() == 0) close(entry);
        }, UNCLAIMED_SECONDS, TimeUnit.SECONDS);
        log.info("A campaign world opened for {}{}", name, save == null ? "" : " from \"" + save + "\"");
        return world;
    }

    /** {@code {player}-auto}, in the letters a save name allows. */
    static String autosaveName(String player) {
        String safe = player.trim().replaceAll("[^A-Za-z0-9_-]+", "-").replaceAll("^-+|-+$", "");
        if (safe.isEmpty()) safe = "player";
        return (safe.length() > 27 ? safe.substring(0, 27) : safe) + "-auto";
    }

    /** Off the world's own loop: stopping it from inside would interrupt the thread doing the stopping. */
    private void close(CampaignWorld entry) {
        if (!campaigns.remove(entry)) return;
        keeper.execute(() -> {
            entry.server().stop();
            log.info("A campaign world closed{}", entry.save() == null ? "" : " (\"" + entry.save() + "\")");
        });
    }

    @Override
    public List<GameServer> worlds() {
        List<GameServer> all = new ArrayList<>();
        all.add(freeForAll);
        for (CampaignWorld c : campaigns) all.add(c.server());
        return all;
    }

    @Override
    public GameServer lobby() {
        return freeForAll;
    }

    /** The campaign worlds running now, for the dashboard. */
    public List<GameServer> campaignWorlds() {
        return campaigns.stream().map(CampaignWorld::server).toList();
    }

    @Override
    public JSONObject modes() {
        JSONArray modes = new JSONArray().put(freeForAll.modeEntry());
        if (campaign == null) {
            modes.put(new JSONObject().put("id", "campaign").put("open", false).put("reason", "No campaign on this server"));
        } else {
            JSONArray saves = new JSONArray();
            for (SaveSummary s : dao.list()) {
                if (!s.campaignTitle().equals(campaign.title())) continue;
                int chapter = Math.max(1, Math.min(s.chapter(), campaign.chapters().size()));
                saves.put(new JSONObject().put("name", s.name()).put("chapter", s.chapter())
                        .put("chapterTitle", campaign.chapters().get(chapter - 1).title())
                        .put("savedAt", s.savedAt().toString()));
            }
            modes.put(new JSONObject().put("id", "campaign").put("open", true).put("title", campaign.title())
                    .put("chapters", campaign.chapters().size()).put("playing", campaigns.size()).put("saves", saves));
        }
        modes.put(new JSONObject().put("id", "match").put("open", false).put("reason", "Matches are coming"));
        return new JSONObject().put("modes", modes);
    }

    public void close() {
        for (CampaignWorld c : campaigns) c.server().stop();
        campaigns.clear();
        keeper.shutdownNow();
    }
}
