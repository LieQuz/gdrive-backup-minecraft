package dev.xnaq.gdrivebackup.drive;

public interface AccessTokenProvider {
    String accessToken();

    void invalidateAccessToken();
}
