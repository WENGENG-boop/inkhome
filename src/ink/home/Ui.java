package ink.home;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared look: white paper, real type weights, hairlines, and nothing that does not carry meaning. */
final class Ui {
    static final int BLACK = 0xFF000000;
    static final int WHITE = 0xFFFFFFFF;
    static final int GRAY = 0xFF686868;
    static final int LINE = 0xFFC8C8C8;
    static final int FILL = 0xFFEEEEEE;

    static final int REGULAR = 0;
    static final int MEDIUM = 1;
    static final int BOLD = 2;

    static final int MARGIN = 24;
    private static final int MAX_DOTS = 9;

    private static Typeface medium;
    private static Typeface bold;
    private static Typeface serif;
    private static Typeface display;

    private Ui() {
    }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static Typeface face(Context context, int weight) {
        if (weight == REGULAR) return Typeface.DEFAULT;
        if (medium == null) {
            medium = Typeface.createFromAsset(context.getAssets(), "medium.otf");
            bold = Typeface.createFromAsset(context.getAssets(), "bold.otf");
        }
        return weight == BOLD ? bold : medium;
    }

    static Typeface serif(Context context) {
        if (serif == null) serif = Typeface.createFromAsset(context.getAssets(), "serif.otf");
        return serif;
    }

    /** The decorative numerals used for the clock. */
    static Typeface display(Context context) {
        if (display == null) display = Typeface.createFromAsset(context.getAssets(), "display.ttf");
        return display;
    }

