package dev.xnaq.gdrivebackup.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Small logging facade so backup/drive classes can be unit-tested without
 * loading the Minecraft/NeoForge runtime.
 */
public final class Log {
    private static final Logger LOGGER = LoggerFactory.getLogger("gdrivebackup");

    private Log() {
    }

    public static void debug(String message, Object... args) {
        LOGGER.debug(message, args);
    }

    public static void info(String message, Object... args) {
        LOGGER.info(message, args);
    }

    public static void warn(String message, Object... args) {
        LOGGER.warn(message, args);
    }

    public static void error(String message, Object... args) {
        LOGGER.error(message, args);
    }
}
