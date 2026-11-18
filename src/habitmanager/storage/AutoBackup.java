package habitmanager.storage;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Keeps a daily safety copy of the data file in a "backups" folder next to it,
 * e.g. backups/habits-2024-03-15.txt. The copy is taken before the first change of each
 * day, so it holds the data as it was at the start of that day. Only the newest
 * {@link #KEEP} copies are kept.
 */
public class AutoBackup {

    public static final int KEEP = 14;
    private static final String PREFIX = "habits-";
    private static final String SUFFIX = ".txt";

    private final Path dataFile;
    private final Path folder;

    public AutoBackup(Path dataFile) {
        this.dataFile = dataFile.toAbsolutePath();
        this.folder = this.dataFile.resolveSibling("backups");
    }

    public Path getFolder() {
        return folder;
    }

    /** Copies the data file for this date if that has not happened yet, then prunes old copies. */
    public void backUpIfNeeded(LocalDate today) throws IOException {
        if (!Files.exists(dataFile)) {
            return;
        }
        Path target = folder.resolve(PREFIX + today + SUFFIX);
        if (Files.exists(target)) {
            return;
        }
        Files.createDirectories(folder);
        Files.copy(dataFile, target, StandardCopyOption.REPLACE_EXISTING);
        prune();
    }

    /** Backup files, newest first. ISO dates sort correctly as text. */
    public List<Path> list() throws IOException {
        List<Path> backups = new ArrayList<>();
        if (!Files.isDirectory(folder)) {
            return backups;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, PREFIX + "*" + SUFFIX)) {
            stream.forEach(backups::add);
        }
        backups.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        return backups;
    }

    private void prune() throws IOException {
        List<Path> backups = list();
        for (int i = KEEP; i < backups.size(); i++) {
            Files.deleteIfExists(backups.get(i));
        }
    }
}
