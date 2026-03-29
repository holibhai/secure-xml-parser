package com.xml.xml;

import org.w3c.dom.Document;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.Deque;

public class XmlParser {

    private static final int DEFAULT_MAX_XML_CHARACTERS = 100_000;
    private static final int DEFAULT_MAX_ELEMENT_DEPTH = 64;
    private static final EntityResolver BLOCKING_ENTITY_RESOLVER =
            (publicId, systemId) -> new InputSource(new StringReader(""));

    private final int maxXmlCharacters;
    private final int maxElementDepth;

    public XmlParser() {
        this(DEFAULT_MAX_XML_CHARACTERS, DEFAULT_MAX_ELEMENT_DEPTH);
    }

    public XmlParser(int maxXmlCharacters, int maxElementDepth) {
        if (maxXmlCharacters <= 0) {
            throw new IllegalArgumentException("Maximum XML characters must be greater than zero");
        }
        if (maxElementDepth <= 0) {
            throw new IllegalArgumentException("Maximum XML depth must be greater than zero");
        }

        this.maxXmlCharacters = maxXmlCharacters;
        this.maxElementDepth = maxElementDepth;
    }

    public Document parse(String xmlContent) {
        validateXmlInput(xmlContent);
        assertSafeStructure(xmlContent);
        return parseInternal(new InputSource(new StringReader(xmlContent)), "Failed to parse XML securely");
    }

    public Document parse(Reader reader) {
        if (reader == null) {
            throw new IllegalArgumentException("XML reader must not be null");
        }

        return parse(readXml(reader));
    }

    public Document parseAndValidate(String xmlContent, Source schemaSource) {
        validateXmlInput(xmlContent);
        if (schemaSource == null) {
            throw new IllegalArgumentException("Schema source must not be null");
        }

        assertSafeStructure(xmlContent);

        try {
            Schema schema = createSecureSchemaFactory().newSchema(schemaSource);
            Document document = parse(xmlContent);
            Validator validator = schema.newValidator();
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            validator.validate(new DOMSource(document));
            return document;
        } catch (ParserConfigurationException | SAXException | IOException exception) {
            throw new XmlParsingException("Failed to parse and validate XML securely", exception);
        }
    }

    public Document parseAndValidate(String xmlContent, String xsdContent) {
        if (xsdContent == null || xsdContent.isBlank()) {
            throw new IllegalArgumentException("XSD content must not be null or blank");
        }

        return parseAndValidate(xmlContent, new StreamSource(new StringReader(xsdContent)));
    }

    protected String protectXml(String xmlValue) {
        if (xmlValue == null) {
            return null;
        }

        StringBuilder protectedXml = new StringBuilder(xmlValue.length());
        for (char character : xmlValue.toCharArray()) {
            switch (character) {
                case '&' -> protectedXml.append("&amp;");
                case '<' -> protectedXml.append("&lt;");
                case '>' -> protectedXml.append("&gt;");
                case '"' -> protectedXml.append("&quot;");
                case '\'' -> protectedXml.append("&apos;");
                default -> protectedXml.append(character);
            }
        }

        return protectedXml.toString();
    }

    private String readXml(Reader reader) {
        StringBuilder xmlContent = new StringBuilder();
        char[] buffer = new char[4096];
        int readCount;

        try {
            while ((readCount = reader.read(buffer)) != -1) {
                xmlContent.append(buffer, 0, readCount);
                if (xmlContent.length() > maxXmlCharacters) {
                    throw new XmlParsingException(
                            "XML input exceeds the configured size limit of " + maxXmlCharacters + " characters");
                }
            }
        } catch (IOException exception) {
            throw new XmlParsingException("Failed to read XML input", exception);
        }

        return xmlContent.toString();
    }

    private void validateXmlInput(String xmlContent) {
        if (xmlContent == null || xmlContent.isBlank()) {
            throw new IllegalArgumentException("XML content must not be null or blank");
        }
        if (xmlContent.length() > maxXmlCharacters) {
            throw new XmlParsingException(
                    "XML input exceeds the configured size limit of " + maxXmlCharacters + " characters");
        }
    }

