package org.example.tgmail;

import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPStore;
import jakarta.annotation.PostConstruct;
import jakarta.mail.internet.InternetAddress;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
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
@Component
public class MailFetcher {

    private final PropertiesProvider props;

    // Файл, где храним последний обработанный UID
    private final File uidStateFile;

    // Последний обработанный UID (в памяти)
    private volatile long lastProcessedUid = 0L;

    public MailFetcher(PropertiesProvider props) {
        this.props = props;
        this.uidStateFile = new File("last-processed-uid.state");
    }

    @PostConstruct
    public void init() {
        this.lastProcessedUid = initFromMailboxMaxUid();
        System.out.println("[MailFetcher] lastProcessedUid (from mailbox) = " + lastProcessedUid);
    }

    /**
     * Возвращает новые письма с UID > lastProcessedUid.
     * ВАЖНО: здесь lastProcessedUid НЕ обновляется.
     * Обновление — только через markAsSuccessfullySent() после Телеги.
     */
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

        try (IMAPStore store = (IMAPStore) session.getStore("imaps")) {
            store.connect(props.imapHost(), props.imapPort(), props.mailUser(), props.mailPassword());

            try (IMAPFolder inbox = (IMAPFolder) store.getFolder("INBOX")) {
                inbox.open(Folder.READ_WRITE);

                Message[] messages = inbox.getMessages();
                if (messages.length == 0) {
                    return result;
                }

                // сортируем по UID от старых к новым
                List<Message> sorted = Arrays.asList(messages);
                sorted.sort(Comparator.comparingLong(m -> {
                    try {
                        return inbox.getUID(m);
                    } catch (MessagingException e) {
                        return Long.MAX_VALUE;
                    }
                }));

                for (Message msg : sorted) {
                    long uid = inbox.getUID(msg);
                    if (uid <= lastProcessedUid) {
                        continue; // это уже обработано ранее
                    }

                    EmailMessage email = toEmailMessage(msg);
                    email.setImapUid(uid);
                    result.add(email);

                    // Не трогаем SEEN, не двигаем lastProcessedUid
                    // Если очень хочешь, можешь помечать прочитанным:
                    // msg.setFlag(Flags.Flag.SEEN, true);
                }
            }
        }
        return result;
    }

    /**
     * Вызывается из бота, когда письмо уже точно ушло в Telegram.
     * Тут двигаем lastProcessedUid и сохраняем в файл.
     */
    public synchronized void markAsSuccessfullySent(EmailMessage email) {
        long uid = email.getImapUid();
        if (uid <= 0) return;

        if (uid > lastProcessedUid) {
            lastProcessedUid = uid;
            saveLastProcessedUid(lastProcessedUid);
            System.out.println("[MailFetcher] updated lastProcessedUid = " + lastProcessedUid);
        }
    }

    private long initFromMailboxMaxUid() {
        long maxUid = 0L;
        try {
            Properties mailProps = new Properties();
            mailProps.put("mail.store.protocol", "imaps");
            mailProps.put("mail.imaps.host", props.imapHost());
            mailProps.put("mail.imaps.port", String.valueOf(props.imapPort()));
            mailProps.put("mail.imaps.ssl.enable", "true");
            mailProps.put("mail.imaps.ssl.trust", "*");
            mailProps.put("mail.imaps.connectiontimeout", "10000");
            mailProps.put("mail.imaps.timeout", "10000");

            Session session = Session.getInstance(mailProps);

            try (IMAPStore store = (IMAPStore) session.getStore("imaps")) {
                store.connect(props.imapHost(), props.imapPort(), props.mailUser(), props.mailPassword());

                try (IMAPFolder inbox = (IMAPFolder) store.getFolder("INBOX")) {
                    inbox.open(Folder.READ_ONLY);

                    Message[] messages = inbox.getMessages();
                    if (messages.length > 0) {
                        Message last = messages[messages.length - 1];
                        maxUid = inbox.getUID(last);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Не удалось инициализировать UID по ящику: " + e.getMessage());
        }

        // не обязательно, но можно сохранять для отладки
        saveLastProcessedUid(maxUid);
        return maxUid;
    }

    private void saveLastProcessedUid(long uid) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(uidStateFile, false))) {
            bw.write(Long.toString(uid));
        } catch (Exception e) {
            System.err.println("Не удалось сохранить lastProcessedUid: " + e.getMessage());
        }
    }

    // ===== дальше твой парсинг писем (минимально тронут) =====

    private String stripHtml(String html) {
        if (html == null) return "";

        html = html.replaceAll("(?is)<head.*?</head>", "");
        html = html.replaceAll("(?is)<style.*?</style>", "");
        html = html.replaceAll("(?is)<script.*?</script>", "");

        html = html.replaceAll("(?i)<br\\s*/?>", "\n");
        html = html.replaceAll("(?i)</p>", "\n");

        html = html.replaceAll("(?s)<[^>]+>", "");

        html = html.replace("&nbsp;", " ");
        html = html.replace("&amp;", "&");
        html = html.replace("&lt;", "<");
        html = html.replace("&gt;", ">");

        return html.trim();
    }

    private String cleanupTildaBody(String text) {
        if (text == null) return "";

        String normalized = text.replace("\r\n", "\n");

        int idxNotif = normalized.indexOf("This email is a notification");
        if (idxNotif >= 0) {
            normalized = normalized.substring(0, idxNotif);
        }

        int idxReq = normalized.indexOf("Request details:");
        if (idxReq >= 0) {
            int idxAddInfo = normalized.indexOf("Additional information:", idxReq);
            if (idxAddInfo > idxReq) {
                normalized = normalized.substring(idxReq, idxAddInfo).trim();
            } else {
                normalized = normalized.substring(idxReq).trim();
            }
        }

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
        String rawSubject = msg.getSubject();
        String subject = (rawSubject == null) ? "(без темы)" : decode(rawSubject);

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

        String body = getBody(msg);

        Address[] fromArrForBody = msg.getFrom();
        if (fromArrForBody != null && fromArrForBody.length > 0) {
            InternetAddress iaFrom = (InternetAddress) fromArrForBody[0];
            String emailAddr = iaFrom.getAddress();
            if (emailAddr != null && emailAddr.toLowerCase().contains("tilda.ws")) {
                body = cleanupTildaBody(body);
            }
        }

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
            String s = (String) content;
            String ct = msg.getContentType() == null ? "" : msg.getContentType().toLowerCase();
            if (ct.startsWith("text/html")) {
                return stripHtml(s);
            }
            return s;
        }

        if (content instanceof Multipart) {
            Multipart mp = (Multipart) content;

            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart part = mp.getBodyPart(i);
                String ct = part.getContentType() == null ? "" : part.getContentType().toLowerCase();
                if (ct.startsWith("text/plain")) {
                    Object p = part.getContent();
                    if (p instanceof String) return (String) p;
                }
            }

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

    private String decode(String s) {
        try {
            return MimeUtility.decodeText(s);
        } catch (Exception e) {
            return s;
        }
    }
}
