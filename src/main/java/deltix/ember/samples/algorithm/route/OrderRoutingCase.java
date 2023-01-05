package deltix.ember.samples.algorithm.route;

import deltix.anvil.util.AsciiStringBuilder;
import deltix.anvil.util.CharSequenceUtil;
import deltix.anvil.util.annotation.Alphanumeric;
import deltix.anvil.util.annotation.Optional;
import deltix.anvil.util.codec.AlphanumericCodec;
import com.epam.deltix.dfp.Decimal;
import com.epam.deltix.dfp.Decimal64Utils;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class OrderRoutingCase implements Comparable<OrderRoutingCase> {
    @Decimal
    private long quantity;

    @Alphanumeric
    private long destination;

    @Optional
    private List<CustomOrderAttribute> attributes;

    public OrderRoutingCase(){
    }

    public OrderRoutingCase(@Decimal long quantity, @Alphanumeric long destination) {
        this.quantity = quantity;
        this.destination = destination;
    }

    private static final Pattern TEXT_PATTERN = Pattern.compile("([0-9\\.]+)\\s*:\\s*(\\w+)\\s*\\((.*)\\)");


    /** quantity =&gt; destination ( tag=value, tag=value, .. ) */
    public OrderRoutingCase (String text) {
        Matcher m = TEXT_PATTERN.matcher(text);
        if (m.matches()) {
            String quantity = m.group(1);
            String destination = m.group(2);
            String tags = m.group(3);

            if (CharSequenceUtil.isEmptyOrNull(quantity))
                throw new IllegalArgumentException("Missing quantity");

            if (CharSequenceUtil.isEmptyOrNull(quantity))
                throw new IllegalArgumentException("Missing destination");

            this.quantity = Decimal64Utils.parse(quantity);
            this.destination = AlphanumericCodec.encode(destination);

            if ( ! CharSequenceUtil.isEmptyOrNull(tags)) {
                attributes = new ArrayList<>();
                for (String tag : tags.split("\\s*,\\s*")) {
                    String [] nameValue = tag.split("=");
                    attributes.add(new CustomOrderAttribute(Integer.valueOf(nameValue[0]), nameValue[1]));
                }
            }
        } else {
            throw new IllegalArgumentException("Can't parse CaseTable item: " + text);
        }
    }


    @Decimal
    public long getQuantity() {
        return quantity;
    }

    public void setQuantity(@Decimal long quantity) {
        this.quantity = quantity;
    }

    @Alphanumeric
    public long getDestination() {
        return destination;
    }

    public void setDestination(@Alphanumeric long destination) {
        this.destination = destination;
    }

    public List<CustomOrderAttribute> getAttributes() {
        return attributes;
    }

    public void setAttributes(List<CustomOrderAttribute> attributes) {
        this.attributes = attributes;
    }

    @Override
    public int compareTo(@Nonnull OrderRoutingCase other) {
        return Decimal64Utils.compareTo(this.quantity, other.quantity);
    }

    @Override
    public String toString () {
        AsciiStringBuilder builder = new AsciiStringBuilder();
        builder.append(Decimal64Utils.toString(quantity));
        builder.append(" : ");
        AlphanumericCodec.decode(destination, builder);
        builder.append(" (");
        if (attributes != null) {
            boolean needComma = false;
            for (CustomOrderAttribute attribute : attributes) {
                if (needComma)
                    builder.append(", ");
                else
                    needComma = true;

                builder.append(attribute.getKey());
                builder.append('=');
                builder.append(attribute.getValue());
            }
        }
        builder.append(')');
        return builder.toString();
    }

}
