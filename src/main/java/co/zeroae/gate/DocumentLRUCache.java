package co.zeroae.gate;

import com.amazonaws.xray.AWSXRay;
import com.jakewharton.disklrucache.DiskLruCache;
import gate.Document;
import gate.creole.ResourceInstantiationException;
import gate.util.GateException;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import javax.xml.stream.XMLStreamException;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Objects;

class DocumentLRUCache {
    private static final int VERSION = 1;
    private static final int VALUE_COUNT = 1;

    private static final Logger logger = LogManager.getLogger(DocumentLRUCache.class);

    private final DiskLruCache cache;

    DocumentLRUCache(String cacheDir, double maxUsage) {
        cache = initializeCache(cacheDir, maxUsage);
    }

    public Document computeIfNull(String key, Utils.GATESupplier<Document> supplier) throws GateException {
        Document rv = get(key);
        if (rv == null) {
            rv = supplier.get();
            put(key, rv);
        }
        return rv;
    }

    public Document get(String key) {
        AWSXRay.beginSubsegment("Cache Read");
        try {
            final DiskLruCache.Snapshot snapshot = cache.get(key);
            if (snapshot != null) {
                AWSXRay.beginSubsegment("Deserialize");
                try {
                    return Utils.xmlToDocument(new InputStreamReader(snapshot.getInputStream(0)));
                } catch (ResourceInstantiationException | XMLStreamException e) {
                    logger.warn(e);
                    AWSXRay.getCurrentSubsegment().addException(e);
                    cache.remove(key);
                    return null;
                } finally {
                    AWSXRay.endSubsegment();
                }
            } else
                return null;
        } catch (IOException e) {
            logger.warn(e);
            return null;
        } finally {
            AWSXRay.endSubsegment();
        }
    }

    public void put(String key, Document doc) {
        AWSXRay.beginSubsegment("Cache Edit");
        try {
            DiskLruCache.Editor editor = cache.edit(key);
            editor.set(0, doc.toXml());
            editor.commit();
        } catch (IOException e) {
            logger.warn(e);
            AWSXRay.getCurrentSubsegment().addException(e);
        } finally {
            AWSXRay.endSubsegment();
        }
    }

    private static DiskLruCache initializeCache(String cacheDir, double maxUsage) {
        File cacheDirPath = new File(cacheDir);
        if (!cacheDirPath.exists() && !cacheDirPath.mkdirs()) {
            throw new RuntimeException("Unable to create cache directory '" + cacheDirPath.getName() + "'.");
        }
        for (File file: Objects.requireNonNull(cacheDirPath.listFiles())) file.delete();
        try {
            long usableSpace = (long) (cacheDirPath.getUsableSpace()*maxUsage);
            return DiskLruCache.open(cacheDirPath, VERSION, VALUE_COUNT, usableSpace);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
