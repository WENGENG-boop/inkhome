package ink.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.Environment;
import android.os.ParcelFileDescriptor;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/** Book list kept in a JSON file, filled by scanning shared storage. */
final class Library {
    private static final int MAX_DEPTH = 6;
    private static final String[] BOOK_DIRS = {"books", "documents", "download", "kindle"};
    /** Bracketed notes such as an edition or publisher; a bare volume marker like （二） is kept. */
    private static final String EDITION_NOTE =
            "[(（\\[【](?!第?[0-9一二三四五六七八九十上中下]+[册卷部集]?[)）\\]】])[^)）\\]】]*[)）\\]】]";

    interface Progress {
        void found(int count);
    }

    private Library() {
    }

    private static File store(Context context) {
        return new File(context.getFilesDir(), "library.json");
    }

    static File coverFile(Context context, Book book) {
        File dir = new File(context.getFilesDir(), "covers");
        dir.mkdirs();
        return new File(dir, book.id + ".png");
    }

    /** Books whose files still exist: most recently read first, then by title. */
    static synchronized List<Book> load(Context context) {
        List<Book> books = new ArrayList<>();
        try {
            byte[] bytes = Files.readAllBytes(store(context).toPath());
            JSONArray array = new JSONArray(new String(bytes, StandardCharsets.UTF_8));
            for (int i = 0; i < array.length(); i++) {
                Book book = Book.fromJson(array.getJSONObject(i));
                if (new File(book.path).exists()) books.add(book);
            }
        } catch (IOException | JSONException ignored) {
        }
        Collections.sort(books, (a, b) -> a.lastRead != b.lastRead
                ? Long.compare(b.lastRead, a.lastRead) : a.title.compareTo(b.title));
        return books;
    }

