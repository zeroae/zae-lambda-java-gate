package co.zeroae.gate.mmap;

import com.amazonaws.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

public class Handler extends URLStreamHandler {
    public static final Map<String, byte[]> paths = Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    protected URLConnection openConnection(URL u) {
        return new Connection(u);
    }

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
            return new ByteArrayInputStream(paths.get(url.getPath()));
        }
    }
}
