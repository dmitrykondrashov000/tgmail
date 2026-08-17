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

    @Scheduled(fixedDelayString = "${mail.poll-interval-seconds:60}000")
    public void checkMailAndForward() {
        try {
            List<EmailMessage> emails = fetcher.fetchNewEmails();
            for (EmailMessage email : emails) {
                // ВАЖНО: если тут что-то упадёт — markAsSuccessfullySent НЕ вызовется
                sendEmailAndMark(email);
            }
        } catch (Exception e) {
            System.err.println("Ошибка при проверке почты: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void sendEmailAndMark(EmailMessage email) throws Exception {
        String chatId = props.telegramChatId();
        try {
            List<Attachment> atts = email.getAttachments();

            if (atts.isEmpty()) {
                SendMessage message = SendMessage.builder()
                    .chatId(chatId)
                    .text(formatEmail(email))
                    .parseMode("HTML")
                    .build();
                execute(message);
            } else {
                String caption = formatEmail(email);

                Attachment first = atts.get(0);
                sendDocumentWithCaption(chatId, first, caption);

                for (int i = 1; i < atts.size(); i++) {
                    sendDocumentWithCaption(chatId, atts.get(i), null);
                }
            }

            // Если всё отправилось — двигаем UID
            fetcher.markAsSuccessfullySent(email);

        } catch (TelegramApiException e) {
            System.err.println("Не удалось отправить сообщение: " + e.getMessage());
            e.printStackTrace();
            // НИЧЕГО не помечаем обработанным, UID остаётся прежним => письмо попробуем ещё раз позже
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
