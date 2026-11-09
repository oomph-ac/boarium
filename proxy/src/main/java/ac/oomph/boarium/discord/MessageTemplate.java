package ac.oomph.boarium.discord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A message from the config, with placeholders such as {player} that each post fills in.
 */
public record MessageTemplate(String text) {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");
    // Every ASCII punctuation character, which Discord shows as written after a backslash, so that a player's name or Boar's text cannot turn the rest of the message bold or into a link.
    private static final Pattern FORMATTING = Pattern.compile("\\p{Punct}");

    /**
     * @throws IllegalArgumentException if the text has a placeholder that is not in {@code placeholders}, naming each
     */
    public static MessageTemplate parse(String text, List<String> placeholders) {
        List<String> unknown = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            if (!placeholders.contains(matcher.group(1)) && !unknown.contains(matcher.group())) {
                unknown.add(matcher.group());
            }
        }
        if (!unknown.isEmpty()) {
            String takes = placeholders.isEmpty() ? "it takes none" : "it takes {" + String.join("}, {", placeholders) + "}";
            throw new IllegalArgumentException(String.join(", ", unknown) + (unknown.size() > 1 ? " are not placeholders" : " is not a placeholder") + " of this message; " + takes);
        }
        return new MessageTemplate(text);
    }

    /**
     * The text with each placeholder replaced by its value, shown as written rather than as Discord formatting, or by nothing when it has no value.
     */
    public String fill(Map<String, String> values) {
        return PLACEHOLDER.matcher(this.text).replaceAll(match -> Matcher.quoteReplacement(FORMATTING.matcher(values.getOrDefault(match.group(1), "")).replaceAll("\\\\$0"))).strip();
    }
}
