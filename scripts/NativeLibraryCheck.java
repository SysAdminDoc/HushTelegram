import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Compare native ZIP names, decompressed SHA-256 and compression without changing either APK.
 * ELF32 and ELF64 headers and LOAD ranges are checked in their declared byte order. Only
 * arm64-v8a and x86_64 LOAD segments require at least 16 KB alignment; compressed entries pass.
 * GNU_RELRO segments are recorded for every ABI, and on those two must not share a 16 KB page
 * with writable or executable LOAD memory (see {@link #checkRelroPages}).
 *
 * <p>Usage: {@code java NativeLibraryCheck.java <stock.apk> <patched.apk> [report.json]}.
 * Stdout and the optional report contain the same deterministic JSON. Exit 0 means all native
 * invariants passed, 1 means invalid APK/native facts, and 2 means usage or report-write failure.
 * Run {@code zipalign -c -P 16 -v 4 <patched.apk>} separately and record its successful verdict.
 * These packaging checks do not prove that the app boots on a 16 KB device.
 */
public final class NativeLibraryCheck {
    private static final BigInteger ALIGNMENT = BigInteger.valueOf(16384);
    private static final BigInteger ADDRESS_SPACE = BigInteger.ONE.shiftLeft(64);
    private static final long PT_GNU_RELRO = 0x6474e552L;
    private static final long PF_X = 1;
    private static final long PF_W = 2;
    private final TreeSet<String> failures = new TreeSet<>();

    public static void main(String[] args) throws IOException {
        if (args.length < 2 || args.length > 3) {
            System.err.println("usage: NativeLibraryCheck <stock.apk> <patched.apk> [report.json]");
            System.exit(2);
        }
        NativeLibraryCheck check = new NativeLibraryCheck();
        Map<String, Map<String, Object>> stock = check.readApk(Path.of(args[0]), "stock");
        Map<String, Map<String, Object>> patched = check.readApk(Path.of(args[1]), "patched");
        check.compare(stock, patched);
        String report = json(object("schemaVersion", 1, "passed", check.failures.isEmpty(),
                "required64BitLoadAlignmentBytes", 16384,
                "stock", object("nativeEntryCount", stock.size(), "entries", stock.values()),
                "patched", object("nativeEntryCount", patched.size(), "entries", patched.values()),
                "failures", check.failures)) + "\n";
        if (args.length == 3) {
            try {
                Files.writeString(Path.of(args[2]), report, StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.err.println("native report could not be written: " + e.getMessage());
                System.exit(2);
            }
        }
        System.out.print(report);
        System.exit(check.failures.isEmpty() ? 0 : 1);
    }

    private Map<String, Map<String, Object>> readApk(Path path, String side) {
        Map<String, Map<String, Object>> libraries = new TreeMap<>();
        try (ZipFile zip = new ZipFile(path.toFile())) {
            Map<String, ZipEntry> entries = new TreeMap<>();
            var enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith("lib/") || !name.endsWith(".so")) continue;
                if (entries.putIfAbsent(name, entry) != null) failures.add(side + " " + name + ": duplicate native ZIP entry");
            }
            for (ZipEntry entry : entries.values()) {
                String name = entry.getName();
                String[] parts = name.split("/", -1);
                String abi = parts.length == 3 ? parts[1] : "";
                Map<String, Object> facts = object("name", name, "abi", abi, "size", entry.getSize(),
                        "sha256", null, "compression", compression(entry.getMethod()),
                        "compressionMethod", entry.getMethod(), "elf", null);
                libraries.put(name, facts);
                try {
                    if (abi.isEmpty() || abi.equals(".") || abi.equals("..") || parts[2].equals(".so")) {
                        throw new IOException("invalid native ZIP path");
                    }
                    facts.put("sha256", hash(zip, entry));
                    facts.put("elf", elf(zip, entry, abi));
                } catch (IOException e) {
                    failures.add(side + " " + name + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            failures.add(side + " APK: cannot read ZIP archive (" + e.getClass().getSimpleName() + ")");
        }
        return libraries;
    }

    private void compare(Map<String, Map<String, Object>> stock, Map<String, Map<String, Object>> patched) {
        for (var library : stock.entrySet()) {
            Map<String, Object> other = patched.get(library.getKey());
            if (other == null) {
                failures.add("patched " + library.getKey() + ": native entry is missing");
                continue;
            }
            if (!library.getValue().get("size").equals(other.get("size"))
                    || !java.util.Objects.equals(library.getValue().get("sha256"), other.get("sha256"))) {
                failures.add("patched " + library.getKey() + ": native bytes differ from stock (size or SHA-256)");
            }
            if (!library.getValue().get("compressionMethod").equals(other.get("compressionMethod"))) {
                failures.add("patched " + library.getKey() + ": native compression differs from stock");
            }
        }
        for (String name : patched.keySet()) {
            if (!stock.containsKey(name)) failures.add("patched " + name + ": native entry is absent from stock");
        }
    }

    private static String compression(int method) {
        return switch (method) {
            case ZipEntry.STORED -> "STORE";
            case ZipEntry.DEFLATED -> "DEFLATE";
            default -> "METHOD_" + method;
        };
    }

    private static String hash(ZipFile zip, ZipEntry entry) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("the JDK has no SHA-256", e);
        }
        CRC32 crc = new CRC32();
        long size = 0;
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] buffer = new byte[65536];
            for (int length; (length = in.read(buffer)) != -1;) {
                size += length;
                digest.update(buffer, 0, length);
                crc.update(buffer, 0, length);
            }
        }
        if (size != entry.getSize()) throw new IOException("native ZIP entry size does not match its bytes");
        if (crc.getValue() != entry.getCrc()) throw new IOException("native ZIP entry CRC does not match its bytes");
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Map<String, Object> elf(ZipFile zip, ZipEntry entry, String abi) throws IOException {
        byte[] ident = read(zip, entry, 0, 16, "ELF identification");
        if (ident[0] != 0x7f || ident[1] != 'E' || ident[2] != 'L' || ident[3] != 'F') {
            throw new IOException("invalid ELF magic");
        }
        int elfClass = ident[4] & 0xff;
        int data = ident[5] & 0xff;
        if (elfClass != 1 && elfClass != 2) throw new IOException("invalid ELF class");
        if (data != 1 && data != 2) throw new IOException("invalid ELF byte order");
        if (ident[6] != 1) throw new IOException("invalid ELF identification version");
        boolean is64 = elfClass == 2;
        ByteOrder order = data == 1 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
        int headerSize = is64 ? 64 : 52;
        ByteBuffer header = ByteBuffer.wrap(read(zip, entry, 0, headerSize, "ELF header")).order(order);
        if (u16(header, 16) != 3) throw new IOException("ELF is not a shared object (ET_DYN)");
        if (u32(header, 20) != 1) throw new IOException("invalid ELF header version");
        int machine = u16(header, 18);
        int expectedMachine = switch (abi) {
            case "arm64-v8a" -> 183;
            case "x86_64" -> 62;
            case "armeabi", "armeabi-v7a" -> 40;
            case "x86" -> 3;
            default -> 0;
        };
        boolean alignmentRequired = abi.equals("arm64-v8a") || abi.equals("x86_64");
        if (expectedMachine != 0 && (machine != expectedMachine || is64 != alignmentRequired)) {
            throw new IOException("ELF class or machine does not match ABI " + abi);
        }
        int metadata = is64 ? 52 : 40;
        if (u16(header, metadata) != headerSize) throw new IOException("invalid ELF header size");
        long phOffset = offset(header, is64 ? 32 : 28, is64, "ELF program-header offset");
        int phEntrySize = u16(header, metadata + 2);
        long phCount = u16(header, metadata + 4);
        long shOffset = offset(header, is64 ? 40 : 32, is64, "ELF section-header offset");
        int shEntrySize = u16(header, metadata + 6);
        long shCount = u16(header, metadata + 8);
        long stringSection = u16(header, metadata + 10);
        if (shOffset == 0) {
            if (shCount != 0 || stringSection != 0 || phCount == 0xffff) {
                throw new IOException("ELF section-header table is missing");
            }
        } else {
            if (shOffset < headerSize || shEntrySize != (is64 ? 64 : 40)) {
                throw new IOException("invalid ELF section-header size or offset");
            }
            ByteBuffer firstSection = ByteBuffer.wrap(read(zip, entry, shOffset, shEntrySize,
                    "ELF section-header table")).order(order);
            if (u32(firstSection, 4) != 0) throw new IOException("invalid ELF null section");
            if (shCount == 0) shCount = offset(firstSection, is64 ? 32 : 20, is64, "ELF extended section count");
            if (shCount == 0) throw new IOException("ELF section-header table is empty");
            range(shOffset, shCount, shEntrySize, entry.getSize(), "ELF section-header table");
            if (phCount == 0xffff) phCount = u32(firstSection, is64 ? 44 : 28);
            if (stringSection == 0xffff) stringSection = u32(firstSection, is64 ? 40 : 24);
            if (stringSection >= shCount) throw new IOException("ELF string section is outside its section table");
        }
        if (phOffset < headerSize || phEntrySize != (is64 ? 56 : 32) || phCount == 0) {
            throw new IOException("invalid ELF program-header size, offset or count");
        }
        range(phOffset, phCount, phEntrySize, entry.getSize(), "ELF program-header table");
        List<Map<String, Object>> loads = new ArrayList<>();
        List<Span> loadSpans = new ArrayList<>();
        List<Span> relros = new ArrayList<>();
        BigInteger addressSpace = is64 ? ADDRESS_SPACE : BigInteger.ONE.shiftLeft(32);
        BigInteger previousAddress = BigInteger.valueOf(-1);
        try (InputStream in = zip.getInputStream(entry)) {
            in.skipNBytes(phOffset);
            for (long index = 0; index < phCount; index++) {
                byte[] bytes = in.readNBytes(phEntrySize);
                if (bytes.length != phEntrySize) throw new IOException("truncated ELF program-header table");
                ByteBuffer ph = ByteBuffer.wrap(bytes).order(order);
                long type = u32(ph, 0);
                if (type == 0) continue; // PT_NULL fields are undefined.
                long fileOffset = offset(ph, is64 ? 8 : 4, is64, "ELF segment file offset");
                long fileSize = offset(ph, is64 ? 32 : 16, is64, "ELF segment file size");
                range(fileOffset, fileSize, 1, entry.getSize(), "ELF segment[" + index + "] file range");
                if (type == PT_GNU_RELRO) {
                    BigInteger address = unsigned(ph, is64 ? 16 : 8, is64);
                    BigInteger end = address.add(unsigned(ph, is64 ? 40 : 20, is64));
                    if (end.compareTo(addressSpace) > 0) throw new IOException("ELF GNU_RELRO[" + index + "] memory range overflows");
                    relros.add(new Span(index, address, end, 0));
                    continue;
                }
                if (type != 1) continue;
                BigInteger address = unsigned(ph, is64 ? 16 : 8, is64);
                BigInteger memorySize = unsigned(ph, is64 ? 40 : 20, is64);
                BigInteger alignment = unsigned(ph, is64 ? 48 : 28, is64);
                String label = "ELF LOAD[" + index + "]";
                if (memorySize.compareTo(BigInteger.valueOf(fileSize)) < 0) throw new IOException(label + " file size exceeds memory size");
                if (address.add(memorySize).compareTo(addressSpace) > 0) throw new IOException(label + " memory range overflows");
                if (address.compareTo(previousAddress) < 0) throw new IOException(label + " virtual addresses are out of order");
                previousAddress = address;
                if (alignment.signum() != 0 && alignment.bitCount() != 1) throw new IOException(label + " alignment is not a power of two");
                if (alignment.compareTo(BigInteger.ONE) > 0
                        && !address.mod(alignment).equals(BigInteger.valueOf(fileOffset).mod(alignment))) {
                    throw new IOException(label + " file offset and virtual address are not congruent to alignment");
                }
                if (alignmentRequired && alignment.compareTo(ALIGNMENT) < 0) {
                    throw new IOException(label + " alignment " + alignment + " is below 16384 bytes for " + abi);
                }
                loads.add(object("index", index, "offset", fileOffset, "virtualAddress", address,
                        "fileSize", fileSize, "memorySize", memorySize, "alignmentBytes", alignment));
                loadSpans.add(new Span(index, address, address.add(memorySize), u32(ph, is64 ? 4 : 24)));
            }
        }
        if (loads.isEmpty()) throw new IOException("ELF has no LOAD segments");
        List<Map<String, Object>> relroSegments = new ArrayList<>();
        for (Span relro : relros) {
            relroSegments.add(object("index", relro.index(), "virtualAddress", relro.start(),
                    "memorySize", relro.end().subtract(relro.start()), "endAddress", relro.end(),
                    "endsOn16KbBoundary", relro.end().mod(ALIGNMENT).signum() == 0));
            if (alignmentRequired) checkRelroPages(relro, loadSpans, abi);
        }
        return object("classBits", is64 ? 64 : 32, "machine", machine,
                "byteOrder", data == 1 ? "little" : "big",
                "requiredLoadAlignmentBytes", alignmentRequired ? 16384 : 0, "loadSegments", loads,
                "relroSegments", relroSegments);
    }

    /**
     * After relocation the loader makes GNU_RELRO read-only by whole pages, from the page holding
     * its start to the page holding its end. On a 16 KB device that can reach past the segment.
     * Google's guidance asks for (p_vaddr + p_memsz) % 16384 == 0, which {@code endsOn16KbBoundary}
     * records. What breaks is writable or executable LOAD memory in those pages losing W or X, so
     * that's what fails here. A RELRO ending mid-page with nothing else in the page still loads.
     */
    private static void checkRelroPages(Span relro, List<Span> loads, String abi) throws IOException {
        BigInteger pageStart = relro.start().subtract(relro.start().mod(ALIGNMENT));
        BigInteger pageEnd = relro.end().add(ALIGNMENT.subtract(BigInteger.ONE)).divide(ALIGNMENT).multiply(ALIGNMENT);
        for (Span load : loads) {
            if ((load.flags() & (PF_W | PF_X)) == 0) continue; // Already read-only, so nothing changes.
            BigInteger shared = firstShared(load.start(), load.end().min(relro.start()), pageStart, pageEnd);
            if (shared == null) shared = firstShared(load.start().max(relro.end()), load.end(), pageStart, pageEnd);
            if (shared != null) {
                throw new IOException("ELF GNU_RELRO[" + relro.index() + "] ends at 0x" + relro.end().toString(16)
                        + ", and its 16 KB pages also hold LOAD[" + load.index() + "] memory at 0x" + shared.toString(16)
                        + " that a 16 KB " + abi + " device would make read-only");
            }
        }
    }

    /** Where [start, end) first meets [from, to), or null when they don't meet. */
    private static BigInteger firstShared(BigInteger start, BigInteger end, BigInteger from, BigInteger to) {
        BigInteger first = start.max(from);
        return first.compareTo(end.min(to)) < 0 ? first : null;
    }

    /** A segment's memory, [start, end), with its program-header index and p_flags. */
    private record Span(long index, BigInteger start, BigInteger end, long flags) {}

    private static byte[] read(ZipFile zip, ZipEntry entry, long offset, int size, String label) throws IOException {
        range(offset, size, 1, entry.getSize(), label);
        try (InputStream in = zip.getInputStream(entry)) {
            in.skipNBytes(offset);
            byte[] bytes = in.readNBytes(size);
            if (bytes.length != size) throw new IOException("truncated " + label);
            return bytes;
        }
    }

    private static void range(long offset, long count, int unit, long size, String label) throws IOException {
        if (offset < 0 || count < 0 || offset > size || count > (size - offset) / unit) {
            throw new IOException(label + " is outside native entry (truncated ELF or invalid range)");
        }
    }

    private static int u16(ByteBuffer buffer, int offset) { return Short.toUnsignedInt(buffer.getShort(offset)); }
    private static long u32(ByteBuffer buffer, int offset) { return Integer.toUnsignedLong(buffer.getInt(offset)); }

    private static long offset(ByteBuffer buffer, int offset, boolean is64, String label) throws IOException {
        long value = is64 ? buffer.getLong(offset) : u32(buffer, offset);
        if (value < 0) throw new IOException(label + " exceeds the native entry's addressable size");
        return value;
    }

    private static BigInteger unsigned(ByteBuffer buffer, int offset, boolean is64) {
        return new BigInteger(is64 ? Long.toUnsignedString(buffer.getLong(offset)) : Long.toString(u32(buffer, offset)));
    }

    private static Map<String, Object> object(Object... fields) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < fields.length; index += 2) result.put((String) fields[index], fields[index + 1]);
        return result;
    }

    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof String text) {
            StringBuilder quoted = new StringBuilder("\"");
            for (int index = 0; index < text.length(); index++) {
                char character = text.charAt(index);
                if (character == '"' || character == '\\') quoted.append('\\').append(character);
                else if (character < 0x20) quoted.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) character));
                else quoted.append(character);
            }
            return quoted.append('"').toString();
        }
        List<String> items = new ArrayList<>();
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) items.add(json(entry.getKey()) + ":" + json(entry.getValue()));
            return "{" + String.join(",", items) + "}";
        }
        if (value instanceof Iterable<?> values) {
            for (Object item : values) items.add(json(item));
            return "[" + String.join(",", items) + "]";
        }
        throw new IllegalArgumentException("unsupported JSON value: " + value.getClass());
    }
}
