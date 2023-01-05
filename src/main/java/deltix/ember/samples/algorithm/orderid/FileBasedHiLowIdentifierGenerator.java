package deltix.ember.samples.algorithm.orderid;

import java.io.File;

public abstract class FileBasedHiLowIdentifierGenerator extends HiLowIdentifierGenerator {

	protected final File file;
	
	protected FileBasedHiLowIdentifierGenerator(String key, int blockSize, long startId) {
		super(key, blockSize, startId);

        file = getSequenceFile(key);
	}

    static final File getSequenceFile (String key) {
	    String emberWorkDirName = System.getProperty("ember.work");
	    if (emberWorkDirName == null)
	        throw new IllegalArgumentException("Ember work directory is not defined");
        File traderDir = new File (emberWorkDirName, "trader");
        traderDir.getAbsoluteFile().mkdirs();
        return new File (traderDir, "sequence-"+key+".id");
    }

}
