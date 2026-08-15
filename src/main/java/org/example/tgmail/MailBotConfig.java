package org.example.tgmail;

import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.ByteArrayInputStream;
import java.util.List;

/**
 * Telegram-бот. Раз в N секунд (см. {@code @Scheduled}) проверяет почту
 * и пересылает новые письма в чат.
 */
@Component
public class MailBotConfig extends TelegramLongPollingBot {

    private final PropertiesProvider props;
    private final MailFetcher fetcher;

    public MailBotConfig(PropertiesProvider props, MailFetcher fetcher) {
        this.props = props;
        this.fetcher = fetcher;
    }

    // Spring вызывает этот метод по расписанию
    @Scheduled(fixedDelayString = "${mail.poll-interval-seconds:60}000")
    public void checkMailAndForward() {
        try {
            List<EmailMessage> emails = fetcher.fetchNewEmails();
            for (EmailMessage email : emails) {
                sendEmail(email);
            }
        } catch (Exception e) {
            System.err.println("Ошибка при проверке почты: " + e.getMessage());
        }
    }

    private void sendEmail(EmailMessage email) {
        String chatId = props.telegramChatId();
        try {
            // 1. текст письма
            SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(formatEmail(email))
                .parseMode("HTML")
                .build();
            execute(message);

            // 2. вложения (любые типы как документы)
            for (Attachment att : email.getAttachments()) {
                ByteArrayInputStream bais = new ByteArrayInputStream(att.getData());
                InputFile file = new InputFile(bais, att.getFilename());

                SendDocument doc = SendDocument.builder()
                    .chatId(chatId)
                    .document(file)
                    .caption(att.getFilename())
                    .build();

                execute(doc);
            }

        } catch (TelegramApiException e) {
            System.err.println("Не удалось отправить сообщение: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String formatEmail(EmailMessage email) {
        String from = escape(email.getFrom());
        String subject = escape(email.getSubject());
        String body = escape(email.getBody());

        StringBuilder sb = new StringBuilder();

        // 1. Отправитель
        if (!from.isBlank()) {
            sb.append("<b>От:</b> ").append(from).append("\n");
        } else {
            sb.append("<b>От:</b> (неизвестно)\n");
        }

        // 2. Тема
        if (!subject.isBlank()) {
            sb.append("<b>Тема:</b> ").append(subject).append("\n\n");
        } else {
            sb.append("<b>Тема:</b> (без темы)\n\n");
        }

        // 3. Тело
        if (!body.isBlank()) {
            sb.append(body);
        } else {
            sb.append("(пустое тело письма)");
        }

        return sb.toString();
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }

    @Override
    public String getBotUsername() {
        return props.telegramUsername();
    }

    @Override
    public String getBotToken() {
        return props.telegramToken();
    }

    @Override
    public void onUpdateReceived(Update update) {
        // Пока игнорируем входящие апдейты
    }
}
