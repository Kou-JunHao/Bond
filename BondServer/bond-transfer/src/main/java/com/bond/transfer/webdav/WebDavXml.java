package com.bond.transfer.webdav;

import java.io.StringWriter;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * WebDAV XML（PROPFIND 207 multistatus / LOCK lockdiscovery）生成。
 *
 * <p>用 JDK 自带 StAX，不引入额外 XML 库；所有文本由 writer 转义。</p>
 */
public final class WebDavXml {

    private static final String DAV_NS = "DAV:";
    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    private WebDavXml() {
    }

    /** 生成 {@code 207 Multi-Status} 响应体。 */
    public static String multiStatus(List<WebDavEntry> entries) {
        try {
            StringWriter out = new StringWriter();
            XMLStreamWriter writer = XMLOutputFactory.newFactory().createXMLStreamWriter(out);
            writer.writeStartDocument("UTF-8", "1.0");
            writer.writeStartElement("D", "multistatus", DAV_NS);
            writer.writeNamespace("D", DAV_NS);

            for (WebDavEntry entry : entries) {
                writer.writeStartElement("D", "response", DAV_NS);
                element(writer, "href", entry.href());

                writer.writeStartElement("D", "propstat", DAV_NS);
                writer.writeStartElement("D", "prop", DAV_NS);

                element(writer, "displayname", entry.displayName());

                writer.writeStartElement("D", "resourcetype", DAV_NS);
                if (entry.directory()) {
                    writer.writeEmptyElement("D", "collection", DAV_NS);
                }
                writer.writeEndElement();

                if (!entry.directory()) {
                    element(writer, "getcontentlength", String.valueOf(entry.size()));
                    element(writer, "getcontenttype",
                            entry.contentType() == null ? "application/octet-stream" : entry.contentType());
                }
                if (entry.lastModified() != null) {
                    element(writer, "getlastmodified", HTTP_DATE.format(entry.lastModified()));
                }
                if (entry.etag() != null) {
                    element(writer, "getetag", entry.etag());
                }

                // task-126：客户端需要 supportedlock 才能知道自己可否加锁；
                // 被锁资源额外返回 lockdiscovery，避免 Office 以为“没锁”。
                writer.writeStartElement("D", "supportedlock", DAV_NS);
                writer.writeStartElement("D", "lockentry", DAV_NS);
                writer.writeStartElement("D", "lockscope", DAV_NS);
                writer.writeEmptyElement("D", "exclusive", DAV_NS);
                writer.writeEndElement();
                writer.writeStartElement("D", "locktype", DAV_NS);
                writer.writeEmptyElement("D", "write", DAV_NS);
                writer.writeEndElement();
                writer.writeEndElement();
                writer.writeEndElement();

                if (entry.lockToken() != null) {
                    writer.writeStartElement("D", "lockdiscovery", DAV_NS);
                    writeActiveLock(writer, entry.href(), entry.lockToken(),
                            WebDavService.LOCK_TIMEOUT_SECONDS);
                    writer.writeEndElement();
                }

                writer.writeEndElement(); // prop
                element(writer, "status", "HTTP/1.1 200 OK");
                writer.writeEndElement(); // propstat
                writer.writeEndElement(); // response
            }

            writer.writeEndElement(); // multistatus
            writer.writeEndDocument();
            writer.flush();
            writer.close();
            return out.toString();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("failed to build WebDAV XML", e);
        }
    }

    /** LOCK 成功时返回的 lockdiscovery 体。 */
    public static String lockDiscovery(String href, String token, long timeoutSeconds) {
        try {
            StringWriter out = new StringWriter();
            XMLStreamWriter writer = XMLOutputFactory.newFactory().createXMLStreamWriter(out);
            writer.writeStartDocument("UTF-8", "1.0");
            writer.writeStartElement("D", "prop", DAV_NS);
            writer.writeNamespace("D", DAV_NS);
            writer.writeStartElement("D", "lockdiscovery", DAV_NS);
            writeActiveLock(writer, href, token, timeoutSeconds);
            writer.writeEndElement(); // lockdiscovery
            writer.writeEndElement(); // prop
            writer.writeEndDocument();
            writer.flush();
            writer.close();
            return out.toString();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("failed to build WebDAV XML", e);
        }
    }

    private static void writeActiveLock(XMLStreamWriter writer, String href, String token,
                                        long timeoutSeconds) throws XMLStreamException {
        writer.writeStartElement("D", "activelock", DAV_NS);
        writer.writeStartElement("D", "locktype", DAV_NS);
        writer.writeEmptyElement("D", "write", DAV_NS);
        writer.writeEndElement();
        writer.writeStartElement("D", "lockscope", DAV_NS);
        writer.writeEmptyElement("D", "exclusive", DAV_NS);
        writer.writeEndElement();
        element(writer, "depth", "infinity");
        element(writer, "timeout", "Second-" + timeoutSeconds);
        writer.writeStartElement("D", "locktoken", DAV_NS);
        element(writer, "href", token);
        writer.writeEndElement();
        writer.writeStartElement("D", "lockroot", DAV_NS);
        element(writer, "href", href);
        writer.writeEndElement();
        writer.writeEndElement(); // activelock
    }

    private static void element(XMLStreamWriter writer, String localName, String value) throws XMLStreamException {
        writer.writeStartElement("D", localName, DAV_NS);
        if (value != null) {
            writer.writeCharacters(value);
        }
        writer.writeEndElement();
    }
}
