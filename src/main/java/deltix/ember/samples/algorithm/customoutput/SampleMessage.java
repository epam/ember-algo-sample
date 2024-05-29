package deltix.ember.samples.algorithm.customoutput;

import deltix.qsrv.hf.pub.InstrumentMessage;

public class SampleMessage extends InstrumentMessage {
    private long sequence;

    public long getSequence() {
        return sequence;
    }

    public void setSequence(long sequence) {
        this.sequence = sequence;
    }
}
