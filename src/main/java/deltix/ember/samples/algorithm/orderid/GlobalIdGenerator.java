package deltix.ember.samples.algorithm.orderid;

import deltix.ember.util.IdGenerator;

import java.io.Closeable;
import java.io.IOException;

/**
 * UHF 4.3 code: INT64 identifiers backed by file (high-low algorithm)
 */
public class GlobalIdGenerator implements IdGenerator, Closeable {
    public static final GlobalIdGenerator INSTANCE = new GlobalIdGenerator();

    private final FileHiLowIdentifierGenerator identifierGenerator;

    private GlobalIdGenerator() {
        try {
            int blockSize = Integer.parseInt(System.getProperty("deltix.util.id.blockSize", "1000"));
            int startId = Integer.parseInt(System.getProperty("deltix.util.id.startId", "1"));
            identifierGenerator = new FileHiLowIdentifierGenerator("child-orders", blockSize, startId, true, "rwd");
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Can't initialize UHF Order Id Generator", e);
        }
    }

    @Override
    public long next() {
        return identifierGenerator.next();
    }

    @Override
    public void close() throws IOException {
        identifierGenerator.close(); //TODO: Make sure some ember service (algo, etc) calls this on shutdown!
    }
}
