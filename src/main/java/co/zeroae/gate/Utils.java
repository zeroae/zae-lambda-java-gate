package co.zeroae.gate;

import gate.Document;
import gate.DocumentExporter;
import gate.Factory;
import gate.corpora.export.GATEJsonExporter;
import gate.corpora.export.GateXMLExporter;
import gate.creole.ResourceInstantiationException;
import gate.util.GateException;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.Reader;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class Utils {

    interface TextProcessor {
        Document process() throws GateException;
    }

    /**
     *
     * @param gateXMLReader a Reader with GateXML content.
     * @return The parsed Document.
     * @throws ResourceInstantiationException if the Factory fails to create an empty Document.
     * @throws XMLStreamException if the reader has invalid XML content.
     */
    static Document xmlToDocument(Reader gateXMLReader) throws ResourceInstantiationException, XMLStreamException {
        final Document doc = Factory.newDocument("");
        XMLStreamReader reader;
        reader = XMLInputFactory.newFactory().createXMLStreamReader(gateXMLReader);
        do {
            reader.next();
        } while(reader.getEventType() != XMLStreamReader.START_ELEMENT);
        gate.corpora.DocumentStaxUtils.readGateXmlDocument(reader, doc);
        return doc;
    }

    /**
     * Loads all exporters that we support
     * @return an UnmodifiableMap of the supported exporters.
     */
    static Map<String, DocumentExporter> loadExporters() {
        final GateXMLExporter gateXMLExporter = new GateXMLExporter();
        final GATEJsonExporter gateJsonExporter = new GATEJsonExporter();
        final Map<String, DocumentExporter> rv = new HashMap<>();
        rv.put("application/gate+xml", gateXMLExporter);
        rv.put("application/gate+json", gateJsonExporter);
        return Collections.unmodifiableMap(rv);
    }


}
