package com.gtnewhorizons.retrofuturagradle.util;

import java.security.MessageDigest;
import java.util.Objects;

/** Consumer.andThen(x) cannot be deserialized by Gradle due to JPMS restrictions */
@FunctionalInterface
public interface MessageDigestConsumer {

    void accept(MessageDigest t);

    default MessageDigestConsumer andThen(MessageDigestConsumer after) {
        Objects.requireNonNull(after);
        return (MessageDigest t) -> {
            accept(t);
            after.accept(t);
        };
    }
}
