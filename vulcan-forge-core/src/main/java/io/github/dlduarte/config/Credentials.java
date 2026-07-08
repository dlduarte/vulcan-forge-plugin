package io.github.dlduarte.config;

/** Par usuario/senha resolvido a partir do settings.xml (Maven) ou gradle.properties (Gradle). */
public final class Credentials {

    private final String username;
    private final String password;

    public Credentials(String username, String password) {
        this.username = username;
        this.password = password;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public boolean isComplete() {
        return username != null && !username.isBlank()
                && password != null && !password.isBlank();
    }

    @Override
    public String toString() {
        // Nunca exponha a senha em logs.
        return "Credentials{username='" + username + "', password='***'}";
    }
}
