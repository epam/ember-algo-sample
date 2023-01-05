package deltix.ember.samples.algorithm.route;

import deltix.ember.message.trade.CustomAttribute;
import deltix.gflog.AppendableEntry;

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
    public boolean hasKey() {
        return true;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
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
    public void appendTo(AppendableEntry entry) {
        entry.append("key:").append(key).append(",value:").append(value);
    }
}
