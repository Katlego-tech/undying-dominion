package com.undyingdominion.persistence;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.Random;

/**
 * A {@link Random}'s exact state as text. Random is Serializable and the JDK specifies its serial form,
 * so a restored generator carries on with the very numbers the original would have drawn.
 */
public final class RandomState {

    private RandomState() {
    }

    public static String of(Random random) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(random);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    public static Random restore(String base64) {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(base64)))) {
            if (in.readObject() instanceof Random random) return random;
            throw new SaveFormatException("The saved random state is not a java.util.Random");
        } catch (IOException | ClassNotFoundException | IllegalArgumentException e) {
            throw new SaveFormatException("The saved random state can't be read", e);
        }
    }
}
