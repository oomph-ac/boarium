package ac.oomph.boarium.discord;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MessageTemplateTest {

    @Test
    void fillsInEachPlaceholder() {
        MessageTemplate template = MessageTemplate.parse("**{player}** failed {check} x{vl}, {check} again", List.of("player", "check", "vl"));
        assertEquals("**Steve** failed Timer x3, Timer again", template.fill(Map.of("player", "Steve", "check", "Timer", "vl", "3")));
    }

    @Test
    void showsValuesAsWrittenRatherThanAsFormatting() {
        MessageTemplate template = MessageTemplate.parse("**{player}**: {details}", List.of("player", "details"));
        assertEquals("**bad\\_\\*name\\***: \\> \\`code\\` \\[link\\]\\(x\\) \\# \\~\\~ \\|\\| \\\\ \\-\\# \\<\\@1\\> \\:x\\: é", template.fill(Map.of("player", "bad_*name*", "details", "> `code` [link](x) # ~~ || \\ -# <@1> :x: é")));
    }

    @Test
    void leavesAPlaceholderWithoutAValueEmptyAndTrimsTheEnds() {
        MessageTemplate template = MessageTemplate.parse("{player} failed {check} {details} {more}", List.of("player", "check", "details", "more"));
        assertEquals("Steve failed Timer", template.fill(Map.of("player", "Steve", "check", "Timer", "details", "", "more", "")));
    }

    @Test
    void leavesOtherBracesAlone() {
        MessageTemplate template = MessageTemplate.parse("{ player } {Player} {} {player", List.of("player"));
        assertEquals("{ player } {Player} {} {player", template.fill(Map.of("player", "Steve")));
    }

    @Test
    void namesEachUnknownPlaceholder() {
        assertEquals("{server} is not a placeholder of this message; it takes {player}", assertThrows(IllegalArgumentException.class, () -> MessageTemplate.parse("{server} {player}", List.of("player"))).getMessage());
        assertEquals("{a}, {b} are not placeholders of this message; it takes none", assertThrows(IllegalArgumentException.class, () -> MessageTemplate.parse("{a}{b}{a}", List.of())).getMessage());
    }

    @Test
    void takesEachDefaultMessage() {
        for (DiscordEvent event : DiscordEvent.values()) {
            assertEquals(event.defaultMessage(), MessageTemplate.parse(event.defaultMessage(), event.placeholders()).text());
        }
    }
}
