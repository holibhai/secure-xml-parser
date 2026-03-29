package com.xml.xml;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class XmlParserTest {

    private final XmlParser parser = new XmlParser();

    @Test
    void parsesXmlWithGreaterThanInsideAttributeValue() {
        Document document = parser.parse("<root note=\"2 > 1\"><child/></root>");

        assertEquals("root", document.getDocumentElement().getTagName());
        assertEquals("2 > 1", document.getDocumentElement().getAttribute("note"));
    }

    @Test
    void parsesSelfClosingTagWithWhitespaceBeforeSlash() {
        Document document = parser.parse("<root><child attr=\"value\" /></root>");

        assertEquals("child", document.getDocumentElement().getFirstChild().getNodeName());
    }

    @Test
    void rejectsClosingTagWithoutMatchingOpeningTag() {
        XmlParser.XmlParsingException exception = assertThrows(
                XmlParser.XmlParsingException.class,
                () -> parser.parse("</root>")
        );

        assertEquals("Malformed XML: closing tag without a matching opening tag", exception.getMessage());
    }

    @Test
    void rejectsMissingClosingTagBeforeDomParsing() {
        XmlParser.XmlParsingException exception = assertThrows(
                XmlParser.XmlParsingException.class,
                () -> parser.parse("<root><child></root>")
        );

        assertEquals("Malformed XML: missing closing tag", exception.getMessage());
    }
}
