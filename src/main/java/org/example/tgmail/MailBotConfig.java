package org.example.tgmail;

import java.util.ArrayList;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.media.InputMedia;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaDocument;

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

    @Scheduled(fixedDelayString = "${mail.poll-interval-seconds:60}000")
    public void checkMailAndForward() {
        try {
            List<EmailMessage> emails = fetcher.fetchNewEmails();
            for (EmailMessage email : emails) {
                sendEmailAndMark(email);
            }
        } catch (TelegramApiException e) {
            System.err.println("Не удалось отправить сообщение в Telegram: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("Неожиданная ошибка при проверке почты: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void sendEmailAndMark(EmailMessage email) throws Exception {
        String chatId = props.telegramChatId();
        try {
            List<Attachment> atts = email.getAttachments();

            // --- сначала ВСЕГДА шлём одно текстовое сообщение ---
            String text = formatEmailWithBorders(email);

            SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .parseMode("HTML")
                .build();

            execute(message);

            // --- затем, если есть вложения, шлём их ВСЕ подряд без подписи ---
            for (Attachment att : atts) {
                sendDocumentWithCaption(chatId, att, null); // caption = null -> только файл
            }

            // если всё дошло — помечаем письмо обработанным
            fetcher.markAsSuccessfullySent(email);

        } catch (TelegramApiException e) {
            System.err.println("Не удалось отправить сообщение в Telegram: " + e.getMessage());
            e.printStackTrace();
            // UID не трогаем, чтобы потом попробовать ещё раз
            throw e;
        }
    }

    private void sendDocumentWithCaption(String chatId, Attachment att, String caption)
        throws TelegramApiException {

        ByteArrayInputStream bais = new ByteArrayInputStream(att.getData());
        InputFile file = new InputFile(bais, att.getFilename());

        SendDocument.SendDocumentBuilder builder = SendDocument.builder()
            .chatId(chatId)
            .document(file);

        if (caption != null && !caption.isBlank()) {
            builder.caption(caption);
            builder.parseMode("HTML");
        }

        execute(builder.build());
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

    private String formatEmailWithBorders(EmailMessage email) {
        String core = formatEmail(email);

        StringBuilder sb = new StringBuilder();
        sb.append("⬇⬇⬇⬇⬇ ПИСЬМО НАЧАЛО ⬇⬇⬇⬇⬇\n\n");
        sb.append(core).append("\n\n");
        sb.append("⬆⬆⬆⬆⬆ ПИСЬМО КОНЕЦ  ⬆⬆⬆⬆⬆");

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
