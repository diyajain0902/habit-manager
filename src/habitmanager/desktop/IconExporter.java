package habitmanager.desktop;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the bubble mascot as a Windows .ico file (used as the app icon by the packaging
 * script). Usage: java -cp out habitmanager.desktop.IconExporter build/bubble.ico
 *
 * An .ico is a small directory of images; each entry here is a PNG at one size.
 */
public final class IconExporter {

    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};

    private IconExporter() {
    }

    public static void main(String[] args) throws IOException {
        Path target = Path.of(args.length > 0 ? args[0] : "bubble.ico");
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream out = Files.newOutputStream(target)) {
            writeIco(out);
        }
        System.out.println("Wrote " + target);
    }

    public static void writeIco(OutputStream out) throws IOException {
        List<byte[]> images = new ArrayList<>();
        for (int size : SIZES) {
            images.add(png(BubbleIcon.create(size)));
        }

        int headerSize = 6 + 16 * SIZES.length;
        ByteBuffer header = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN);
        header.putShort((short) 0);            // reserved
        header.putShort((short) 1);            // type: icon
        header.putShort((short) SIZES.length); // number of images
        int offset = headerSize;
        for (int i = 0; i < SIZES.length; i++) {
            int size = SIZES[i];
            header.put((byte) (size >= 256 ? 0 : size)); // 0 means 256
            header.put((byte) (size >= 256 ? 0 : size));
            header.put((byte) 0);                          // no palette
            header.put((byte) 0);                          // reserved
            header.putShort((short) 1);                    // colour planes
            header.putShort((short) 32);                   // bits per pixel
            header.putInt(images.get(i).length);
            header.putInt(offset);
            offset += images.get(i).length;
        }
        out.write(header.array());
        for (byte[] image : images) {
            out.write(image);
        }
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }
}
