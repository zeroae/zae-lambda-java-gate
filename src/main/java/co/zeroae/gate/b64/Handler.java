package co.zeroae.gate.b64;

import com.amazonaws.util.Base64;
import com.amazonaws.util.StringInputStream;
import org.apache.commons.codec.binary.Base64InputStream;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

public class Handler extends URLStreamHandler {
    static final public Map<String, String> paths = Collections.synchronizedMap(new WeakHashMap<>());
    private class Connection extends URLConnection {
        /**
         * Constructs a URL connection to the specified URL. A connection to
         * the object referenced by the URL is not created.
         *
         * @param url the specified URL.
         */
        protected Connection(URL url) {
            super(url);
        }
        @Override
        public void connect() {
        }

        @Override
        public String getContentType() {
            return new String(Base64.decode(url.getHost()));
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return new Base64InputStream(new StringInputStream(
                    paths.get(url.getPath())
            ));
        }
    }
    @Override
    protected URLConnection openConnection(URL u) {
        return new Connection(u);
    }
}
