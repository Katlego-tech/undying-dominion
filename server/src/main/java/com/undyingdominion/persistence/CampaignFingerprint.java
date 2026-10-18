package com.undyingdominion.persistence;

import com.undyingdominion.story.Campaign;

import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Identifies a campaign's files: the SHA-256 of {@code campaign.json} followed by each chapter's map, in
 * chapter order. A save made against different files would rebuild onto creatures and maps that no
 * longer match, so resuming checks it.
 */
public final class CampaignFingerprint {

    private CampaignFingerprint() {
    }

    public static String of(Campaign campaign) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JDK provides SHA-256", e);
        }
        digest.update(Files.readAllBytes(campaign.folder().resolve("campaign.json")));
        for (Campaign.Chapter chapter : campaign.chapters()) {
            digest.update(Files.readAllBytes(campaign.folder().resolve(chapter.mapFile())));
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
