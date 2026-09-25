import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/** Prints a VBSP lump directory both ways (standard and L4D2 order) plus a few size checks. */
public class LumpDump {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Path.of(args[0]));
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        System.out.println("version " + b.getInt(4) + ", size " + data.length);
        int[] sizes = {0, 20, 32, 12, 0, 32, 72, 56, 0, 0, 32, 0, 4, 4, 48, 0, 0, 2, 12, 8, 0, 0, 0, 0, 0, 0, 176, 0, 0, 0, 0, 0, 0, 20};
        for (int i = 0; i < 64; i++) {
            int a = b.getInt(8 + i * 16), c = b.getInt(12 + i * 16), d = b.getInt(16 + i * 16), e = b.getInt(20 + i * 16);
            if (a == 0 && c == 0 && d == 0) continue;
            String head = "";
            int ofs = a;
            if (ofs > 0 && ofs + 4 < data.length) head = new String(data, ofs, 4, java.nio.charset.StandardCharsets.ISO_8859_1).replaceAll("[^\\x20-\\x7e]", ".");
            String div = "";
            if (i < sizes.length && sizes[i] > 0) div = " len%" + sizes[i] + "=" + (c % sizes[i]);
            System.out.printf("%2d a=%d b=%d c=%d fourCC=%d head='%s'%s%n", i, a, c, d, e, head, div);
        }
        System.out.println("entities start: " + new String(data, b.getInt(8), Math.min(300, b.getInt(12)), java.nio.charset.StandardCharsets.ISO_8859_1).replace("\n", " "));
    }
}
