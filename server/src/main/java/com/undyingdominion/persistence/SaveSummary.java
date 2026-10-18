package com.undyingdominion.persistence;

import java.time.Instant;

/** One line of the save list. {@code chapter} counts from 1. */
public record SaveSummary(String name, String campaignTitle, int chapter, Instant savedAt) {
}
