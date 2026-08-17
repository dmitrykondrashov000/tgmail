package org.example.tgmail;

import jakarta.mail.internet.InternetAddress;
import org.example.tgmail.PropertiesProvider;
import org.springframework.stereotype.Component;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Flags;
import jakarta.mail.Multipart;
import jakarta.mail.internet.MimeUtility;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import jakarta.mail.*;
import jakarta.mail.Flags;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeUtility;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.*;

/**
 * Читает новые (непрочитанные) письма из ящика по IMAP.
 * Оформлен как Spring-компонент, свойства берёт из {@link PropertiesProvider}.
 */
@Component
public class MailFetcher {

    private final PropertiesProvider props;

    /** Защита от дублей по Message-ID. */
    private final Set<String> processedIds = new HashSet<>();

    public MailFetcher(PropertiesProvider props) {
        this.props = props;
    }

    public List<EmailMessage> fetchNewEmails() throws Exception {
        Properties mailProps = new Properties();
        mailProps.put("mail.store.protocol", "imaps");
        mailProps.put("mail.imaps.host", props.imapHost());
        mailProps.put("mail.imaps.port", String.valueOf(props.imapPort()));
        mailProps.put("mail.imaps.ssl.enable", "true");
        mailProps.put("mail.imaps.ssl.trust", "*");
        mailProps.put("mail.imaps.connectiontimeout", "10000");
        mailProps.put("mail.imaps.timeout", "10000");

        Session session = Session.getInstance(mailProps);
        List<EmailMessage> result = new ArrayList<>();

        try (Store store = session.getStore("imaps")) {
            store.connect(props.imapHost(), props.imapPort(), props.mailUser(), props.mailPassword());

            try (Folder inbox = store.getFolder("INBOX")) {
                inbox.open(Folder.READ_WRITE);

                Message[] messages = inbox.getMessages();
                for (Message msg : messages) {
                    String id = messageId(msg);

                    // уже отправляли это письмо в телегу — пропускаем
                    if (processedIds.contains(id)) {
                        continue;
                    }

                    EmailMessage email = toEmailMessage(msg);
                    result.add(email);

                    // помечаем как обработанное в нашей памяти
                    processedIds.add(id);

                    // опционально: пометить письмо прочитанным на сервере
                    msg.setFlag(Flags.Flag.SEEN, true);
                }
            }
        }
        return result;
    }




    private String stripHtml(String html) {
        if (html == null) return "";

        // убираем head
        html = html.replaceAll("(?is)<head.*?</head>", "");
        // стили/скрипты
        html = html.replaceAll("(?is)<style.*?</style>", "");
        html = html.replaceAll("(?is)<script.*?</script>", "");

        // заменяем переносы строк
        html = html.replaceAll("(?i)<br\\s*/?>", "\n");
        html = html.replaceAll("(?i)</p>", "\n");

        // сносим все остальные теги
        html = html.replaceAll("(?s)<[^>]+>", "");

        // популярные HTML‑сущности
        html = html.replace("&nbsp;", " ");
        html = html.replace("&amp;", "&");
        html = html.replace("&lt;", "<");
        html = html.replace("&gt;", ">");

        return html.trim();
    }

