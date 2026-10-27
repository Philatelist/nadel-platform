package app.nadel.reports.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;
import org.jspecify.annotations.Nullable;

/**
 * The small pieces of XML the template filler holds in memory (ADR 0015): one element with its subtree (a row of a
 * sheet, the merged ranges), read from the StAX events of {@link TemplateXml} and written with the JDK's StAX writer,
 * namespaces, prefixes and the order of attributes as read; every text and attribute value passes
 * {@link XmlText#clean}. Everything else of a part streams from reader to writer
 * ({@link #start}, {@link #copy}). Building and writing use an explicit stack, never recursion, so a deeply nested or
 * hostile subtree cannot exhaust the stack.
 */
final class XmlTree {

    static final String MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    static final String XML = "http://www.w3.org/XML/1998/namespace";

    private XmlTree() {
    }

    /** One element: its name, its namespace declarations, its attributes and its children (nodes and text). */
    static final class Node {

        final String prefix;
        final String local;
        final String uri;
        final List<String[]> namespaces;
        final List<String[]> attributes;
        final List<Object> children;

        Node(String prefix, String local, String uri) {
            this(prefix, local, uri, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }

        private Node(String prefix, String local, String uri, List<String[]> namespaces, List<String[]> attributes,
                List<Object> children) {
            this.prefix = prefix;
            this.local = local;
            this.uri = uri;
            this.namespaces = namespaces;
            this.attributes = attributes;
            this.children = children;
        }

        /** The element at the reader's start event, without children. */
        static Node of(XMLStreamReader reader) {
            Node node = new Node(Objects.toString(reader.getPrefix(), ""), reader.getLocalName(),
                    Objects.toString(reader.getNamespaceURI(), ""));
            for (int i = 0; i < reader.getNamespaceCount(); i++) {
                node.namespaces.add(new String[] {Objects.toString(reader.getNamespacePrefix(i), ""),
                        reader.getNamespaceURI(i)});
            }
            for (int i = 0; i < reader.getAttributeCount(); i++) {
                node.attributes.add(new String[] {Objects.toString(reader.getAttributePrefix(i), ""),
                        Objects.toString(reader.getAttributeNamespace(i), ""), reader.getAttributeLocalName(i),
                        reader.getAttributeValue(i)});
            }
            return node;
        }

        /** A copy of the element sharing the children; its own lists can be changed. */
        Node shallow() {
            return new Node(prefix, local, uri, new ArrayList<>(namespaces), new ArrayList<>(attributes),
                    new ArrayList<>(children));
        }

        /** A new element of the same namespace, without attributes or children. */
        Node sibling(String name) {
            return new Node(prefix, name, uri);
        }

        /** The value of an attribute without a namespace; null when absent. */
        @Nullable String attr(String name) {
            for (String[] attribute : attributes) {
                if (attribute[1].isEmpty() && attribute[2].equals(name)) {
                    return attribute[3];
                }
            }
            return null;
        }

        /** Sets an attribute without a namespace in its place (added last), or removes it with null. */
        Node attr(String name, @Nullable String value) {
            for (int i = 0; i < attributes.size(); i++) {
                String[] attribute = attributes.get(i);
                if (attribute[1].isEmpty() && attribute[2].equals(name)) {
                    if (value == null) {
                        attributes.remove(i);
                    } else {
                        attributes.set(i, new String[] {"", "", name, value}); // never changes a shared array
                    }
                    return this;
                }
            }
            if (value != null) {
                attributes.add(new String[] {"", "", name, value});
            }
            return this;
        }

        /** The first child element of the name; null when there is none. */
        @Nullable Node child(String name) {
            for (Object child : children) {
                if (child instanceof Node node && node.local.equals(name)) {
                    return node;
                }
            }
            return null;
        }

        /** The text of the element's own text children. */
        String text() {
            StringBuilder text = new StringBuilder();
            for (Object child : children) {
                if (child instanceof String part) {
                    text.append(part);
                }
            }
            return text.toString();
        }

        /** Replaces the children by one text. */
        Node text(String text) {
            children.clear();
            children.add(text);
            return this;
        }
    }

    /** Builds one element's subtree from the events that follow its start event. */
    static final class Builder {

        private final Deque<Node> open = new ArrayDeque<>();
        private final Node root;

        /** Starts at the reader's start event. */
        Builder(XMLStreamReader reader) {
            root = Node.of(reader);
            open.push(root);
        }

        /** Feeds the next event; returns the element once its end event arrived, null before. */
        @Nullable Node feed(XMLStreamReader reader) {
            switch (reader.getEventType()) {
                case XMLStreamConstants.START_ELEMENT -> {
                    Node child = Node.of(reader);
                    Objects.requireNonNull(open.peek()).children.add(child);
                    open.push(child);
                }
                case XMLStreamConstants.END_ELEMENT -> {
                    open.pop();
                    return open.isEmpty() ? root : null;
                }
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE ->
                        Objects.requireNonNull(open.peek()).children.add(reader.getText());
                default -> { } // comments and processing instructions inside a captured element are dropped
            }
            return null;
        }
    }

