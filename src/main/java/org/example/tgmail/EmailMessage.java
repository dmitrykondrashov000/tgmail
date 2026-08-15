package org.example.tgmail;

/**
 * Модель одного письма.
 */
public class EmailMessage {
    private final String subject;
    private final String from;
    private final String body;
    private final java.util.List<Attachment> attachments = new java.util.ArrayList<>();

    public EmailMessage(String subject, String from, String body) {
        this.subject = subject;
        this.from = from;
        this.body = body;
    }

    public String getSubject() { return subject; }
    public String getFrom()    { return from; }
    public String getBody()    { return body; }
    public java.util.List<Attachment> getAttachments() { return attachments; }

    public void addAttachment(Attachment attachment) {
        attachments.add(attachment);
    }
}
