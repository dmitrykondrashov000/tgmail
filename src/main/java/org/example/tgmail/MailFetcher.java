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

                for (Message msg : inbox.getMessages()) {
                    if (msg.isSet(Flags.Flag.SEEN) || processedIds.contains(messageId(msg))) {
                        continue;
                    }
                    EmailMessage email = toEmailMessage(msg);
                    result.add(email);
                    processedIds.add(messageId(msg));
                }
            }
        }
        return result;
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
        if (content instanceof String) {
            return (String) content;
        }
        if (content instanceof Multipart) {
            Multipart mp = (Multipart) content;

            // Сперва text/plain
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart part = mp.getBodyPart(i);
                String ct = part.getContentType() == null ? "" : part.getContentType();
                if (ct.toLowerCase().startsWith("text/plain")) {
                    Object p = part.getContent();
                    if (p instanceof String) return (String) p;
                }
            }
            // Потом text/html
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart part = mp.getBodyPart(i);
                String ct = part.getContentType() == null ? "" : part.getContentType();
                if (ct.toLowerCase().startsWith("text/html")) {
                    Object p = part.getContent();
                    if (p instanceof String) return (String) p;
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