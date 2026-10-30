package com.bond.transfer.webdav;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebDavXmlTest {

    @Test
    void multiStatusContainsCollectionAndFileProperties() {
        List<WebDavEntry> entries = List.of(
                new WebDavEntry("/dav/docs/", "docs", true, 0L, Instant.parse("2026-10-23T00:00:00Z"),
                        null, WebDavService.CONTENT_TYPE_DIR, null),
                new WebDavEntry("/dav/docs/a%20b.txt", "a b.txt", false, 262144L,
                        Instant.parse("2026-10-23T00:00:00Z"),
                        "\"9f86d081\"", "text/plain", null));

        String xml = WebDavXml.multiStatus(entries);

        assertTrue(xml.contains("<D:multistatus xmlns:D=\"DAV:\">"), xml);
        assertTrue(xml.contains("<D:collection/>"), xml);
        assertTrue(xml.contains("<D:getcontentlength>262144</D:getcontentlength>"), xml);
        assertTrue(xml.contains("<D:getetag>\"9f86d081\"</D:getetag>"), xml);
        assertTrue(xml.contains("HTTP/1.1 200 OK"), xml);
        assertTrue(xml.contains("<D:href>/dav/docs/a%20b.txt</D:href>"), xml);
        assertTrue(xml.contains("<D:supportedlock>"), xml);
        assertTrue(xml.contains("<D:exclusive/>"), xml);
    }

    @Test
    void multiStatusEscapesXmlSpecialCharacters() {
        String xml = WebDavXml.multiStatus(List.of(
                new WebDavEntry("/dav/a&b.txt", "a&b.txt", false, 1L, null, null, null, null)));

        assertTrue(xml.contains("a&amp;b.txt"), xml);
        assertTrue(xml.contains("<D:getcontentlength>1</D:getcontentlength>"), xml);
        assertTrue(xml.contains("<D:getcontenttype>application/octet-stream</D:getcontenttype>"), xml);
    }

    @Test
    void lockDiscoveryCarriesTokenAndTimeout() {
        String xml = WebDavXml.lockDiscovery("/dav/a.txt", "opaquelocktoken:abc", 1800L);

        assertTrue(xml.contains("<D:lockdiscovery>"), xml);
        assertTrue(xml.contains("<D:write/>"), xml);
        assertTrue(xml.contains("<D:exclusive/>"), xml);
        assertTrue(xml.contains("<D:timeout>Second-1800</D:timeout>"), xml);
        assertTrue(xml.contains("opaquelocktoken:abc"), xml);
        assertTrue(xml.contains("<D:lockroot>"), xml);
    }
}