    /** Writes the element and its subtree. */
    static void write(XMLStreamWriter writer, Node node) throws XMLStreamException {
        Deque<Iterator<Object>> stack = new ArrayDeque<>();
        startElement(writer, node);
        stack.push(node.children.iterator());
        while (!stack.isEmpty()) {
            Iterator<Object> children = stack.peek();
            if (!children.hasNext()) {
                writer.writeEndElement();
                stack.pop();
                continue;
            }
            Object child = children.next();
            if (child instanceof Node element) {
                startElement(writer, element);
                stack.push(element.children.iterator());
            } else {
                writer.writeCharacters(XmlText.clean((String) child));
            }
        }
    }

    /**
     * Streams one part from the reader to the writer, event by event. Subclasses change what passes: a start tag can be
     * changed or dropped with its subtree ({@link #start}), an element can be taken whole as a {@link Node}
     * ({@link #capture}, then {@link #captured}), the text of an element can be changed ({@link #text}, given whole even
     * when the parser splits it) and elements can be added before an end tag ({@link #end}). Comments and processing
     * instructions pass as they are.
     */
    abstract static class Copy implements TemplateXml.Handler {

        final XMLStreamWriter out;
        private final Deque<String> open = new ArrayDeque<>();
        private final StringBuilder pending = new StringBuilder();
        private @Nullable Builder builder;
        private int skip;

        Copy(XMLStreamWriter out) {
            this.out = out;
        }

        /** Whether the element at this depth (the root is 1) is taken whole. */
        boolean capture(Node element, int depth) {
            return false;
        }

        /** A captured element, to write as it should be. */
        void captured(Node element) throws XMLStreamException {
            write(out, element);
        }

        /** The start tag to write; null drops the element and its subtree. Elements may be written before it. */
        @Nullable Node start(Node element, int depth) throws XMLStreamException {
            return element;
        }

        /** The text to write inside the element. */
        String text(String element, String text) {
            return text;
        }

        /** Called before the end tag of the element; elements written here are its last children. */
        void end(String element, int depth) throws XMLStreamException {
        }

        @Override
        public final void on(XMLStreamReader reader) throws XMLStreamException {
            if (builder != null) {
                Node done = builder.feed(reader);
                if (done != null) {
                    builder = null;
                    captured(done);
                }
                return;
            }
            int event = reader.getEventType();
            if (skip > 0) {
                skip += event == XMLStreamConstants.START_ELEMENT ? 1 : event == XMLStreamConstants.END_ELEMENT ? -1 : 0;
                return;
            }
            switch (event) {
                case XMLStreamConstants.START_ELEMENT -> {
                    flush();
                    Node element = Node.of(reader);
                    if (capture(element, open.size() + 1)) {
                        builder = new Builder(reader);
                        return;
                    }
                    Node written = start(element, open.size() + 1);
                    if (written == null) {
                        skip = 1;
                        return;
                    }
                    startElement(out, written);
                    open.push(written.local);
                }
                case XMLStreamConstants.END_ELEMENT -> {
                    flush();
                    end(open.peek(), open.size());
                    out.writeEndElement();
                    open.pop();
                }
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE ->
                        pending.append(reader.getText());
                case XMLStreamConstants.COMMENT -> {
                    flush();
                    out.writeComment(reader.getText());
                }
                case XMLStreamConstants.PROCESSING_INSTRUCTION -> {
                    flush();
                    out.writeProcessingInstruction(reader.getPITarget(), reader.getPIData());
                }
                case XMLStreamConstants.END_DOCUMENT -> out.writeEndDocument();
                default -> { }
            }
        }

        private void flush() throws XMLStreamException {
            if (!pending.isEmpty()) {
                String text = open.isEmpty() ? pending.toString() : text(open.peek(), pending.toString());
                out.writeCharacters(XmlText.clean(text));
                pending.setLength(0);
            }
        }
    }

    private static void startElement(XMLStreamWriter writer, Node node) throws XMLStreamException {
        writer.writeStartElement(node.prefix, node.local, node.uri);
        for (String[] namespace : node.namespaces) {
            if (namespace[0].isEmpty()) {
                writer.writeDefaultNamespace(namespace[1]);
            } else {
                writer.writeNamespace(namespace[0], namespace[1]);
            }
        }
        for (String[] attribute : node.attributes) {
            if (attribute[1].isEmpty()) {
                writer.writeAttribute(attribute[2], XmlText.clean(attribute[3]));
            } else {
                writer.writeAttribute(attribute[0], attribute[1], attribute[2], XmlText.clean(attribute[3]));
            }
        }
    }
}
