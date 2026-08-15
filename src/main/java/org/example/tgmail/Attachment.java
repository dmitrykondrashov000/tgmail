package org.example.tgmail;

/**
 * Модель вложения в письме.
 */
public class Attachment {
    private final String filename;
    private final String mimeType;
    private final byte[] data;

    public Attachment(String filename, String mimeType, byte[] data) {
        this.filename = filename;
        this.mimeType = mimeType;
        this.data = data;
    }

    public String getFilename() { return filename; }
    public String getMimeType() { return mimeType; }
    public byte[] getData()     { return data; }

    public boolean isImage() {
        return mimeType != null && mimeType.toLowerCase().startsWith("image/");
    }
}
