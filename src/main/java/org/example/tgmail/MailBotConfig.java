package org.example.tgmail;

import org.example.tgmail.PropertiesProvider;
import org.example.tgmail.MailFetcher;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

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
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${mail.poll-interval-seconds:60}000")
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
        try {
            String chatId = props.telegramChatId();
            System.out.println(">>> sending to chatId = " + chatId);

            SendMessage message = SendMessage.builder()
                .chatId(props.telegramChatId())
                .text(formatEmail(email))
                .parseMode("HTML")
                .build();
            execute(message);
            // Точка расширения для вложений — здесь добавим sendPhoto/sendDocument.
        }  catch (TelegramApiException e) {
            System.err.println("Не удалось отправить сообщение: " + e.getMessage());

            // Если это запрос к Telegram API, попробуем вытащить ответ сервера
            if (e instanceof org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException) {
                org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException re =
                    (org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException) e;
                System.err.println("Код ошибки Telegram: " + re.getErrorCode());
                System.err.println("Текст от Telegram: " + re.getApiResponse());
            }

            e.printStackTrace(); // временно, чтобы увидеть всё
        }
    }

    private String formatEmail(EmailMessage email) {
        String subject = escape(email.getSubject());
        String from = escape(email.getFrom());
        String body = escape(email.getBody());
        return "📧 <b>" + subject + "</b>\n👤 " + from + "\n\n" + body;
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
        // Входящие команды не обязательны — по желанию добавим /start позже.
    }
}
