package com.bazi;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Port of the static Qi Men "useful god" symbol tables
 * ({@code MysticDoors.usefulGodSymbols}) behind the RN QiMenUsefulGodList /
 * QiMenUsefulGodDetails screens.
 *
 * <p>There is no chart math here: the RN screens simply list the topic keys
 * (sorted alphabetically) and show each table's {@code [symbol, meaning]}
 * rows. {@link #deCamel(String)} mirrors {@code lib/utils/strings.deCamel}
 * for the displayed topic titles.
 */
@Service
public class QimenUsefulGodsService {

    private static final Pattern WORD = Pattern.compile("\\w\\S*");

    private final ResourceJson resources;

    public QimenUsefulGodsService(ResourceJson resources) {
        this.resources = resources;
    }

    /** QiMenUsefulGodList: raw topic keys sorted, deCamel'ed titles. */
    public List<Map<String, Object>> topics() {
        JsonNode table = table();
        List<String> keys = new ArrayList<>();
        table.fieldNames().forEachRemaining(keys::add);
        keys.sort(Comparator.naturalOrder());

        List<Map<String, Object>> out = new ArrayList<>();
        for (String key : keys) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("topic", key);
            row.put("title", deCamel(key));
            out.add(row);
        }
        return out;
    }

    /** QiMenUsefulGodDetails: the selected table mapped to {title, body}. */
    public Map<String, Object> details(String topic) {
        JsonNode rows = table().get(topic);
        if (rows == null) {
            throw new IllegalArgumentException("unknown useful-god topic: " + topic);
        }
        List<Map<String, Object>> symbols = new ArrayList<>();
        for (JsonNode row : rows) {
            Map<String, Object> symbol = new LinkedHashMap<>();
            symbol.put("title", row.get(0).asText());
            symbol.put("body", row.get(1).asText());
            symbols.add(symbol);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("topic", topic);
        out.put("title", deCamel(topic));
        out.put("symbols", symbols);
        return out;
    }

    public int topicCount() {
        return table().size();
    }

    private JsonNode table() {
        JsonNode node = resources.load("qimen-data.json").get("usefulGodSymbols");
        if (node == null) {
            throw new IllegalStateException("qimen-data.json has no usefulGodSymbols");
        }
        return node;
    }

    /**
     * lib/utils/strings.deCamel port. JS {@code String.replace(string, ...)}
     * replaces only the first match, hence {@code replaceFirst} for the
     * underscore/double-space passes.
     */
    static String deCamel(String text) {
        if (text == null || text.isEmpty()) return "";
        if (text.indexOf(' ') >= 0) return titleEveryWord(text);
        return toTitle(text)
                .replaceAll("([A-Z])", " $1")
                .replaceFirst("_", " ")
                .replaceFirst("  ", " ")
                .trim();
    }

    /** strings.toTitle: upper-case the first character of every word. */
    private static String toTitle(String text) {
        Matcher m = WORD.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String word = m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    Character.toUpperCase(word.charAt(0)) + word.substring(1)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** strings.titleEveryWord: upper first + lower rest of every word. */
    private static String titleEveryWord(String text) {
        Matcher m = WORD.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String word = m.group();
            String replaced = Character.toUpperCase(word.charAt(0))
                    + word.substring(1).toLowerCase();
            m.appendReplacement(sb, Matcher.quoteReplacement(replaced));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
