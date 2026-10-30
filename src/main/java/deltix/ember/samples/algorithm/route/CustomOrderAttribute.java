package deltix.ember.samples.algorithm.route;

import deltix.ember.message.trade.CustomAttribute;
import com.epam.deltix.gflog.api.AppendableEntry;
import deltix.anvil.util.AppendableEntryBuilder;

public class CustomOrderAttribute implements CustomAttribute {
    private int key;
    private String value;

    public CustomOrderAttribute () {
    }

    public CustomOrderAttribute (int key, String value) {
        this.key = key;
        this.value = value;
    }

    @Override
    public int getKey() {
        return key;
    }

    @Override
    public void setKey(int key) {
        this.key = key;
    }

    @Override
    public boolean hasKey() {
        return true;
    }

    @Override
    public String getValue() {
        return value;
    }

    @Override
    public void setValue(CharSequence value) {
        this.value = (value != null) ? value.toString() : null;
    }


    @Override
    public boolean hasValue() {
        return true;
    }

    @Override
    public CustomAttribute copy() {
        return new CustomOrderAttribute(key, value);
    }

    @Override
    public void appendTo(AppendableEntryBuilder entry) {
        entry.append("key:").append(key).append(",value:").append(value);
    }

    @Override
    public void appendTo(AppendableEntry entry) {
        entry.append("key:").append(key).append(",value:").append(value);
    }
}
