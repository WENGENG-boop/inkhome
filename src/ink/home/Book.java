package ink.home;

import org.json.JSONException;
import org.json.JSONObject;

/** One library entry. */
final class Book {
    String id;
    String path;
    String title;
    String author = "";
    String format;
    int chapter;
    float fraction;
    float progress;
    long lastRead;

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("id", id).put("path", path).put("title", title).put("author", author)
                .put("format", format).put("chapter", chapter).put("fraction", (double) fraction)
                .put("progress", (double) progress).put("lastRead", lastRead);
    }

    static Book fromJson(JSONObject json) {
        Book book = new Book();
        book.id = json.optString("id");
        book.path = json.optString("path");
        book.title = json.optString("title");
        book.author = json.optString("author");
        book.format = json.optString("format");
        book.chapter = json.optInt("chapter");
        book.fraction = (float) json.optDouble("fraction", 0);
        book.progress = (float) json.optDouble("progress", 0);
        book.lastRead = json.optLong("lastRead");
        return book;
    }
}