    private void assertSafeStructure(String xmlContent) {
        int deepestDepth = 0;
        Deque<String> elementStack = new ArrayDeque<>();

        for (int index = 0; index < xmlContent.length(); index++) {
            if (xmlContent.charAt(index) != '<') {
                continue;
            }

            if (startsWith(xmlContent, index, "<!--")) {
                index = skipUntil(xmlContent, index + 4, "-->");
                continue;
            }
            if (startsWith(xmlContent, index, "<![CDATA[")) {
                index = skipUntil(xmlContent, index + 9, "]]>");
                continue;
            }
            if (startsWith(xmlContent, index, "<?")) {
                index = skipUntil(xmlContent, index + 2, "?>");
                continue;
            }
            if (startsWith(xmlContent, index, "<!DOCTYPE") || startsWith(xmlContent, index, "<!ENTITY")) {
                throw new XmlParsingException("DOCTYPE and ENTITY declarations are not allowed");
            }

            int tagEnd = findTagEnd(xmlContent, index);
            if (startsWith(xmlContent, index, "</")) {
                String closingTagName = extractTagName(xmlContent, index, tagEnd);
                if (elementStack.isEmpty()) {
                    throw new XmlParsingException("Malformed XML: closing tag without a matching opening tag");
                }

                String openingTagName = elementStack.removeLast();
                if (!openingTagName.equals(closingTagName)) {
                    throw new XmlParsingException(
                            "Malformed XML: expected closing tag for <" + openingTagName + "> but found </"
                                    + closingTagName + ">");
                }
                index = tagEnd;
                continue;
            }

            if (!isOpeningTag(xmlContent, index)) {
                index = tagEnd;
                continue;
            }

            if (!isSelfClosingTag(xmlContent, index, tagEnd)) {
                elementStack.addLast(extractTagName(xmlContent, index, tagEnd));
                deepestDepth = Math.max(deepestDepth, elementStack.size());
                if (deepestDepth > maxElementDepth) {
                    throw new XmlParsingException(
                            "XML nesting depth exceeds the configured limit of " + maxElementDepth);
                }
            }

            index = tagEnd;
        }

        if (!elementStack.isEmpty()) {
            throw new XmlParsingException(
                    "Malformed XML: missing closing tag for <" + elementStack.peekLast() + ">");
        }
    }

    private boolean isOpeningTag(String xmlContent, int tagStartIndex) {
        int nameStart = tagStartIndex + 1;
        if (nameStart >= xmlContent.length()) {
            return false;
        }

        char firstCharacter = xmlContent.charAt(nameStart);
        return Character.isLetter(firstCharacter) || firstCharacter == '_' || firstCharacter == ':';
    }

    private int findTagEnd(String xmlContent, int tagStartIndex) {
        boolean insideQuotes = false;
        char quoteCharacter = 0;

        for (int index = tagStartIndex + 1; index < xmlContent.length(); index++) {
            char currentCharacter = xmlContent.charAt(index);
            if (insideQuotes) {
                if (currentCharacter == quoteCharacter) {
                    insideQuotes = false;
                }
                continue;
            }

            if (currentCharacter == '"' || currentCharacter == '\'') {
                insideQuotes = true;
                quoteCharacter = currentCharacter;
                continue;
            }

            if (currentCharacter == '>') {
                return index;
            }
        }

        throw new XmlParsingException("Malformed XML: missing closing bracket for a tag");
    }

    private boolean isSelfClosingTag(String xmlContent, int tagStartIndex, int tagEndIndex) {
        for (int index = tagEndIndex - 1; index > tagStartIndex; index--) {
            char currentCharacter = xmlContent.charAt(index);
            if (Character.isWhitespace(currentCharacter)) {
                continue;
            }
            return currentCharacter == '/';
        }

        return false;
    }

    private String extractTagName(String xmlContent, int tagStartIndex, int tagEndIndex) {
        int nameStart = tagStartIndex + 1;
        if (xmlContent.charAt(nameStart) == '/') {
            nameStart++;
        }

        int nameEnd = nameStart;
        while (nameEnd < tagEndIndex) {
            char currentCharacter = xmlContent.charAt(nameEnd);
            if (Character.isWhitespace(currentCharacter) || currentCharacter == '/' || currentCharacter == '>') {
                break;
            }
            nameEnd++;
        }

        if (nameStart == nameEnd) {
            throw new XmlParsingException("Malformed XML: missing element name");
        }

        return xmlContent.substring(nameStart, nameEnd);
    }

    private int skipUntil(String xmlContent, int startIndex, String terminator) {
        int endIndex = xmlContent.indexOf(terminator, startIndex);
        if (endIndex == -1) {
            throw new XmlParsingException("Malformed XML: unterminated section");
        }

        return endIndex + terminator.length() - 1;
    }

    private boolean startsWith(String xmlContent, int index, String token) {
        return xmlContent.regionMatches(index, token, 0, token.length());
    }

    private Document parseInternal(InputSource inputSource, String failureMessage) {
        try {
            DocumentBuilder builder = createSecureDocumentBuilderFactory().newDocumentBuilder();
            builder.setEntityResolver(BLOCKING_ENTITY_RESOLVER);
            Document document = builder.parse(inputSource);
            document.getDocumentElement().normalize();
            return document;
        } catch (ParserConfigurationException | SAXException | IOException exception) {
            throw new XmlParsingException(failureMessage, exception);
        }
    }

    private DocumentBuilderFactory createSecureDocumentBuilderFactory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setValidating(false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }

    private SchemaFactory createSecureSchemaFactory() throws SAXException, ParserConfigurationException {
        SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        schemaFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        schemaFactory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        schemaFactory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return schemaFactory;
    }

    public static class XmlParsingException extends RuntimeException {

        public XmlParsingException(String message) {
            super(message);
        }

        public XmlParsingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
