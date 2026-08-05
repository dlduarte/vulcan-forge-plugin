package io.github.dlduarte.config;

/**
 * Build tool que esta executando o Vulcan Forge. Usado para falar com o dev na
 * linguagem do projeto dele (goal/pom.xml vs. task/build.gradle) nas mensagens de erro.
 */
public enum BuildTool {

    MAVEN,
    GRADLE
}