    static TextView text(Context context, String value, float sp, int color, int weight) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        view.setTypeface(face(context, weight));
        return view;
    }

    static TextView oneLine(TextView view) {
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    static GradientDrawable round(Context context, int fill, float radiusDp, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(context, radiusDp));
        if (stroke != 0) shape.setStroke(Math.max(1, dp(context, 0.8f)), stroke);
        return shape;
    }

    static View hairline(Context context) {
        View line = new View(context);
        line.setBackgroundColor(LINE);
        return line;
    }

    static LinearLayout.LayoutParams hairlineParams() {
        return new LinearLayout.LayoutParams(-1, 1);
    }

    static int batteryLevel(Context context) {
        Intent state = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return state == null ? 0 : state.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
    }

    static ImageView icon(Context context, int res, int color) {
        ImageView image = new ImageView(context);
        image.setImageResource(res);
        image.setColorFilter(color);
        return image;
    }

    /** Oversized screen title with a quiet caption above it. */
    static LinearLayout header(Context context, String caption, String title) {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.VERTICAL);
        header.addView(oneLine(text(context, caption, 14, GRAY, MEDIUM)));
        TextView big = oneLine(text(context, title, 36, BLACK, BOLD));
        big.setPadding(0, dp(context, 6), 0, 0);
        header.addView(big);
        return header;
    }

    /** Pill-shaped choice control; the selected option is a solid black capsule. */
    static LinearLayout segmented(Context context, String[] labels, int selected, Choice choice) {
        LinearLayout control = new LinearLayout(context);
        int inset = dp(context, 3);
        control.setPadding(inset, inset, inset, inset);
        control.setBackground(round(context, FILL, 18, 0));
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            boolean on = i == selected;
            TextView cell = text(context, labels[i], 15, on ? WHITE : BLACK, MEDIUM);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(0, dp(context, 7), 0, dp(context, 7));
            if (on) cell.setBackground(round(context, BLACK, 15, 0));
            cell.setOnClickListener(v -> choice.chosen(index));
            control.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
        }
        return control;
    }

    /** Previous / position / next; hidden space is kept so the layout never jumps. */
    static LinearLayout pager(Context context, int page, int pages, Choice turn) {
        LinearLayout pager = new LinearLayout(context);
        pager.setGravity(Gravity.CENTER);
        if (pages <= 1) return pager;
        pager.addView(pagerArrow(context, R.drawable.ic_back, page > 0, -1, turn));
        TextView label = text(context, (page + 1) + " / " + pages, 14, GRAY, MEDIUM);
        label.setPadding(dp(context, 14), 0, dp(context, 14), 0);
        pager.addView(label);
        pager.addView(pagerArrow(context, R.drawable.ic_next, page < pages - 1, 1, turn));
        return pager;
    }

    /** Page dots; a long run of pages falls back to a plain count. */
    static LinearLayout dots(Context context, int page, int pages) {
        LinearLayout dots = new LinearLayout(context);
        dots.setGravity(Gravity.CENTER);
        if (pages <= 1) return dots;
        if (pages > MAX_DOTS) {
            dots.addView(text(context, (page + 1) + " / " + pages, 13, GRAY, MEDIUM));
            return dots;
        }
        for (int i = 0; i < pages; i++) {
            View dot = new View(context);
            dot.setBackground(round(context, i == page ? BLACK : LINE, 4, 0));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(context, 7), dp(context, 7));
            params.setMargins(dp(context, 5), 0, dp(context, 5), 0);
            dots.addView(dot, params);
        }
        return dots;
    }

    private static View pagerArrow(Context context, int res, boolean enabled, int delta, Choice turn) {
        ImageView arrow = icon(context, res, enabled ? BLACK : LINE);
        int pad = dp(context, 10);
        arrow.setPadding(dp(context, 22), pad, dp(context, 22), pad);
        if (enabled) arrow.setOnClickListener(v -> turn.chosen(delta));
        return arrow;
    }

    interface Choice {
        void chosen(int value);
    }

    /** Thin continuous progress line. */
    static final class Progress extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bar = new RectF();
        private final int thickness;
        private float value;

        Progress(Context context) {
            super(context);
            thickness = dp(context, 3);
        }

        void set(float value) {
            this.value = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float top = (getHeight() - thickness) / 2f;
            float radius = thickness / 2f;
            bar.set(0, top, getWidth(), top + thickness);
            paint.setColor(LINE);
            canvas.drawRoundRect(bar, radius, radius, paint);
            if (value <= 0) return;
            bar.right = Math.max(thickness, getWidth() * value);
            paint.setColor(BLACK);
            canvas.drawRoundRect(bar, radius, radius, paint);
        }
    }

    /** Small outline battery with a proportional fill. */
    /** Daily goal as an arc: a pale track with the black share of it drawn from twelve o'clock. */
    static final class Ring extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final int thickness;
        private float value;

        Ring(Context context) {
            super(context);
            thickness = dp(context, 13);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(thickness);
        }

        void set(float value) {
            this.value = Math.max(0, Math.min(1, value));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            box.set(thickness / 2f, thickness / 2f, getWidth() - thickness / 2f, getHeight() - thickness / 2f);
            paint.setColor(LINE);
            canvas.drawArc(box, 0, 360, false, paint);
            if (value <= 0) return;
            paint.setColor(BLACK);
            canvas.drawArc(box, -90, value * 360, false, paint);
        }
    }

    static final class Battery extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private float level;

        Battery(Context context, int color) {
            super(context);
            paint.setColor(color);
        }

        void set(float level) {
            this.level = level;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float stroke = Math.max(1, getHeight() / 11f);
            float nub = stroke * 1.6f;
            float half = stroke / 2;
            float right = getWidth() - nub;
            float radius = getHeight() / 5f;
            box.set(half, half, right - half, getHeight() - half);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            canvas.drawRoundRect(box, radius, radius, paint);
            paint.setStyle(Paint.Style.FILL);
            float gap = stroke * 2;
            box.set(gap, gap, gap + (right - gap * 2) * level, getHeight() - gap);
            canvas.drawRoundRect(box, radius / 2, radius / 2, paint);
            box.set(right + stroke / 2, getHeight() * 0.33f, getWidth(), getHeight() * 0.67f);
            canvas.drawRoundRect(box, stroke / 2, stroke / 2, paint);
        }
    }

    /** Recognises a horizontal flick; -1 means the next page, 1 the previous, 0 no flick. */
    static final class Swipe {
        private final int distance;
        private float x;
        private float y;

        Swipe(Context context) {
            distance = dp(context, 60);
        }

        int track(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                x = event.getX();
                y = event.getY();
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                float dx = event.getX() - x;
                float dy = event.getY() - y;
                if (Math.abs(dx) > distance && Math.abs(dx) > Math.abs(dy) * 2) return dx < 0 ? -1 : 1;
            }
            return 0;
        }
    }
}
