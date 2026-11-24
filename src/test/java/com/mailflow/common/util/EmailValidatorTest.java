package com.mailflow.common.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The one normalisation rule for addresses: trim (including the characters Java's
 * trim()/strip() miss), then lower-case with Locale.ROOT, then validate.
 */
class EmailValidatorTest {

    /** The characters String.trim() and String.strip() leave in place, which the rule must remove. */
    private static final char[] INVISIBLE = {'\u00A0', '\u2007', '\u202F', '\u200B', '\u200C', '\u200D', '\u2060', '\uFEFF'};

    @ParameterizedTest(name = "U+{0}")
    @ValueSource(strings = {"00A0", "2007", "202F", "200B", "200C", "200D", "2060", "FEFF"})
    void each_invisible_character_is_trimmed_from_both_ends(String hex) {
        char c = (char) Integer.parseInt(hex, 16);
        // Java's own trimming leaves the character in place ...
        assertThat((c + "ada@test.local").trim()).isNotEqualTo("ada@test.local");
        assertThat((c + "ada@test.local").strip()).isNotEqualTo("ada@test.local");

        // ... the rule removes it at the start, at the end, repeated, and mixed with whitespace.
        for (String raw : new String[] {
                c + "ada@test.local",
                "ada@test.local" + c,
                "" + c + c + "Ada@Test.Local" + c,
                " \t" + c + " ADA@TEST.LOCAL \n" + c + "\r\n"}) {
            assertThat(EmailValidator.normalize(raw)).as("normalize(%s)", escape(raw)).isEqualTo("ada@test.local");
            assertThat(EmailValidator.isValid(raw)).as("isValid(%s)", escape(raw)).isTrue();
        }
    }

    @Test
    void the_trimmed_characters_are_exactly_what_trim_and_strip_remove_plus_the_invisible_ones() {
        Set<Character> extra = new HashSet<>();
        for (char c : INVISIBLE) extra.add(c);

        for (int i = 0; i <= 0xFFFF; i++) {
            char c = (char) i;
            boolean javaTrims = c <= ' ' || Character.isWhitespace(c);   // trim() and strip()
            boolean unicodeSpace = Character.isSpaceChar(c);             // Zs, Zl, Zp
            boolean expected = javaTrims || unicodeSpace || extra.contains(c);
            assertThat(EmailValidator.isTrimmable(c)).as("U+%04X", i).isEqualTo(expected);
        }
    }

    @Test
    void characters_inside_an_address_are_kept_so_it_stays_invalid() {
        assertThat(EmailValidator.normalize("ada\u200B@test.local")).isEqualTo("ada\u200B@test.local");
        assertThat(EmailValidator.isValid("ada\u200B@test.local")).isFalse();
        assertThat(EmailValidator.isValid("ada@test\u00A0.local")).isFalse();
        assertThat(EmailValidator.isValid("ada lovelace@test.local")).isFalse();
    }

