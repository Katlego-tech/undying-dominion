package com.undyingdominion.persistence;

import com.undyingdominion.net.GameServer;
import com.undyingdominion.story.Campaign;
import com.undyingdominion.story.StoryDirector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

/**
 * Saves a solo campaign game, from a player or the host. The rules are checked and the world captured
 * on the game loop, between ticks; the write goes to a virtual thread so a slow disk never stretches a
 * tick, and the outcome comes back on the loop. See docs/design/persistence.md §4.
 */
public final class SaveService {

    private static final Logger log = LoggerFactory.getLogger(SaveService.class);
    private static final String NAME_RULE = "[A-Za-z0-9_-]{1,32}";

    private final GameServer server;
    private final StoryDirector story;
    private final SaveDao dao;
    /** Of the files this game was loaded from, so a save can tell if they change before it is resumed. */
    private final String fingerprint;

    /** {@code story} is null in a free-for-all, where every save is refused. */
    public SaveService(GameServer server, StoryDirector story, SaveDao dao) {
        this.server = server;
        this.story = story;
        this.dao = dao;
        try {
            this.fingerprint = story == null ? null : CampaignFingerprint.of(story.campaign());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Loop thread. Refuses, or captures the world and writes it; {@code reply} hears the outcome on the loop. */
    public void save(String name, boolean force, Consumer<String> reply) {
        if (story == null) {
            reply.accept("Saving only works in a story campaign");
            return;
        }
        if (server.joinedCount() != 1) {
            reply.accept("Saving only works with one player in the world");
            return;
        }
        if (name == null || !name.matches(NAME_RULE)) {
            reply.accept("Save names are 1 to 32 letters, digits, - or _");
            return;
        }
        if (name.equalsIgnoreCase(AUTOSAVE) || name.toLowerCase().endsWith("-auto")) {
            reply.accept("That name is kept for the autosave. Choose another");
            return;
        }
        write(name, force, reply);
    }

    /** Loop thread, rules already checked: captures the world and writes it off the loop. */
    private void write(String name, boolean force, Consumer<String> reply) {
        SaveGame save = new SaveGame(SaveGame.FORMAT, name, Instant.now(),
                story.campaign().folder().toAbsolutePath().toString(), story.campaign().title(), fingerprint,
                RandomState.of(story.random()), story.capture(), server.engine().capture());
        String saved = "Saved \"" + name + "\": chapter " + story.chapterNumber() + ", " + story.current().title() + ".";

        Thread.ofVirtual().name("save-" + name).start(() -> {
            String outcome;
            try {
                outcome = dao.write(save, force) ? saved : nameTaken(name);
                if (outcome.equals(saved)) log.info("Saved \"{}\"", name);
            } catch (RuntimeException e) {
                log.warn("Could not save \"{}\"", name, e);
                outcome = "The save could not be written: " + e.getMessage();
            }
            String message = outcome;
            server.runOnLoop(() -> reply.accept(message));
        });
    }

    /** The name every autosave is written under, replacing the last. */
    public static final String AUTOSAVE = "autosave";

    /** This game's autosave name: {@link #AUTOSAVE}, or a hub campaign's {@code {player}-auto}. */
    private String autosaveName = AUTOSAVE;

    public SaveService autosaveAs(String name) {
        autosaveName = name;
        return this;
    }

    public String autosaveName() {
        return autosaveName;
    }

    /**
     * Loop thread, as the last player leaves a hub's campaign world: autosaves it whatever the seat
     * count says (the seat is already gone; the player is still in the world until the next tick),
     * then runs {@code done} on the loop.
     */
    public void saveOnLeaving(Runnable done) {
        // Gone before their join reached the world: there's no game of theirs to keep.
        if (story == null || server.engine().getPlayers().isEmpty()) {
            done.run();
            return;
        }
        write(autosaveName, true, message -> {
            log.info("{}", message);
            done.run();
        });
    }

    /**
     * Loop thread. Saves a solo campaign under {@link #AUTOSAVE}, replacing the last one, and tells
     * {@code reply} as {@code Autosaved: chapter N, Title.}. With anything but exactly one player it
     * does nothing: an autosave nobody asked for has no refusal to show. The name is reserved: a
     * player's own save can't take it, so the autosave never replaces one of theirs.
     */
    public void autosave(Consumer<String> reply) {
        if (story == null || server.joinedCount() != 1) return;
        String name = autosaveName;
        write(name, true, message -> reply.accept(message.startsWith("Saved \"" + name + "\"")
                ? "Autosaved" + message.substring(("Saved \"" + name + "\"").length())
                : message));
    }

    /** A save that passed every check, and the director rebuilt from it. */
    public record Resumed(SaveGame save, StoryDirector director) {}

    /**
     * Startup: reads the save and checks it can still be played. Every refusal is an
     * IllegalArgumentException carrying the message the host sees, before the port is opened.
     */
    public static Resumed resume(SaveDao dao, String name) {
        SaveGame save;
        try {
            save = dao.read(name).orElseThrow(() -> new IllegalArgumentException("No save called \"" + name + "\""));
        } catch (SaveFormatException e) {
            throw new IllegalArgumentException("This save was made by an older version of the game and can't be resumed", e);
        }

        Path folder = Path.of(save.campaignFolder());
        if (!Files.isRegularFile(folder.resolve("campaign.json"))) {
            throw new IllegalArgumentException("The campaign for this save is no longer at " + folder);
        }
        Campaign campaign;
        String now;
        try {
            campaign = Campaign.load(folder);
            now = CampaignFingerprint.of(campaign);
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("The campaign has changed since this save was made", e);
        }
        if (!now.equals(save.fingerprint())) {
            throw new IllegalArgumentException("The campaign has changed since this save was made");
        }
        return new Resumed(save, StoryDirector.resume(campaign, RandomState.restore(save.random()), save.story(), save.engine()));
    }

    public List<SaveSummary> list() {
        return dao.list();
    }

    /** The refusal for a name already in use, as a player sees it. The console words it for its own syntax. */
    public static String nameTaken(String name) {
        return "A save called \"" + name + "\" already exists. Save again with force to replace it";
    }
}
