package com.undyingdominion.persistence;

import com.undyingdominion.net.GameServer;
import com.undyingdominion.story.StoryDirector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
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

    public List<SaveSummary> list() {
        return dao.list();
    }

    /** The refusal for a name already in use, as a player sees it. The console words it for its own syntax. */
    public static String nameTaken(String name) {
        return "A save called \"" + name + "\" already exists. Save again with force to replace it";
    }
}
