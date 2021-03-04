package co.zeroae.gate;

import gate.Document;
import gate.Factory;
import gate.creole.ResourceInstantiationException;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.Reader;

public class Utils {
    /**
     *
     * @param gateXMLReader
     * @return
     * @throws ResourceInstantiationException if Document
     * @throws XMLStreamException
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
}
