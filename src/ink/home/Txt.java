package ink.home;

import android.text.Html;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Plain text split on Chinese chapter headings, or into fixed chunks when there are none. */
final class Txt implements Content {
    private static final Pattern HEADING = Pattern.compile(
            "(?m)^[ \\t\\u3000]*(第[零〇一二三四五六七八九十百千万0-9]+[章节回卷集部篇][^\\n]{0,40})[ \\t\\u3000]*\\r?$");
    private static final int CHUNK = 20000;

    private final String text;
    private final File base;
    private final List<int[]> ranges = new ArrayList<>();
    private final List<TocEntry> toc = new ArrayList<>();

    Txt(File file, File base) throws IOException {
        this.base = base;
        text = decode(Files.readAllBytes(file.toPath()));
        Matcher matcher = HEADING.matcher(text);
        int previous = 0;
        String title = "开始";
        while (matcher.find()) {
            add(title, previous, matcher.start());
            previous = matcher.start();
            title = matcher.group(1).trim();
        }
        add(title, previous, text.length());
    }

    private void add(String title, int start, int end) {
        if (end <= start) return;
        toc.add(new TocEntry(title, ranges.size()));
        for (int from = start; from < end; ) {
            int to = Math.min(end, from + CHUNK);
            if (to < end) {
                int line = text.lastIndexOf('\n', to);
                if (line > from) to = line + 1;
            }
            ranges.add(new int[]{from, to});
            from = to;
        }
    }

    private static String decode(byte[] bytes) {
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, StandardCharsets.UTF_16BE);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("GB18030"));
        }
    }

    @Override
    public int chapters() {
        return ranges.size();
    }

    @Override
    public String html(int chapter) {
        int[] range = ranges.get(chapter);
        StringBuilder html = new StringBuilder("<html><head></head><body>");
        for (String line : text.substring(range[0], range[1]).split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            boolean heading = HEADING.matcher(trimmed).matches();
            html.append(heading ? "<h2>" : "<p class=\"t\">").append(Html.escapeHtml(trimmed))
                    .append(heading ? "</h2>" : "</p>");
        }
        return html.append("</body></html>").toString();
    }

    @Override
    public File base(int chapter) {
        return base;
    }

    @Override
    public List<TocEntry> toc() {
        return toc;
    }
}
