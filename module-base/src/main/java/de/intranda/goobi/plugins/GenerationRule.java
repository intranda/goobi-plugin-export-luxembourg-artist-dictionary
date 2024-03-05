package de.intranda.goobi.plugins;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

import de.sub.goobi.helper.VariableReplacer;
import lombok.Data;

@Data
public class GenerationRule {

    private final String value;
    private final String numberFormat;

    public GenerationRule(String value) {
        this(value, null);
    }

    public GenerationRule(String value, String numberFormat) {
        this.value = value;
        this.numberFormat = numberFormat;
    }

    public String generate(VariableReplacer vr) {

        String v = vr == null ? this.value : vr.replace(this.value);
        if (StringUtils.isNotBlank(numberFormat)) {
            NumberFormat format = new DecimalFormat(numberFormat);
            Matcher matcher = Pattern.compile("\\d+").matcher(v);
            List<Group> groups = new ArrayList<>();
            while (matcher.find()) {
                groups.add(new Group(matcher.group(), matcher.start(), matcher.end()));
            }
            Collections.reverse(groups);
            for (Group group : groups) {
                Long number = Long.parseLong(group.content);
                v = v.substring(0, group.start) + format.format(number) + v.substring(group.end);
            }
        }
        return v;
    }

    private class Group {
        public int start;
        public int end;
        public String content;

        public Group(String content, int start, int end) {
            this.content = content;
            this.start = start;
            this.end = end;
        }
    }

}
