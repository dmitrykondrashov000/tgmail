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
                    result.add(toEmailMessage(msg));
                    processedIds.add(messageId(msg));
                }
            }
        }
        return result;
    }

    private EmailMessage toEmailMessage(Message msg) throws Exception {
        // ТЕМА
        String rawSubject = msg.getSubject();
        String subject = (rawSubject == null) ? "(без темы)" : decode(rawSubject);

        // ОТПРАВИТЕЛЬ
        jakarta.mail.Address[] fromArr = msg.getFrom();
        String from;

        if (fromArr == null || fromArr.length == 0) {
            from = "(неизвестно)";
        } else {
            InternetAddress ia = (InternetAddress) fromArr[0];
            String personal = ia.getPersonal();   // отображаемое имя
            String email = ia.getAddress();       // адрес

            if (personal != null && !personal.isBlank()) {
                // имя тоже может быть в виде =?utf-8?B?...?
                String decodedName = decode(personal);
                from = decodedName + " <" + email + ">";
            } else {
                from = email;
            }
        }

        EmailMessage email = new EmailMessage(subject, from, getBody(msg));
        return email;
    }

    private String getBody(Message msg) throws Exception {
        Object content = msg.getContent();
        if (content instanceof String) {
            return (String) content;
        }
        if (content instanceof Multipart) {
            Multipart mp = (Multipart) content;
            for (int i = 0; i < mp.getCount(); i++) {
                jakarta.mail.BodyPart part = mp.getBodyPart(i);
                String ct = part.getContentType() == null ? "" : part.getContentType();
                if (ct.toLowerCase().startsWith("text/plain")) {
                    Object p = part.getContent();
                    if (p instanceof String) return (String) p;
                }
            }
            for (int i = 0; i < mp.getCount(); i++) {
                jakarta.mail.BodyPart part = mp.getBodyPart(i);
                String ct = part.getContentType() == null ? "" : part.getContentType();
                if (ct.toLowerCase().startsWith("text/html")) {
                    Object p = part.getContent();
                    if (p instanceof String) return (String) p;
                }
            }
        }
        return "(вложение / без текста)";
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
