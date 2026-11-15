package habitmanager;

import habitmanager.service.HabitManager;
import habitmanager.storage.FileStorage;
import habitmanager.ui.ConsoleUI;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.FileDescriptor;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;

/** Entry point: wires the storage, service and UI together and starts the menu. */
public class Main {

    private static final String DATA_FILE = "habits.txt";

    public static void main(String[] args) {
        // UTF-8 output so the check / cross symbols print correctly (on Windows run "chcp 65001" first).
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));

        ConsoleUI ui = new ConsoleUI(
                new HabitManager(),
                new FileStorage(Path.of(DATA_FILE)),
                Clock.systemDefaultZone(),
                in,
                out);
        ui.run();
    }
}
