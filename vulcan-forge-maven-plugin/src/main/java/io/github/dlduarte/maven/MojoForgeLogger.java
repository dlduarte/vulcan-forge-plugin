package io.github.dlduarte.maven;

import io.github.dlduarte.ForgeLogger;
import org.apache.maven.plugin.logging.Log;

/** Adapta o {@link ForgeLogger} do core para o {@link Log} do Maven. */
final class MojoForgeLogger implements ForgeLogger {

    private final Log log;

    MojoForgeLogger(Log log) {
        this.log = log;
    }

    @Override
    public void info(String message) {
        log.info(message);
    }

    @Override
    public void warn(String message) {
        log.warn(message);
    }

    @Override
    public void error(String message) {
        log.error(message);
    }
}
