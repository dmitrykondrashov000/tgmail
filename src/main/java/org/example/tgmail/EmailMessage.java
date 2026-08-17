package org.example.tgmail;
import java.util.ArrayList;
import java.util.List;

/**
 * Модель одного письма.
 */
public class EmailMessage {
    private final String subject;
    private final String from;
    private final String body;
    private final List<Attachment> attachments = new ArrayList<>();

    // Новый атрибут — IMAP UID
    private long imapUid;

    public EmailMessage(String subject, String from, String body) {
        this.subject = subject;
        this.from = from;
        this.body = body;
    }

    public String getSubject() {
        return subject;
    }

    public String getFrom() {
        return from;
    }

    public String getBody() {
        return body;
    }

    public List<Attachment> getAttachments() {
        return attachments;
    }

    public void addAttachment(Attachment a) {
        attachments.add(a);
    }

    public long getImapUid() {
        return imapUid;
    }

    public void setImapUid(long imapUid) {
        this.imapUid = imapUid;
    }
}