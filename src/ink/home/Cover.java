package ink.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import java.io.File;

/** Book cover at its natural proportions, resting on the bottom of its cell like a book on a shelf. */
final class Cover extends View {
    private static final float MIN_ASPECT = 1.2f;
    private static final float MAX_ASPECT = 1.6f;
    private static final float DEFAULT_ASPECT = 1.42f;
    private static final int LEVELS = 16;

    private final Book book;
    private Bitmap bitmap;
    private boolean framed;

    Cover(Context context, Book book) {
        super(context);
        this.book = book;
    }

    /** A black keyline, so pale covers stay distinct where they overlap in a pile. */
    Cover framed() {
        framed = true;
        return this;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        if (w <= 0 || h <= 0) return;
        File file = Library.coverFile(getContext(), book);
        Bitmap art = file.exists() ? BitmapFactory.decodeFile(file.getPath()) : null;
        float aspect = art == null ? DEFAULT_ASPECT
                : Math.max(MIN_ASPECT, Math.min(MAX_ASPECT, art.getHeight() / (float) art.getWidth()));
        int width = w;
        int height = Math.round(width * aspect);
        if (height > h) {
            height = h;
            width = Math.round(height / aspect);
        }
        Bitmap face = art != null ? dither(art, width, height) : generate(width, height);
        bitmap = compose(face, w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (bitmap != null) canvas.drawBitmap(bitmap, 0, 0, null);
    }

    /** Rounded and flat: a shadow would only dither into gray noise on the panel. */
    private Bitmap compose(Bitmap face, int w, int h) {
        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        float radius = Ui.dp(getContext(), 9);
        int top = h - face.getHeight();
        RectF frame = new RectF(0, top, face.getWidth(), h);

        Paint art = new Paint(Paint.ANTI_ALIAS_FLAG);
        BitmapShader shader = new BitmapShader(face, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix offset = new Matrix();
        offset.setTranslate(0, top);
        shader.setLocalMatrix(offset);
        art.setShader(shader);
        canvas.drawRoundRect(frame, radius, radius, art);

        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setStyle(Paint.Style.STROKE);
        float stroke = framed ? Ui.dp(getContext(), 1) : 1;
        edge.setStrokeWidth(stroke);
        edge.setColor(framed ? Ui.BLACK : Ui.LINE);
        frame.inset(stroke / 2, stroke / 2);
        canvas.drawRoundRect(frame, radius, radius, edge);
        face.recycle();
        return result;
    }

    /** Scales to size, then error-diffuses onto the panel's 16 gray levels so tones stay smooth. */
    private static Bitmap dither(Bitmap art, int w, int h) {
        float scale = Math.max(w / (float) art.getWidth(), h / (float) art.getHeight());
        int sw = Math.round(w / scale);
        int sh = Math.round(h / scale);
        int sx = (art.getWidth() - sw) / 2;
        int sy = (art.getHeight() - sh) / 2;
        Bitmap small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        new Canvas(small).drawBitmap(art, new Rect(sx, sy, sx + sw, sy + sh), new Rect(0, 0, w, h),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        art.recycle();

        int[] pixels = new int[w * h];
        small.getPixels(pixels, 0, w, 0, 0, w, h);
        float[] gray = new float[w * h];
        for (int i = 0; i < gray.length; i++) {
            int p = pixels[i];
            float luma = ((p >> 16 & 255) * 0.3f + (p >> 8 & 255) * 0.59f + (p & 255) * 0.11f);
            gray[i] = (luma - 128) * 1.12f + 128;
        }
        float step = 255f / (LEVELS - 1);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float value = Math.max(0, Math.min(255, gray[i]));
                int level = Math.round(Math.round(value / step) * step);
                float error = value - level;
                pixels[i] = 0xFF000000 | level << 16 | level << 8 | level;
                if (x + 1 < w) gray[i + 1] += error * 7 / 16;
                if (y + 1 < h) {
                    if (x > 0) gray[i + w - 1] += error * 3 / 16;
                    gray[i + w] += error * 5 / 16;
                    if (x + 1 < w) gray[i + w + 1] += error / 16;
                }
            }
        }
        small.setPixels(pixels, 0, w, 0, 0, w, h);
        return small;
    }

    /** A plain cloth-bound look for books without art: one tone, title and author set in serif. */
    private Bitmap generate(int w, int h) {
        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        boolean dark = (book.title.hashCode() & 1) == 0;
        canvas.drawColor(dark ? 0xFF222222 : 0xFFEEEEEE);
        int ink = dark ? Ui.WHITE : Ui.BLACK;
        int pad = Math.round(w * 0.11f);

        TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        title.setColor(ink);
        title.setTypeface(Ui.serif(getContext()));
        title.setTextSize(w / 8f);
        StaticLayout layout = StaticLayout.Builder.obtain(book.title, 0, book.title.length(), title, w - pad * 2)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(4).setLineSpacing(0, 1.15f)
                .setEllipsize(TextUtils.TruncateAt.END).build();
        canvas.save();
        canvas.translate(pad, pad * 1.4f);
        layout.draw(canvas);
        canvas.restore();

        if (!book.author.isEmpty()) {
            TextPaint author = new TextPaint(title);
            author.setTextSize(w / 13f);
            String line = TextUtils.ellipsize(book.author, author, w - pad * 2, TextUtils.TruncateAt.END).toString();
            canvas.drawText(line, pad, h - pad * 1.2f, author);
        }
        return result;
    }
}
