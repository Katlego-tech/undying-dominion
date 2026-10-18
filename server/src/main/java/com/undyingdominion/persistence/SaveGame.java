package com.undyingdominion.persistence;

import com.undyingdominion.game.EngineState;
import com.undyingdominion.story.StoryState;

import java.time.Instant;

/**
 * A whole solo campaign game, saved under a name: which campaign it belongs to (and a fingerprint of
 * its files, so a changed campaign is noticed), the random generator's exact state, the story's
 * progress and the world.
 *
 * @param random the serialised {@link java.util.Random}, see {@link RandomState}
 */
public record SaveGame(int format, String name, Instant savedAt, String campaignFolder, String campaignTitle,
                       String fingerprint, String random, StoryState story, EngineState engine) {

    /** Bumped whenever the saved shape changes. Saves of any other format are refused. */
    public static final int FORMAT = 1;
}
