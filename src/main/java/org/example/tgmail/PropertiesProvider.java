package org.example.tgmail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Централизованный доступ к настройкам (spring properties / env).
 */
@Component
public class PropertiesProvider {

    private final String telegramToken;
    private final String telegramUsername;
    private final String telegramChatId;

    private final String imapHost;
    private final int imapPort;
    private final String mailUser;
    private final String mailPassword;

    private final long pollIntervalSeconds;

    public PropertiesProvider(
        @Value("${bot.token}") String telegramToken,
        @Value("${bot.username}") String telegramUsername,
        @Value("${bot.chat-id}") String telegramChatId,
        @Value("${mail.imap.host}") String imapHost,
        @Value("${mail.imap.port}") int imapPort,
        @Value("${mail.user}") String mailUser,
        @Value("${mail.password}") String mailPassword,
        @Value("${mail.poll-interval-seconds}") long pollIntervalSeconds) {
        this.telegramToken = telegramToken;
        this.telegramUsername = telegramUsername;
        this.telegramChatId = telegramChatId;
        this.imapHost = imapHost;
        this.imapPort = imapPort;
        this.mailUser = mailUser;
        this.mailPassword = mailPassword;
        this.pollIntervalSeconds = pollIntervalSeconds;
        System.out.println(">>> PROPS.telegramChatId = [" + telegramChatId + "]");
    }

    public String telegramToken()      { return telegramToken; }
    public String telegramUsername()   { return telegramUsername; }
    public String telegramChatId()     { return telegramChatId; }
    public String imapHost()           { return imapHost; }
    public int imapPort()              { return imapPort; }
    public String mailUser()           { return mailUser; }
    public String mailPassword()       { return mailPassword; }
    public long pollIntervalSeconds()  { return pollIntervalSeconds; }
}
