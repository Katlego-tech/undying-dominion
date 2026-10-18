package com.undyingdominion.story;

/**
 * Where a campaign has got to: the chapter being played, a pending move to another chapter
 * ({@code moveOnAtTick} is -1 when none is due), and that chapter's progress.
 */
public record StoryState(int chapterIndex, long moveOnAtTick, int moveOnTo, ChapterRunState run) {
}
