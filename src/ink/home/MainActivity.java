package ink.home;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int SCREEN_HOME = 0;
    private static final int SCREEN_LIBRARY = 1;
    private static final int SCREEN_APPS = 2;
    private static final int COLUMNS = 3;
    private static final int ROWS = 2;
    private static final int BOOKS_PER_PAGE = COLUMNS * ROWS;
    private static final int APP_COLUMNS = 4;
    private static final int APP_ROWS = 5;
    private static final int APPS_PER_PAGE = APP_COLUMNS * APP_ROWS;
    private static final int DAILY_GOAL = 30;
    private static final int STACK_BOOKS = 3;
    private static final int DELETE_PERMISSION = 2;
    private static final List<String> HIDDEN = Arrays.asList("com.mgs.factorytest", "com.android.settings");

    private List<Book> books = new ArrayList<>();
    private List<ResolveInfo> apps = new ArrayList<>();
    private int screen = SCREEN_HOME;
    private boolean scanning;
    private int bookPage;
    private int appPage;
    private Book pendingDelete;
    private FrameLayout root;
    private View sheet;
    private TextView clock;
    private TextView bigClock;
    private TextView bigDate;
    private ImageView wifi;
    private TextView batteryText;
    private Ui.Battery battery;
    private Ui.Swipe swipe;

    private final BroadcastReceiver ticker = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updateStatus();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setWindowAnimations(0);
        swipe = new Ui.Swipe(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(ticker, new IntentFilter(Intent.ACTION_TIME_TICK));
        books = Library.shelf(Library.load(this));
        render();
        if (!scanning) startScan();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(ticker);
    }

    @Override
    public void onBackPressed() {
        if (sheet != null) {
            closeSheet();
        } else if (screen != SCREEN_HOME) {
            show(SCREEN_HOME);
        }
    }

    /** This is the system home screen, so the home key lands here and there is nothing behind it to go back to. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (screen != SCREEN_HOME) show(SCREEN_HOME);
        else if (sheet != null) closeSheet();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        int direction = swipe.track(event);
        if (direction != 0 && sheet == null) {
            event.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(event);
            turnPage(-direction);
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    private void show(int target) {
        screen = target;
        if (screen == SCREEN_APPS) loadApps();
        render();
    }

    private void turnPage(int delta) {
        if (screen == SCREEN_HOME) return;
        if (screen == SCREEN_LIBRARY) {
            int next = bookPage + delta;
            if (next < 0 || next >= pageCount(books.size(), BOOKS_PER_PAGE)) return;
            bookPage = next;
        } else {
            int next = appPage + delta;
            if (next < 0 || next >= pageCount(apps.size(), APPS_PER_PAGE)) return;
            appPage = next;
        }
        render();
    }

    private static int pageCount(int items, int perPage) {
        return Math.max(1, (items + perPage - 1) / perPage);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (code == DELETE_PERMISSION) {
            if (granted && pendingDelete != null) delete(pendingDelete);
        } else if (granted) {
            startScan();
        }
    }

    /** Runs quietly on every return to the app, so new books simply appear. */
    private void startScan() {
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
            return;
        }
        scanning = true;
        new Thread(() -> {
            List<Book> found = Library.shelf(Library.scan(this, count -> { }));
            runOnUiThread(() -> {
                scanning = false;
                boolean changed = !ids(found).equals(ids(books));
                books = found;
                if ((changed || books.isEmpty()) && sheet == null) render();
            });
        }).start();
    }

    private static List<String> ids(List<Book> list) {
        List<String> ids = new ArrayList<>();
        for (Book book : list) ids.add(book.id + book.lastRead + book.progress);
        return ids;
    }

    private void render() {
        sheet = null;
        bigClock = null;
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.WHITE);
        View body = screen == SCREEN_HOME ? buildHome() : screen == SCREEN_LIBRARY ? buildLibrary() : buildApps();
        root.addView(body, new FrameLayout.LayoutParams(-1, -1));
        root.addView(navigation(), new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);
        updateStatus();
    }

    private View statusBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(Ui.MARGIN), dp(12), dp(Ui.MARGIN), dp(4));
        clock = Ui.text(this, "", 14, Ui.BLACK, Ui.MEDIUM);
        bar.addView(clock, new LinearLayout.LayoutParams(0, -2, 1));
        wifi = Ui.icon(this, R.drawable.ic_wifi, Ui.BLACK);
        LinearLayout.LayoutParams wifiParams = new LinearLayout.LayoutParams(dp(17), dp(17));
        wifiParams.rightMargin = dp(10);
        bar.addView(wifi, wifiParams);
        batteryText = Ui.text(this, "", 13, Ui.BLACK, Ui.MEDIUM);
        batteryText.setPadding(0, 0, dp(6), 0);
        bar.addView(batteryText);
        battery = new Ui.Battery(this, Ui.BLACK);
        bar.addView(battery, new LinearLayout.LayoutParams(dp(25), dp(12)));
        return bar;
    }

    /** The home screen shows the time large, so its status bar leaves the time out. */
    private void updateStatus() {
        Date now = new Date();
        String time = new SimpleDateFormat("H:mm", Locale.ROOT).format(now);
        clock.setText(bigClock == null ? time : "");
        if (bigClock != null) {
            bigClock.setText(time);
            bigDate.setText(new SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(now));
        }
        int level = Ui.batteryLevel(this);
        batteryText.setText(level + "%");
        battery.set(level / 100f);
        ConnectivityManager network = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo active = network.getActiveNetworkInfo();
        wifi.setVisibility(active != null && active.isConnected() ? View.VISIBLE : View.GONE);
    }

    /** Four words; the current place rests on a soft rounded tile. */
    private View navigation() {
        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(dp(Ui.MARGIN), 0, dp(Ui.MARGIN), dp(10));
        bar.addView(place("主页", screen == SCREEN_HOME, v -> show(SCREEN_HOME)), slot());
        bar.addView(place("书库", screen == SCREEN_LIBRARY, v -> show(SCREEN_LIBRARY)), slot());
        bar.addView(place("应用", screen == SCREEN_APPS, v -> show(SCREEN_APPS)), slot());
        bar.addView(place("设置", false, v -> {
            Intent vendor = getPackageManager().getLaunchIntentForPackage("com.mgs.settings");
            launch(vendor != null ? vendor : new Intent(Settings.ACTION_SETTINGS));
        }), slot());
        return bar;
    }

    private LinearLayout.LayoutParams slot() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.leftMargin = dp(12);
        params.rightMargin = dp(12);
        return params;
    }

    private View place(String label, boolean current, View.OnClickListener listener) {
        TextView word = Ui.text(this, label, 16, current ? Ui.BLACK : Ui.GRAY, current ? Ui.BOLD : Ui.REGULAR);
        word.setGravity(Gravity.CENTER);
        word.setPadding(0, dp(9), 0, dp(9));
        if (current) word.setBackground(Ui.round(this, Ui.FILL, 18, 0));
        word.setOnClickListener(listener);
        return word;
    }

    /** Large title shared by the library and the apps. */
    private View titleBand(String title, String caption) {
        LinearLayout band = new LinearLayout(this);
        band.setOrientation(LinearLayout.VERTICAL);
        band.addView(statusBar());
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.BOTTOM);
        row.setPadding(dp(Ui.MARGIN), dp(14), dp(Ui.MARGIN), dp(2));
        row.addView(Ui.oneLine(Ui.text(this, title, 38, Ui.BLACK, Ui.BOLD)), new LinearLayout.LayoutParams(0, -2, 1));
        TextView count = Ui.text(this, caption, 15, Ui.GRAY, Ui.REGULAR);
        count.setPadding(0, 0, 0, dp(6));
        row.addView(count);
        band.addView(row);
        return band;
    }

    private TextView serif(String value, float sp, int color) {
        TextView view = Ui.text(this, value, sp, color, Ui.REGULAR);
        view.setTypeface(Ui.serif(this));
        return view;
    }

    /** Everything below a title band; the bottom inset keeps content clear of the navigation. */
    private LinearLayout page() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(Ui.MARGIN), dp(16), dp(Ui.MARGIN), dp(62));
        return page;
    }

    private LinearLayout screenWith(View band, View page) {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.addView(band);
        screen.addView(page, new LinearLayout.LayoutParams(-1, 0, 1));
        return screen;
    }

    /** The time fills whatever the cards leave: a low reading card, then today's goal and the folded shelf. */
    private View buildHome() {
        LinearLayout home = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL);
        home.addView(statusBar());
        home.addView(masthead(), new LinearLayout.LayoutParams(-1, 0, 1));
        if (books.isEmpty()) {
            home.addView(emptyState(), new LinearLayout.LayoutParams(-1, 0, 1));
            return home;
        }
        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.VERTICAL);
        cards.setPadding(dp(Ui.MARGIN), 0, dp(Ui.MARGIN), dp(62));
        cards.addView(hero(books.get(0)));
        LinearLayout row = new LinearLayout(this);
        row.addView(goalCard(), new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout.LayoutParams shelf = new LinearLayout.LayoutParams(0, -1, 1);
        shelf.leftMargin = dp(14);
        row.addView(shelfCard(), shelf);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, dp(226));
        rowParams.topMargin = dp(14);
        cards.addView(row, rowParams);
        home.addView(cards);
        return home;
    }

    private View masthead() {
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setGravity(Gravity.CENTER);
        bigDate = Ui.text(this, "", 17, Ui.BLACK, Ui.MEDIUM);
        bigDate.setGravity(Gravity.CENTER);
        top.addView(bigDate);
        bigClock = Ui.text(this, "", 140, Ui.BLACK, Ui.REGULAR);
        bigClock.setTypeface(Ui.display(this));
        bigClock.setGravity(Gravity.CENTER);
        bigClock.setSingleLine(true);
        top.addView(bigClock);
        return top;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(this, Ui.FILL, 24, 0));
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        return card;
    }

    private View hero(final Book book) {
        boolean started = percent(book) > 0;
        LinearLayout hero = card();
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(14), dp(14), dp(14), dp(14));
        hero.addView(new Cover(this, book), new LinearLayout.LayoutParams(dp(100), dp(142)));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(18), 0, dp(24), 0);
        text.addView(Ui.text(this, started ? "继续阅读" : "下一本", 15, Ui.GRAY, Ui.MEDIUM));
        TextView title = serif(Library.shortTitle(book), 24, Ui.BLACK);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setLineSpacing(dp(4), 1);
        title.setPadding(0, dp(7), 0, 0);
        text.addView(title);
        if (!book.author.isEmpty()) {
            TextView author = Ui.oneLine(Ui.text(this, book.author, 15, Ui.GRAY, Ui.REGULAR));
            author.setPadding(0, dp(6), 0, 0);
            text.addView(author);
        }
        if (started) {
            Ui.Progress bar = new Ui.Progress(this);
            bar.set(book.progress);
            LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, dp(12));
            barParams.topMargin = dp(10);
            text.addView(bar, barParams);
            TextView state = Ui.text(this, "已读 " + status(book), 14, Ui.BLACK, Ui.REGULAR);
            state.setPadding(0, dp(4), 0, 0);
            text.addView(state);
        }
        hero.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        hero.setOnClickListener(v -> open(book));
        hero.setOnLongClickListener(v -> {
            showSheet(book);
            return true;
        });
        return hero;
    }

    private View goalCard() {
        int minutes = Library.todayMinutes(this);
        LinearLayout card = card();
        card.addView(Ui.text(this, "今日阅读", 15, Ui.BLACK, Ui.MEDIUM));
        FrameLayout stage = new FrameLayout(this);
        Ui.Ring ring = new Ui.Ring(this);
        ring.set(minutes / (float) DAILY_GOAL);
        stage.addView(ring, new FrameLayout.LayoutParams(dp(150), dp(150), Gravity.CENTER));
        LinearLayout label = new LinearLayout(this);
        label.setOrientation(LinearLayout.VERTICAL);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView count = Ui.text(this, String.valueOf(minutes), 44, Ui.BLACK, Ui.REGULAR);
        count.setTypeface(Ui.display(this));
        count.setGravity(Gravity.CENTER);
        label.addView(count);
        TextView goal = Ui.text(this, "/ " + DAILY_GOAL + " 分钟", 13, Ui.GRAY, Ui.REGULAR);
        goal.setGravity(Gravity.CENTER);
        label.addView(goal);
        stage.addView(label, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        card.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        return card;
    }

    /** The other books as a small pile; the library is one tap behind it. */
    private View shelfCard() {
        LinearLayout card = card();
        LinearLayout head = new LinearLayout(this);
        head.addView(Ui.text(this, "书库", 15, Ui.BLACK, Ui.MEDIUM), new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(Ui.text(this, books.size() + " 本", 15, Ui.GRAY, Ui.REGULAR));
        card.addView(head);
        FrameLayout pile = new FrameLayout(this);
        int count = Math.min(STACK_BOOKS, books.size() - 1);
        for (int i = count; i >= 1; i--) {
            float scale = 1 - (i - 1) * 0.14f;
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(116 * scale), dp(164 * scale), Gravity.BOTTOM);
            params.leftMargin = dp((i - 1) * 62 - (i - 1) * (i - 2) * 6);
            pile.addView(new Cover(this, books.get(i)).framed(), params);
        }
        card.addView(pile, new LinearLayout.LayoutParams(-1, 0, 1));
        card.setOnClickListener(v -> show(SCREEN_LIBRARY));
        return card;
    }

    private View emptyState() {
        LinearLayout empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        TextView title = Ui.text(this, scanning ? "正在查找你的书" : "这里还没有书", 24, Ui.BLACK, Ui.BOLD);
        empty.addView(title);
        TextView tip = Ui.text(this, "用数据线连接电脑，把 EPUB、MOBI、PDF 或 TXT\n放进 Books 文件夹，它们会自动出现在这里。",
                16, Ui.GRAY, Ui.REGULAR);
        tip.setGravity(Gravity.CENTER);
        tip.setLineSpacing(dp(6), 1);
        tip.setPadding(0, dp(14), 0, dp(60));
        empty.addView(tip);
        return empty;
    }

    private View buildLibrary() {
        LinearLayout page = page();
        View band = titleBand("书库", books.size() + " 本书");
        if (books.isEmpty()) {
            page.addView(emptyState(), new LinearLayout.LayoutParams(-1, 0, 1));
            return screenWith(band, page);
        }
        int pages = pageCount(books.size(), BOOKS_PER_PAGE);
        bookPage = Math.min(bookPage, pages - 1);
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        int start = bookPage * BOOKS_PER_PAGE;
        for (int r = 0; r < ROWS; r++) {
            LinearLayout row = new LinearLayout(this);
            for (int c = 0; c < COLUMNS; c++) {
                int i = start + r * COLUMNS + c;
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
                if (c > 0) params.leftMargin = dp(14);
                row.addView(i < books.size() ? tile(books.get(i)) : new View(this), params);
            }
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, 0, 1);
            if (r > 0) rowParams.topMargin = dp(14);
            grid.addView(row, rowParams);
        }
        page.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        page.addView(Ui.dots(this, bookPage, pages), new LinearLayout.LayoutParams(-1, dp(24)));
        return screenWith(band, page);
    }

    private View tile(final Book book) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.addView(new Cover(this, book), new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout caption = new LinearLayout(this);
        caption.setOrientation(LinearLayout.VERTICAL);
        caption.setPadding(0, dp(6), 0, 0);
        caption.addView(Ui.oneLine(Ui.text(this, Library.shortTitle(book), 15, Ui.BLACK, Ui.MEDIUM)));
        TextView state = Ui.oneLine(Ui.text(this, status(book), 13, Ui.GRAY, Ui.REGULAR));
        state.setPadding(0, dp(3), 0, 0);
        if (percent(book) == 0) state.setVisibility(View.INVISIBLE);
        caption.addView(state);
        tile.addView(caption);
        tile.setOnClickListener(v -> open(book));
        tile.setOnLongClickListener(v -> {
            showSheet(book);
            return true;
        });
        return tile;
    }

    private static String status(Book book) {
        return percent(book) >= 100 ? "已读完" : percent(book) + "%";
    }

    private static int percent(Book book) {
        return Math.round(book.progress * 100);
    }

    /** Floating card with the book's actions. There is no dimming: a gray wash smears on e-ink. */
    private void showSheet(final Book book) {
        LinearLayout card = sheetCard();
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(18), dp(16), dp(18), dp(16));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.addView(Ui.oneLine(Ui.text(this, Library.shortTitle(book), 19, Ui.BLACK, Ui.BOLD)));
        String meta = book.author.isEmpty() ? book.format : book.author + " · " + book.format;
        TextView detail = Ui.oneLine(Ui.text(this, meta, 14, Ui.GRAY, Ui.REGULAR));
        detail.setPadding(0, dp(5), 0, 0);
        names.addView(detail);
        top.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(top);
        card.addView(Ui.hairline(this), Ui.hairlineParams());
        card.addView(sheetRow(book.lastRead > 0 ? "继续阅读" : "开始阅读", Ui.MEDIUM, v -> {
            closeSheet();
            open(book);
        }));
        card.addView(Ui.hairline(this), Ui.hairlineParams());
        card.addView(sheetRow("删除…", Ui.MEDIUM, v -> confirmDelete(book)));
        presentSheet(card);
    }

    private void confirmDelete(final Book book) {
        LinearLayout card = sheetCard();
        TextView title = Ui.text(this, "删除这本书？", 19, Ui.BLACK, Ui.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(dp(18), dp(20), dp(18), 0);
        card.addView(title);
        TextView tip = Ui.text(this, "《" + Library.shortTitle(book) + "》的所有格式都会从设备上删除，无法恢复。", 15, Ui.GRAY, Ui.REGULAR);
        tip.setGravity(Gravity.CENTER);
        tip.setLineSpacing(dp(4), 1);
        tip.setPadding(dp(24), dp(10), dp(24), dp(18));
        card.addView(tip);
        card.addView(Ui.hairline(this), Ui.hairlineParams());
        card.addView(sheetRow("删除", Ui.BOLD, v -> delete(book)));
        card.addView(Ui.hairline(this), Ui.hairlineParams());
        card.addView(sheetRow("取消", Ui.MEDIUM, v -> closeSheet()));
        presentSheet(card);
    }

    private void delete(Book book) {
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingDelete = book;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, DELETE_PERMISSION);
            return;
        }
        pendingDelete = null;
        boolean removed = Library.delete(this, book);
        books = Library.shelf(Library.load(this));
        render();
        if (!removed) showNotice("这本书没有删除成功，文件可能正在被占用。");
    }

    private void showNotice(String message) {
        LinearLayout card = sheetCard();
        TextView tip = Ui.text(this, message, 16, Ui.BLACK, Ui.REGULAR);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(dp(24), dp(22), dp(24), dp(22));
        card.addView(tip);
        card.addView(Ui.hairline(this), Ui.hairlineParams());
        card.addView(sheetRow("好", Ui.MEDIUM, v -> closeSheet()));
        presentSheet(card);
    }

    private LinearLayout sheetCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(this, Ui.WHITE, 16, Ui.BLACK));
        card.setClickable(true);
        return card;
    }

    private View sheetRow(String label, int weight, View.OnClickListener listener) {
        TextView row = Ui.text(this, label, 18, Ui.BLACK, weight);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(15), 0, dp(15));
        row.setOnClickListener(listener);
        return row;
    }

    private void presentSheet(View card) {
        if (sheet != null) root.removeView(sheet);
        FrameLayout layer = new FrameLayout(this);
        layer.setOnClickListener(v -> closeSheet());
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        params.setMargins(dp(16), 0, dp(16), dp(8));
        layer.addView(card, params);
        sheet = layer;
        root.addView(sheet, new FrameLayout.LayoutParams(-1, -1));
    }

    private void closeSheet() {
        root.removeView(sheet);
        sheet = null;
    }

    private View buildApps() {
        LinearLayout page = page();
        int pages = pageCount(apps.size(), APPS_PER_PAGE);
        appPage = Math.min(appPage, pages - 1);
        int start = appPage * APPS_PER_PAGE;
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int r = 0; r < APP_ROWS; r++) {
            LinearLayout row = new LinearLayout(this);
            for (int c = 0; c < APP_COLUMNS; c++) {
                int i = start + r * APP_COLUMNS + c;
                row.addView(i < apps.size() ? appCell(apps.get(i)) : new View(this), new LinearLayout.LayoutParams(0, -1, 1));
            }
            grid.addView(row, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        page.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        page.addView(Ui.dots(this, appPage, pages), new LinearLayout.LayoutParams(-1, dp(24)));
        return screenWith(titleBand("应用", apps.size() + " 个"), page);
    }

    private View appCell(final ResolveInfo info) {
        PackageManager pm = getPackageManager();
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        ImageView image = new ImageView(this);
        image.setImageDrawable(info.loadIcon(pm));
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(0);
        image.setColorFilter(new ColorMatrixColorFilter(matrix));
        cell.addView(image, new LinearLayout.LayoutParams(dp(54), dp(54)));
        TextView name = Ui.oneLine(Ui.text(this, info.loadLabel(pm).toString(), 13, Ui.BLACK, Ui.REGULAR));
        name.setGravity(Gravity.CENTER);
        name.setPadding(dp(4), dp(7), dp(4), 0);
        cell.addView(name, new LinearLayout.LayoutParams(-1, -2));
        cell.setOnClickListener(v -> launch(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(info.activityInfo.packageName, info.activityInfo.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        return cell;
    }

    private void open(Book book) {
        launch(new Intent(this, ReaderActivity.class).putExtra("id", book.id));
    }

    private void launch(Intent intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (RuntimeException ignored) {
        }
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        apps = new ArrayList<>();
        for (ResolveInfo info : pm.queryIntentActivities(intent, 0)) {
            String pkg = info.activityInfo.packageName;
            if (!getPackageName().equals(pkg) && !HIDDEN.contains(pkg)) apps.add(info);
        }
        Collections.sort(apps, new ResolveInfo.DisplayNameComparator(pm));
    }

    private LinearLayout.LayoutParams spaced(int width, int height, int topDp, int bottomDp) {
        return spaced(width, height, topDp, bottomDp, 0);
    }

    private LinearLayout.LayoutParams spaced(int width, int height, int topDp, int bottomDp, float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height, weight);
        params.topMargin = dp(topDp);
        params.bottomMargin = dp(bottomDp);
        return params;
    }

    private int dp(float value) {
        return Ui.dp(this, value);
    }
}