    @Test
    void lower_cases_with_the_root_locale_whatever_the_default_locale() {
        Locale previous = Locale.getDefault();
        try {
            // In Turkish "I".toLowerCase() is a dotless ı, which would break matching.
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(EmailValidator.normalize("ADMIN@TEST.LOCAL")).isEqualTo("admin@test.local");
            assertThat(EmailValidator.isValid("ADMIN@TEST.LOCAL")).isTrue();
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void null_blank_and_invisible_only_values_are_not_valid() {
        assertThat(EmailValidator.normalize(null)).isNull();
        assertThat(EmailValidator.trim(null)).isNull();
        assertThat(EmailValidator.isValid(null)).isFalse();
        assertThat(EmailValidator.normalize(" \u200B\uFEFF ")).isEmpty();
        assertThat(EmailValidator.isValid(" \u200B\uFEFF ")).isFalse();
        assertThat(EmailValidator.isValid("not-an-email")).isFalse();
        assertThat(EmailValidator.isValid("a@b")).isFalse();
        assertThat(EmailValidator.isValid("a.b+c_d%e-f@sub.example.co")).isTrue();
    }

    @Test
    void trim_keeps_the_case() {
        assertThat(EmailValidator.trim("\u00A0Ada@Test.Local\uFEFF")).isEqualTo("Ada@Test.Local");
    }

    @Test
    void cells_with_a_display_name_or_mailto_give_the_address() {
        assertThat(EmailValidator.extractAddress("Ada Lovelace <Ada@Test.Local>")).isEqualTo("Ada@Test.Local");
        assertThat(EmailValidator.extractAddress("\"Lovelace, Ada\" <ada@test.local>")).isEqualTo("ada@test.local");
        assertThat(EmailValidator.extractAddress("<ada@test.local>")).isEqualTo("ada@test.local");
        assertThat(EmailValidator.extractAddress("\u00A0Ada <\u200Bada@test.local > ")).isEqualTo("ada@test.local");
        assertThat(EmailValidator.extractAddress("mailto:ada@test.local")).isEqualTo("ada@test.local");
        assertThat(EmailValidator.extractAddress("MAILTO:Ada@Test.Local?subject=Hi")).isEqualTo("Ada@Test.Local");
        assertThat(EmailValidator.extractAddress("Ada <mailto:ada@test.local>")).isEqualTo("ada@test.local");
        assertThat(EmailValidator.extractAddress("  ada@test.local ")).isEqualTo("ada@test.local");
        assertThat(EmailValidator.extractAddress(null)).isNull();
        // Anything else is left as it is (and then fails validation).
        assertThat(EmailValidator.extractAddress("Ada <ada@test.local> extra")).isEqualTo("Ada <ada@test.local> extra");
        assertThat(EmailValidator.isValid(EmailValidator.extractAddress("Ada <ada@test.local> extra"))).isFalse();
    }

    @Test
    void extracting_from_a_long_untrusted_cell_stays_fast() {
        // A long run of spaces inside a cell must not make the display-name pattern backtrack.
        String cell = "a" + " ".repeat(200_000) + "b";
        String quoted = "\"" + " ".repeat(200_000) + "x";
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            assertThat(EmailValidator.extractAddress(cell)).isEqualTo(cell);
            assertThat(EmailValidator.extractAddress(quoted)).isEqualTo(quoted);
        });
    }

    @Test
    void the_frontend_uses_the_same_pattern_and_the_same_trimmed_characters() throws IOException {
        Path source = Path.of("frontend", "src", "lib", "recipientSelection.ts");
        assumeTrue(Files.exists(source), "frontend sources are not checked out next to the backend");
        String ts = Files.readString(source, StandardCharsets.UTF_8);

        Matcher regex = Pattern.compile("const EMAIL_RE = /(.+)/;").matcher(ts);
        assertThat(regex.find()).as("EMAIL_RE in %s", source).isTrue();
        assertThat(regex.group(1)).isEqualTo(EmailValidator.PATTERN_SOURCE);

        int start = ts.indexOf("function isEdgeChar(code: number): boolean {");
        assertThat(start).as("isEdgeChar in %s", source).isNotNegative();
        String body = ts.substring(start, ts.indexOf("\n}", start));
        Set<Integer> frontend = new HashSet<>();
        Matcher range = Pattern.compile("\\(code >= 0x([0-9a-fA-F]+) && code <= 0x([0-9a-fA-F]+)\\)").matcher(body);
        while (range.find()) {
            for (int i = Integer.parseInt(range.group(1), 16); i <= Integer.parseInt(range.group(2), 16); i++) frontend.add(i);
        }
        String rest = range.replaceAll("");
        Matcher upTo = Pattern.compile("code <= 0x([0-9a-fA-F]+)").matcher(rest);
        while (upTo.find()) {
            for (int i = 0; i <= Integer.parseInt(upTo.group(1), 16); i++) frontend.add(i);
        }
        Matcher single = Pattern.compile("code === 0x([0-9a-fA-F]+)").matcher(rest);
        while (single.find()) frontend.add(Integer.parseInt(single.group(1), 16));

        for (int i = 0; i <= 0xFFFF; i++) {
            assertThat(frontend.contains(i)).as("U+%04X", i).isEqualTo(EmailValidator.isTrimmable((char) i));
        }
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c < 0x20 || c > 0x7E) sb.append(String.format("\\u%04X", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }
}