    private String cleanupTildaBody(String text) {
        if (text == null) return "";

        // Нормализуем переводы строк
        String normalized = text.replace("\r\n", "\n");

        // Режем всё от фразы "This email is a notification" и ниже
        int idxNotif = normalized.indexOf("This email is a notification");
        if (idxNotif >= 0) {
            normalized = normalized.substring(0, idxNotif);
        }

        // Если есть блок "Request details:" — вытащим его отдельно
        int idxReq = normalized.indexOf("Request details:");
        if (idxReq >= 0) {
            int idxAddInfo = normalized.indexOf("Additional information:", idxReq);
            if (idxAddInfo > idxReq) {
                normalized = normalized.substring(idxReq, idxAddInfo).trim();
            } else {
                normalized = normalized.substring(idxReq).trim();
            }
        }

        // Убираем лишние пустые строки подряд
        String[] lines = normalized.split("\n");
        StringBuilder sb = new StringBuilder();
        boolean lastEmpty = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                if (!lastEmpty) {
                    sb.append("\n");
                    lastEmpty = true;
                }
            } else {
                sb.append(trimmed).append("\n");
                lastEmpty = false;
            }
        }

        return sb.toString().trim();
    }

    private EmailMessage toEmailMessage(Message msg) throws Exception {
        // ===== ТЕМА =====
        String rawSubject = msg.getSubject();
        String subject = (rawSubject == null) ? "(без темы)" : decode(rawSubject);

        // ===== ОТПРАВИТЕЛЬ =====
        Address[] fromArr = msg.getFrom();
        String from;
        if (fromArr == null || fromArr.length == 0) {
            from = "(неизвестно)";
        } else {
            InternetAddress ia = (InternetAddress) fromArr[0];
            String personal = ia.getPersonal();
            String email = ia.getAddress();
            if (personal != null && !personal.isBlank()) {
                from = decode(personal) + " <" + email + ">";
            } else {
                from = email;
            }
        }

        // ===== ТЕЛО =====
        String body = getBody(msg);

        // если это письмо от Tilda — подчистим мусор
        Address[] fromArrForBody = msg.getFrom();
        if (fromArrForBody != null && fromArrForBody.length > 0) {
            InternetAddress iaFrom = (InternetAddress) fromArrForBody[0];
            String emailAddr = iaFrom.getAddress();
            if (emailAddr != null && emailAddr.toLowerCase().contains("tilda.ws")) {
                body = cleanupTildaBody(body);
            }
        }


        // ===== ВЛОЖЕНИЯ =====
        List<Attachment> attachments = getAttachments(msg);

        EmailMessage emailMessage = new EmailMessage(subject, from, body);
        for (Attachment a : attachments) {
            emailMessage.addAttachment(a);
        }
        return emailMessage;
    }

    private String getBody(Message msg) throws Exception {
        Object content = msg.getContent();

        // Если это просто строка
        if (content instanceof String) {
            String s = (String) content;
            String ct = msg.getContentType() == null ? "" : msg.getContentType().toLowerCase();
            if (ct.startsWith("text/html")) {
                return stripHtml(s);
            }
            return s;
        }

        if (content instanceof Multipart) {
            Multipart mp = (Multipart) content;

            // Сначала ищем text/plain
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart part = mp.getBodyPart(i);
                String ct = part.getContentType() == null ? "" : part.getContentType().toLowerCase();
                if (ct.startsWith("text/plain")) {
                    Object p = part.getContent();
                    if (p instanceof String) return (String) p;
                }
            }

            // Потом text/html, но уже через stripHtml
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart part = mp.getBodyPart(i);
                String ct = part.getContentType() == null ? "" : part.getContentType().toLowerCase();
                if (ct.startsWith("text/html")) {
                    Object p = part.getContent();
                    if (p instanceof String) return stripHtml((String) p);
                }
            }
        }

        return "(вложение / без текста)";
    }



    private List<Attachment> getAttachments(Message msg) throws Exception {
        List<Attachment> list = new ArrayList<>();

        Object content = msg.getContent();
        if (!(content instanceof Multipart)) {
            return list;
        }

        Multipart mp = (Multipart) content;
        for (int i = 0; i < mp.getCount(); i++) {
            BodyPart part = mp.getBodyPart(i);

            String disp = part.getDisposition();
            String ct = part.getContentType() == null ? "" : part.getContentType().toLowerCase();

            boolean isAttachment =
                Part.ATTACHMENT.equalsIgnoreCase(disp) ||
                    (Part.INLINE.equalsIgnoreCase(disp) && !ct.startsWith("text/"));

            if (!isAttachment) {
                continue;
            }

            String filename = part.getFileName();
            if (filename != null) {
                filename = decode(filename);
            } else {
                filename = "attachment-" + i;
            }

            String mimeType = part.getContentType();
            byte[] data = readAllBytes(part.getInputStream());

            list.add(new Attachment(filename, mimeType, data));
        }

        return list;
    }

    private byte[] readAllBytes(InputStream in) throws Exception {
        try (in; ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) {
                baos.write(buf, 0, r);
            }
            return baos.toByteArray();
        }
    }

    private String messageId(Message msg) {
        try {
            String[] id = msg.getHeader("Message-ID");
            if (id != null && id.length > 0) return id[0];
        } catch (Exception ignore) {
            // fallback ниже
        }
        try {
            return msg.getSubject() + "|" + msg.getSentDate();
        } catch (Exception e) {
            return msg.toString();
        }
    }

    private String decode(String s) {
        try {
            return MimeUtility.decodeText(s);
        } catch (Exception e) {
            return s;
        }
    }
}