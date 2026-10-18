package com.undyingdominion.story;

import java.util.List;

/**
 * A chapter's progress, as saved: which creatures it brought, which triggers have fired (as indices
 * into the chapter's triggers), what it has seen and counted, and whether it is over.
 */
public record ChapterRunState(long startTick, List<Integer> creatures, List<Integer> firedTriggers,
                              List<String> seenTypes, List<String> pendingLines, int kills,
                              boolean bossSeen, ChapterRun.Outcome outcome) {
}
