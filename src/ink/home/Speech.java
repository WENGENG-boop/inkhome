package ink.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.Html;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Calls an OpenAI-style speech endpoint, and cuts a book's HTML into text it can say. */
final class Speech {
    static final String URL_KEY = "url";
    static final String API_KEY = "key";
    static final String MODEL_KEY = "model";
    static final String VOICE_KEY = "voice";

    private static final int PIECE = 240;
    private static final int TIMEOUT = 60000;
    private static final int MAX_ERROR = 120;
    private static final String SENTENCE_END = "。！？!?；;\n";

    /** The service turned the request down, so sending it again cannot help. */
    static final class Refused extends IOException {
        Refused(String message) {
            super(message);
        }
    }

    private Speech() {
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("voice", Context.MODE_PRIVATE);
    }

    static boolean configured(Context context) {
        return !prefs(context).getString(URL_KEY, "").isEmpty();
    }

    static void synthesize(Context context, String text, File out) throws IOException {
        SharedPreferences prefs = prefs(context);
        HttpURLConnection http = (HttpURLConnection) new URL(prefs.getString(URL_KEY, "")).openConnection();
        try {
            http.setConnectTimeout(TIMEOUT);
            http.setReadTimeout(TIMEOUT);
            http.setRequestMethod("POST");
            http.setDoOutput(true);
            http.setRequestProperty("Content-Type", "application/json");
            String key = prefs.getString(API_KEY, "");
            if (!key.isEmpty()) http.setRequestProperty("Authorization", "Bearer " + key);
            try (OutputStream body = http.getOutputStream()) {
                body.write(request(prefs, text).getBytes(StandardCharsets.UTF_8));
            }
            int status = http.getResponseCode();
            if (status >= 400) {
                String message = "接口返回 " + status + ": " + excerpt(http.getErrorStream());
                if (status < 500 && status != 429) throw new Refused(message);
                throw new IOException(message);
            }
            String type = String.valueOf(http.getContentType());
            if (type.contains("json") || type.contains("text")) {
                throw new Refused("接口没有返回音频: " + excerpt(http.getInputStream()));
            }
            try (InputStream in = http.getInputStream(); FileOutputStream file = new FileOutputStream(out)) {
                byte[] buffer = new byte[16384];
                int read;
                while ((read = in.read(buffer)) > 0) file.write(buffer, 0, read);
            }
        } finally {
            http.disconnect();
        }
    }

    private static String request(SharedPreferences prefs, String text) throws IOException {
        try {
            JSONObject json = new JSONObject().put("input", text).put("response_format", "mp3");
            String model = prefs.getString(MODEL_KEY, "");
            String voice = prefs.getString(VOICE_KEY, "");
            if (!model.isEmpty()) json.put("model", model);
            if (!voice.isEmpty()) json.put("voice", voice);
            return json.toString();
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    private static String excerpt(InputStream stream) throws IOException {
        if (stream == null) return "";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream in = stream) {
            byte[] buffer = new byte[1024];
            int read = in.read(buffer);
            if (read > 0) bytes.write(buffer, 0, read);
        }
        String text = new String(bytes.toByteArray(), StandardCharsets.UTF_8).trim();
        return text.length() > MAX_ERROR ? text.substring(0, MAX_ERROR) : text;
    }

    static String plain(String html) {
        String body = html.replaceAll("(?is)<head.*?</head>|<style.*?</style>|<script.*?</script>", " ");
        return Html.fromHtml(body, Html.FROM_HTML_MODE_COMPACT).toString().replace('￼', ' ')
                .replaceAll("[ \\t\\u3000\\u00A0]+", " ").replaceAll("\\s*\\n\\s*", "\n").trim();
    }

    /** Backs up to the start of the sentence, so reading never begins mid-phrase. */
    static int sentenceStart(String text, int position) {
        for (int i = Math.min(position, text.length()); i > 0; i--) {
            if (SENTENCE_END.indexOf(text.charAt(i - 1)) >= 0) return i;
        }
        return 0;
    }

    /** Where the piece starting at start should stop: the last sentence end that fits. */
    static int pieceEnd(String text, int start) {
        int limit = start + PIECE;
        if (limit >= text.length()) return text.length();
        for (int i = limit; i > start + PIECE / 3; i--) {
            if (SENTENCE_END.indexOf(text.charAt(i - 1)) >= 0) return i;
        }
        return limit;
    }

    static boolean sayable(String piece) {
        for (int i = 0; i < piece.length(); i++) {
            if (Character.isLetterOrDigit(piece.charAt(i))) return true;
        }
        return false;
    }
}
