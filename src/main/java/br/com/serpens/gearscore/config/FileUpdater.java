package br.com.serpens.gearscore.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Brings a YAML file written by an older version up to date without touching what the
 * administrator changed.
 *
 * The file is treated as a sequence of top-level sections. A section that is still exactly
 * as an older version shipped it is replaced by the current default; a section that was
 * edited is kept as it is, comments and formatting included; sections that are new are
 * added. Works on text, so nothing is reformatted.
 */
public final class FileUpdater {

    public static final String VERSION_KEY = "file-version";

    private static final Pattern TOP_LEVEL_KEY = Pattern.compile("^([A-Za-z0-9_-]+):.*$");
    private static final Pattern VERSION_LINE =
            Pattern.compile("(?m)^" + VERSION_KEY + ":\\s*(\\d+)\\s*$");

    /** One top-level key with the comment lines that introduce it. */
    private record Section(String key, String lead, String body) {
    }

    /**
     * @param updated sections replaced by the new default because they were unmodified
     * @param kept    sections left as the administrator wrote them
     * @param added   sections that did not exist in the file
     */
    public record Result(String text, List<String> updated, List<String> kept, List<String> added) {
    }

    private FileUpdater() {
    }

    /** Version recorded in the file, or 1 when it predates the marker. */
    public static int versionOf(String text) {
        Matcher matcher = VERSION_LINE.matcher(text.replace("\r\n", "\n"));
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 1;
    }

    /**
     * @param existing file currently on disk
     * @param current  default shipped with this version
     * @param legacy   defaults shipped with earlier versions
     */
    public static Result merge(String existing, String current, List<String> legacy) {
        Map<String, Section> mine = parse(existing);
        Map<String, Section> fresh = parse(current);
        List<Map<String, Section>> old = new ArrayList<>();
        for (String text : legacy) {
            old.add(parse(text));
        }

        List<String> updated = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        List<String> added = new ArrayList<>();
        StringBuilder out = new StringBuilder();

        for (Section section : fresh.values()) {
            Section present = mine.get(section.key());
            if (present == null) {
                if (!section.key().isEmpty() && !section.key().equals(VERSION_KEY)) {
                    added.add(section.key());
                }
                append(out, section);
            } else if (section.key().equals(VERSION_KEY) || section.key().isEmpty()
                    || same(present, section)) {
                append(out, section);
            } else if (matchesAny(present, old)) {
                updated.add(section.key());
                append(out, section);
            } else {
                kept.add(section.key());
                append(out, present);
            }
        }
        // Sections that no longer exist: dropped if untouched, otherwise kept at the end.
        for (Section section : mine.values()) {
            if (fresh.containsKey(section.key()) || section.key().isEmpty()) {
                continue;
            }
            if (!matchesAny(section, old)) {
                kept.add(section.key());
                append(out, section);
            }
        }
        return new Result(out.toString(), updated, kept, added);
    }

    /** Adds one to a top-level integer value such as {@code rules-version: 3}. */
    public static String increment(String text, String key) {
        Matcher matcher = Pattern.compile("(?m)^(" + Pattern.quote(key) + ":\\s*)(\\d+)(\\s*)$").matcher(text);
        if (!matcher.find()) {
            return text;
        }
        int value = Integer.parseInt(matcher.group(2)) + 1;
        return text.substring(0, matcher.start()) + matcher.group(1) + value + matcher.group(3)
                + text.substring(matcher.end());
    }

    private static void append(StringBuilder out, Section section) {
        out.append(section.lead()).append(section.body());
    }

    private static boolean matchesAny(Section section, List<Map<String, Section>> versions) {
        for (Map<String, Section> version : versions) {
            Section other = version.get(section.key());
            if (other != null && same(section, other)) {
                return true;
            }
        }
        return false;
    }

    // Only the section itself is compared; the comment block above it may be reworded freely.
    private static boolean same(Section a, Section b) {
        return normalize(a.body()).equals(normalize(b.body()));
    }

    private static String normalize(String body) {
        StringBuilder out = new StringBuilder();
        for (String line : body.split("\n")) {
            String trimmed = line.stripTrailing();
            if (!trimmed.isEmpty()) {
                out.append(trimmed).append('\n');
            }
        }
        return out.toString();
    }

    /**
     * Splits a file into sections. Comment and blank lines at column zero that come right
     * before a key belong to that key; whatever follows the last key is stored under "".
     */
    private static Map<String, Section> parse(String text) {
        Map<String, Section> sections = new LinkedHashMap<>();
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        // split leaves one empty element after a final newline
        int count = lines.length > 0 && lines[lines.length - 1].isEmpty() ? lines.length - 1 : lines.length;

        StringBuilder lead = new StringBuilder();
        StringBuilder body = null;
        StringBuilder trailing = new StringBuilder();
        String key = null;

        for (int i = 0; i < count; i++) {
            String line = lines[i];
            Matcher matcher = TOP_LEVEL_KEY.matcher(line);
            if (matcher.matches()) {
                if (key != null) {
                    sections.put(key, new Section(key, lead.toString(), body.toString()));
                    lead = trailing;
                } else {
                    lead.append(trailing);
                }
                trailing = new StringBuilder();
                key = matcher.group(1);
                body = new StringBuilder(line).append('\n');
                continue;
            }
            boolean detached = line.isEmpty() || line.startsWith("#");
            if (key == null) {
                lead.append(line).append('\n');
            } else if (detached) {
                trailing.append(line).append('\n');
            } else {
                // An indented line: everything held back so far is inside the section.
                body.append(trailing).append(line).append('\n');
                trailing = new StringBuilder();
            }
        }
        if (key != null) {
            sections.put(key, new Section(key, lead.toString(), body.toString()));
            if (trailing.length() > 0) {
                sections.put("", new Section("", "", trailing.toString()));
            }
        } else if (lead.length() > 0) {
            sections.put("", new Section("", "", lead.toString()));
        }
        return sections;
    }
}