    static synchronized void save(Context context, List<Book> books) {
        try {
            JSONArray array = new JSONArray();
            for (Book book : books) array.put(book.toJson());
            Files.write(store(context).toPath(), array.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | JSONException ignored) {
        }
    }

    static synchronized void update(Context context, Book changed) {
        List<Book> books = load(context);
        for (int i = 0; i < books.size(); i++) {
            if (books.get(i).id.equals(changed.id)) books.set(i, changed);
        }
        save(context, books);
    }

    /** Reading time is kept for the current day only. */
    static void addReading(Context context, long millis) {
        SharedPreferences prefs = context.getSharedPreferences("reading", Context.MODE_PRIVATE);
        long total = today().equals(prefs.getString("day", "")) ? prefs.getLong("millis", 0) : 0;
        prefs.edit().putString("day", today()).putLong("millis", total + millis).apply();
    }

    static int todayMinutes(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("reading", Context.MODE_PRIVATE);
        return today().equals(prefs.getString("day", "")) ? (int) (prefs.getLong("millis", 0) / 60000) : 0;
    }

    private static String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date());
    }

    /** Opens a reflowable book; PDF has no text layer to reach and is rendered by the reader itself. */
    static Content open(Context context, Book book) throws Exception {
        File file = new File(book.path);
        if (book.format.equals("EPUB")) {
            File cache = new File(new File(context.getCacheDir(), "epub"), book.id);
            cache.mkdirs();
            return new Epub(file, cache);
        }
        if (book.format.equals("MOBI")) {
            File cache = new File(new File(context.getCacheDir(), "mobi"), book.id);
            cache.mkdirs();
            return new Mobi(file, cache);
        }
        return new Txt(file, context.getCacheDir());
    }

    static Book find(Context context, String id) {
        for (Book book : load(context)) {
            if (book.id.equals(id)) return book;
        }
        return null;
    }

    /** Walks shared storage and adds new books; existing entries keep their progress. */
    static List<Book> scan(Context context, Progress progress) {
        if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) return load(context);
        Map<String, Book> known = new HashMap<>();
        for (Book book : load(context)) known.put(book.path, book);
        List<File> files = new ArrayList<>();
        File[] top = Environment.getExternalStorageDirectory().listFiles();
        for (String wanted : BOOK_DIRS) {
            for (File dir : top == null ? new File[0] : top) {
                if (dir.isDirectory() && dir.getName().equalsIgnoreCase(wanted)) walk(dir, 0, files);
            }
        }
        List<Book> books = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (File file : files) {
            if (!seen.add(file.getName() + "/" + file.length())) continue;
            Book book = known.get(file.getPath());
            if (book == null) book = identify(context, file);
            books.add(book);
            progress.found(books.size());
        }
        synchronized (Library.class) {
            // Reading may have moved on while the walk ran; the stored position wins.
            Map<String, Book> latest = new HashMap<>();
            for (Book book : load(context)) latest.put(book.path, book);
            for (int i = 0; i < books.size(); i++) {
                Book current = latest.get(books.get(i).path);
                if (current != null) books.set(i, current);
            }
            save(context, books);
            return load(context);
        }
    }

    /** What the shelf shows: one entry per title, even when the same book exists in several formats.
     *  The edition being read stays, unless it is a PDF: those pages cannot be set, so any other format replaces it. */
    static List<Book> shelf(List<Book> books) {
        List<Book> shown = new ArrayList<>();
        for (Book book : books) {
            int twin = -1;
            for (int i = 0; i < shown.size() && twin < 0; i++) {
                if (editions(shown.get(i), book)) twin = i;
            }
            if (twin < 0) {
                shown.add(book);
            } else if ((book.lastRead == shown.get(twin).lastRead || shown.get(twin).format.equals("PDF"))
                    && rank(book) < rank(shown.get(twin))) {
                shown.set(twin, book);
            }
        }
        return shown;
    }

    /** The other formats of the same book count as editions; numbered volumes never do. */
    private static boolean editions(Book a, Book b) {
        if (a.format.equals(b.format) || !titleKey(a).equals(titleKey(b))) return false;
        return a.author.isEmpty() || b.author.isEmpty() || a.author.contains(b.author) || b.author.contains(a.author);
    }

    private static String titleKey(Book book) {
        return shortTitle(book).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /** The title as a person would say it, without edition and marketing notes. */
    static String shortTitle(Book book) {
        String title = book.title.replaceAll(EDITION_NOTE, "").replaceAll("(?<=.)《.*$", "").trim();
        return title.isEmpty() ? book.title : title;
    }

    private static int rank(Book book) {
        return Arrays.asList("EPUB", "MOBI", "PDF", "TXT").indexOf(book.format);
    }

    /** Removes the book and its other editions from the device; false when a file could not be deleted. */
    static synchronized boolean delete(Context context, Book target) {
        List<Book> kept = new ArrayList<>();
        boolean removed = true;
        for (Book book : load(context)) {
            if (book.id.equals(target.id) || editions(book, target)) {
                File file = new File(book.path);
                if (file.delete() || !file.exists()) {
                    coverFile(context, book).delete();
                } else {
                    removed = false;
                    kept.add(book);
                }
            } else {
                kept.add(book);
            }
        }
        save(context, kept);
        return removed;
    }

    private static void walk(File dir, int depth, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null || depth > MAX_DEPTH) return;
        for (File child : children) {
            String name = child.getName();
            if (name.startsWith(".")) continue;
            if (child.isDirectory()) {
                walk(child, depth + 1, out);
            } else if (format(name) != null) {
                out.add(child);
            }
        }
    }

    static String format(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".epub")) return "EPUB";
        if (lower.endsWith(".mobi") || lower.endsWith(".prc") || lower.endsWith(".azw")) return "MOBI";
        if (lower.endsWith(".pdf")) return "PDF";
        if (lower.endsWith(".txt")) return "TXT";
        return null;
    }

    private static Book identify(Context context, File file) {
        Book book = new Book();
        book.path = file.getPath();
        book.id = Integer.toHexString(file.getPath().hashCode());
        book.format = format(file.getName());
        String name = file.getName();
        book.title = name.substring(0, name.lastIndexOf('.')).replaceFirst("^[A-Z0-9]{32}_(.+)_PDOC$", "$1");
        try {
            if (book.format.equals("EPUB")) readEpub(context, file, book);
            if (book.format.equals("PDF")) readPdf(context, file, book);
            if (book.format.equals("MOBI")) readMobi(context, file, book);
        } catch (Exception ignored) {
        }
        return book;
    }

    private static void readEpub(Context context, File file, Book book) throws Exception {
        try (ZipFile zip = new ZipFile(file)) {
            Epub.Package pkg = Epub.readPackage(zip);
            if (!pkg.title.isEmpty()) book.title = pkg.title;
            book.author = pkg.author;
            if (pkg.coverPath != null) saveCover(context, book, Epub.read(zip, pkg.coverPath));
        }
    }

    private static void readMobi(Context context, File file, Book book) throws Exception {
        Mobi.Header header = new Mobi.Header(Files.readAllBytes(file.toPath()));
        if (!header.title.isEmpty()) book.title = header.title;
        book.author = header.author;
        byte[] cover = header.cover();
        if (cover != null) saveCover(context, book, cover);
    }

    private static void saveCover(Context context, Book book, byte[] bytes) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        options.inJustDecodeBounds = false;
        options.inSampleSize = Math.max(1, options.outHeight / 800);
        saveCover(context, book, BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options));
    }

    private static void readPdf(Context context, File file, Book book) throws IOException {
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(fd);
             PdfRenderer.Page page = renderer.openPage(0)) {
            Bitmap bitmap = Bitmap.createBitmap(420, Math.round(420f * page.getHeight() / page.getWidth()),
                    Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            saveCover(context, book, bitmap);
        }
    }

    private static void saveCover(Context context, Book book, Bitmap bitmap) throws IOException {
        if (bitmap == null) return;
        try (FileOutputStream out = new FileOutputStream(coverFile(context, book))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        bitmap.recycle();
    }
}
