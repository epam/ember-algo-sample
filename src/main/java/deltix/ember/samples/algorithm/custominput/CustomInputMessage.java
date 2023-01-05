package deltix.ember.samples.algorithm.custominput;

import deltix.qsrv.hf.pub.InstrumentMessage;
import deltix.qsrv.hf.pub.RecordInfo;
import deltix.qsrv.hf.pub.md.FloatDataType;
import deltix.timebase.api.SchemaElement;

/** Custom input message that algorithm may process */
@SchemaElement
public class CustomInputMessage extends InstrumentMessage {

    /** sample payload (this one is a simple public field but you can aslo use POJO style) */
    public CharSequence data;

    @Override
    public InstrumentMessage copyFrom(RecordInfo source) {
        super.copyFrom(source);
        if (source instanceof CustomInputMessage) {
            final CustomInputMessage obj = (CustomInputMessage) source;
            data = obj.data;
        }
        return this;
    }

    @Override
    protected InstrumentMessage createInstance() {
        return new CustomInputMessage();
    }

}

