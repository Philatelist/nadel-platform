package app.nadel.reports.internal;

import app.nadel.problem.ApiProblemException;
import java.io.InputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * The one way the reports module reads the XML parts of an uploaded template: the JDK's own StAX parser
 * ({@link XMLInputFactory#newDefaultFactory()}, never one picked from the class path) with DTD support and external
 * entities switched off and entity references not replaced. A part that declares a document type ({@code <!DOCTYPE})
 * is refused outright, whether or not it declares entities: no workbook part needs one, and it is how XXE and
 * entity-expansion attacks start.
 */
final class TemplateXml {

    /** A part is not usable: not well-formed, or it declares a document type. */
    static final class UnsafeXmlException extends Exception {

        UnsafeXmlException() {
            super("The XML part is not well-formed or declares a document type", null, false, false);
        }
    }

    /** Receives the events of one part, one by one, while it is read. */
    @FunctionalInterface
    interface Handler {

        void on(XMLStreamReader reader) throws XMLStreamException;
    }

    private static final XMLInputFactory FACTORY = factory();

    private TemplateXml() {
    }

    /**
     * Reads the whole part, handing every event but the document type to the handler.
     *
     * @throws UnsafeXmlException when the part is not well-formed XML or has a {@code <!DOCTYPE>}
     */
    static void read(InputStream part, Handler handler) throws UnsafeXmlException {
        XMLStreamReader reader = null;
        try {
            reader = FACTORY.createXMLStreamReader(part);
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
                    throw new UnsafeXmlException();
                }
                handler.on(reader);
            }
        } catch (ApiProblemException e) {
            throw e; // a refusal of the caller's own (a part that unpacks too large), not a parser failure
        } catch (XMLStreamException | RuntimeException e) {
            // the parser's message can quote the part's contents: never kept
            throw new UnsafeXmlException();
        } finally {
            close(reader);
        }
    }

    private static void close(XMLStreamReader reader) {
        if (reader == null) {
            return;
        }
        try {
            reader.close();
        } catch (XMLStreamException e) {
            // nothing to release that matters: the input is an in-memory or bounded stream closed by the caller
        }
    }

    private static XMLInputFactory factory() {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, false);
        return factory;
    }
}
