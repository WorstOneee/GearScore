package br.com.serpens.gearscore.storage;

import br.com.serpens.gearscore.model.PlayerRecord;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Small file holding the writes that were still unconfirmed, so they survive a restart.
 *
 * It is always rewritten as a whole from the queue's current content (which is what keeps
 * it compact) through a temporary file and an atomic rename, and it never holds more than
 * the queue's own limit. It is only touched from the database writer thread.
 *
 * A record read back from here may be older than what the database already has; the upsert
 * ignores rows older than the stored one, so replaying it is harmless.
 */
public final class PendingSpool {

    private static final String HEADER = "GEARSCORE-PENDING\t1";

    private final Path file;
    private final Path temporary;

    public PendingSpool(Path file) {
        this.file = file;
        this.temporary = file.resolveSibling(file.getFileName() + ".tmp");
    }

    public boolean exists() {
        return Files.exists(file);
    }

    public void save(Collection<PlayerRecord> records) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            out.write(HEADER);
            out.newLine();
            for (PlayerRecord record : records) {
                out.write(record.uuid().toString());
                out.write('\t');
                out.write(clean(record.name()));
                out.write('\t');
                out.write(Integer.toString(record.score()));
                out.write('\t');
                out.write(Integer.toString(record.armorScore()));
                out.write('\t');
                out.write(Integer.toString(record.weaponScore()));
                out.write('\t');
                out.write(clean(record.tier()));
                out.write('\t');
                out.write(clean(record.server()));
                out.write('\t');
                out.write(Integer.toString(record.rulesVersion()));
                out.write('\t');
                out.write(Long.toString(record.updatedAt()));
                out.newLine();
            }
        }
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * @param limit maximum number of records to read
     * @return the records in the file; unreadable lines are skipped
     */
    public List<PlayerRecord> load(int limit) throws IOException {
        List<PlayerRecord> records = new ArrayList<>();
        if (!Files.exists(file)) {
            return records;
        }
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line = in.readLine();
            if (line == null || !line.equals(HEADER)) {
                return records;
            }
            while (records.size() < limit && (line = in.readLine()) != null) {
                String[] parts = line.split("\t", -1);
                if (parts.length != 9) {
                    continue;
                }
                try {
                    records.add(new PlayerRecord(UUID.fromString(parts[0]), parts[1], Integer.parseInt(parts[2]),
                            Integer.parseInt(parts[3]), Integer.parseInt(parts[4]), parts[5], parts[6],
                            Integer.parseInt(parts[7]), Long.parseLong(parts[8])));
                } catch (IllegalArgumentException ignored) {
                    // damaged line: the player is evaluated again on their next join
                }
            }
        }
        return records;
    }

    public void delete() throws IOException {
        Files.deleteIfExists(file);
        Files.deleteIfExists(temporary);
    }

    private static String clean(String value) {
        return value.indexOf('\t') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0
                ? value
                : value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
