package io.github.dlduarte.gradle;

import io.github.dlduarte.ForgeLogger;
import org.gradle.api.logging.Logger;

/** Adapta o {@link ForgeLogger} do core para o {@link Logger} do Gradle. */
final class GradleForgeLogger implements ForgeLogger {

    private final Logger logger;

    GradleForgeLogger(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void info(String message) {
        logger.lifecycle(message);
    }

    @Override
    public void warn(String message) {
        logger.warn(message);
    }

    @Override
    public void error(String message) {
        logger.error(message);
    }
}
