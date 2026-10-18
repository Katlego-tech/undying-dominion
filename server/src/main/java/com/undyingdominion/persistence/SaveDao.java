package com.undyingdominion.persistence;

import java.util.List;
import java.util.Optional;

/** Where saved games are kept. No SQL, files or JSON leak past this interface into the game. */
public interface SaveDao {

    /** False if the name is taken and {@code replace} is false. Throws {@link SaveStoreException} on I/O failure. */
    boolean write(SaveGame save, boolean replace);

    /** Empty if absent. Throws {@link SaveFormatException} if the stored format isn't {@link SaveGame#FORMAT}. */
    Optional<SaveGame> read(String name);

    /** Newest first. */
    List<SaveSummary> list();
}
