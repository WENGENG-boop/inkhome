package ink.home;

import android.text.Html;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** MOBI (PalmDOC or uncompressed, no DRM): the text stream is split on page breaks. */
final class Mobi implements Content {
    private static final Pattern BREAK = Pattern.compile("<mbp:pagebreak\\s*/?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADING_START = Pattern.compile("<h[1-3]\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADING = Pattern.compile("<h[1-3][^>]*>(.*?)</h[1-3]>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern IMAGE = Pattern.compile("<img([^>]*?)recindex=[\"']?0*(\\d+)[\"']?([^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK = Pattern.compile("<a\\b[^>]*?filepos=[\"']?0*(\\d{1,9})[\"']?[^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final int MAX_CHAPTER = 150000;
    private static final int MIN_TOC_LINKS = 3;
    private static final int EXTH_AUTHOR = 100;
    private static final int EXTH_COVER = 201;
    private static final int EXTH_TITLE = 503;

    /** Parsed PDB and MOBI headers. */
    static final class Header {
        final ByteBuffer data;
        final int[] offsets;
        final int record0;
        final int compression;
        final int textRecords;
        final int encryption;
        final int extraFlags;
        final int firstImage;
        final Charset charset;
        String title = "";
        String author = "";
        int coverOffset = -1;

        Header(byte[] bytes) throws IOException {
            data = ByteBuffer.wrap(bytes);
            if (bytes.length < 86 || !"BOOKMOBI".equals(new String(bytes, 60, 8, StandardCharsets.ISO_8859_1))) {
                throw new IOException("不是 MOBI 文件");
            }
            int count = data.getShort(76) & 0xFFFF;
            offsets = new int[count + 1];
            for (int i = 0; i < count; i++) offsets[i] = data.getInt(78 + i * 8);
            offsets[count] = bytes.length;
            record0 = offsets[0];
            compression = data.getShort(record0) & 0xFFFF;
            textRecords = data.getShort(record0 + 8) & 0xFFFF;
            encryption = data.getShort(record0 + 12) & 0xFFFF;
            int length = data.getInt(record0 + 20);
            charset = data.getInt(record0 + 28) == 65001 ? StandardCharsets.UTF_8 : Charset.forName("windows-1252");
            firstImage = data.getInt(record0 + 108);
            extraFlags = length >= 0xE4 ? data.getShort(record0 + 0xF2) & 0xFFFF : 0;
            int nameOffset = data.getInt(record0 + 84);
            int nameLength = data.getInt(record0 + 88);
            title = new String(bytes, record0 + nameOffset, nameLength, charset);
            if ((data.getInt(record0 + 128) & 0x40) != 0) readExth(bytes, record0 + 16 + length);
        }

        private void readExth(byte[] bytes, int start) {
            int count = data.getInt(start + 8);
            int position = start + 12;
            for (int i = 0; i < count; i++) {
                int type = data.getInt(position);
                int size = data.getInt(position + 4);
                if (type == EXTH_AUTHOR) author = new String(bytes, position + 8, size - 8, charset);
                if (type == EXTH_TITLE) title = new String(bytes, position + 8, size - 8, charset);
                if (type == EXTH_COVER) coverOffset = data.getInt(position + 8);
                position += size;
            }
        }

        byte[] record(int index) {
            byte[] out = new byte[offsets[index + 1] - offsets[index]];
            System.arraycopy(data.array(), offsets[index], out, 0, out.length);
            return out;
        }

        byte[] cover() {
            if (coverOffset < 0 || firstImage + coverOffset >= offsets.length - 1) return null;
            return record(firstImage + coverOffset);
        }
    }

    private final File root;
    private final List<String> chapters = new ArrayList<>();
    private final List<Integer> starts = new ArrayList<>();
    private final List<TocEntry> toc = new ArrayList<>();
    private final Map<Integer, String> links = new TreeMap<>();

    Mobi(File file, File cacheDir) throws IOException {
        root = cacheDir;
        Header header = new Header(Files.readAllBytes(file.toPath()));
        if (header.encryption != 0) throw new IOException("这本 MOBI 有 DRM 加密");
        if (header.compression != 1 && header.compression != 2) throw new IOException("不支持的 MOBI 压缩方式");

        ByteArrayOutputStream text = new ByteArrayOutputStream();
        for (int i = 1; i <= header.textRecords; i++) {
            byte[] record = header.record(i);
            int size = record.length - trailing(record, header.extraFlags);
            if (header.compression == 2) {
                decompress(record, size, text);
            } else {
                text.write(record, 0, size);
            }
        }
        byte[] raw = text.toByteArray();
        // One char per byte, so match offsets line up with the byte offsets filepos links use.
        String bytes = new String(raw, StandardCharsets.ISO_8859_1);
        boolean paged = BREAK.matcher(bytes).find();
        Matcher breaks = (paged ? BREAK : HEADING_START).matcher(bytes);
        int start = 0;
        int body = 0;
        while (breaks.find()) {
            addPart(raw, start, body, breaks.start(), header);
            start = breaks.start();
            body = paged ? breaks.end() : breaks.start();
        }
        addPart(raw, start, body, raw.length, header);
        if (chapters.isEmpty()) chapters.add("<html><body></body></html>");
        if (links.size() >= MIN_TOC_LINKS) {
            toc.clear();
            for (Map.Entry<Integer, String> link : links.entrySet()) {
                toc.add(new TocEntry(link.getValue(), chapterAt(link.getKey())));
            }
        }
    }

    private void addPart(byte[] raw, int start, int body, int end, Header header) throws IOException {
        String part = new String(raw, body, end - body, header.charset);
        collectLinks(part);
        addChapter(replaceImages(part, header), start, header.charset);
    }

    /** Keeps the links of a contents page: one that is mostly links, unlike a page with footnotes. */
    private void collectLinks(String part) {
        Map<Integer, String> found = new HashMap<>();
        int linked = 0;
        Matcher matcher = LINK.matcher(part);
        while (matcher.find()) {
            String label = Html.fromHtml(matcher.group(2), Html.FROM_HTML_MODE_LEGACY).toString().trim();
            if (label.isEmpty()) continue;
            found.put(Integer.parseInt(matcher.group(1)), label);
            linked += label.length();
        }
        int visible = part.replaceAll("<[^>]+>", "").replaceAll("\\s+", "").length();
        if (found.size() >= MIN_TOC_LINKS && linked * 2 >= visible) links.putAll(found);
    }

    private int chapterAt(int position) {
        int chapter = 0;
        while (chapter + 1 < starts.size() && starts.get(chapter + 1) <= position) chapter++;
        return chapter;
    }

    private void addChapter(String part, int start, Charset charset) {
        String visible = part.replaceAll("(?is)<title>.*?</title>", "").replaceAll("<[^>]+>", "");
        if (visible.trim().isEmpty() && !part.contains("<img")) return;
        Matcher matcher = HEADING.matcher(part);
        String label = matcher.find() ? matcher.group(1).replaceAll("<[^>]+>", "").trim() : "";
        toc.add(new TocEntry(label.isEmpty() ? "第 " + (toc.size() + 1) + " 节" : label, chapters.size()));
        int from = 0;
        while (part.length() - from > MAX_CHAPTER) {
            int cut = part.indexOf("<p", from + MAX_CHAPTER);
            if (cut < 0) break;
            chapters.add(part.substring(from, cut));
            starts.add(start + part.substring(0, from).getBytes(charset).length);
            from = cut;
        }
        chapters.add(part.substring(from));
        starts.add(start + part.substring(0, from).getBytes(charset).length);
    }

    private String replaceImages(String source, Header header) throws IOException {
        Matcher matcher = IMAGE.matcher(source);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            int record = header.firstImage + Integer.parseInt(matcher.group(2)) - 1;
            String name = "";
            if (record > 0 && record < header.offsets.length - 1) name = writeImage(header.record(record), record);
            matcher.appendReplacement(out, Matcher.quoteReplacement("<img src=\"" + name + "\"" + matcher.group(1) + matcher.group(3) + ">"));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String writeImage(byte[] bytes, int record) throws IOException {
        String extension = (bytes[0] & 0xFF) == 0xFF ? ".jpg" : bytes[0] == 'G' ? ".gif" : ".png";
        File image = new File(root, "img" + record + extension);
        if (!image.exists()) Files.write(image.toPath(), bytes);
        return image.getName();
    }

    private static int trailing(byte[] record, int flags) {
        int size = record.length;
        int total = 0;
        for (int bits = flags >> 1; bits != 0; bits >>= 1) {
            if ((bits & 1) != 0) total += entrySize(record, size - total);
        }
        if ((flags & 1) != 0 && size - total > 0) total += (record[size - total - 1] & 3) + 1;
        return Math.min(total, size);
    }

    private static int entrySize(byte[] record, int end) {
        int result = 0;
        int shift = 0;
        for (int i = end - 1; i >= 0 && shift < 28; i--) {
            int value = record[i] & 0xFF;
            result |= (value & 0x7F) << shift;
            shift += 7;
            if ((value & 0x80) != 0) break;
        }
        return result;
    }

    /** PalmDOC LZ77 expansion; back-references stay within one record. */
    private static void decompress(byte[] in, int size, ByteArrayOutputStream target) {
        byte[] out = new byte[8192];
        int length = 0;
        for (int i = 0; i < size; ) {
            int c = in[i++] & 0xFF;
            if (length + 16 > out.length) out = java.util.Arrays.copyOf(out, out.length * 2);
            if (c >= 1 && c <= 8) {
                for (int k = 0; k < c && i < size; k++) out[length++] = in[i++];
            } else if (c < 0x80) {
                out[length++] = (byte) c;
            } else if (c >= 0xC0) {
                out[length++] = ' ';
                out[length++] = (byte) (c ^ 0x80);
            } else if (i < size) {
                int pair = (c << 8) | (in[i++] & 0xFF);
                int distance = (pair >> 3) & 0x7FF;
                int count = (pair & 7) + 3;
                for (int k = 0; k < count && distance > 0 && distance <= length; k++) {
                    out[length] = out[length - distance];
                    length++;
                }
            }
        }
        target.write(out, 0, length);
    }

    @Override
    public int chapters() {
        return chapters.size();
    }

    @Override
    public String html(int chapter) {
        return chapters.get(chapter);
    }

    @Override
    public File base(int chapter) {
        return new File(root, "chapter.html");
    }

    @Override
    public List<TocEntry> toc() {
        return toc;
    }
}
