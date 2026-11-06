package ink.home;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Paged reader: EPUB and TXT flow through a column-laid WebView, PDF renders page bitmaps. */
public class ReaderActivity extends Activity {
    private static final int MIN_FONT = 14;
    private static final int MAX_FONT = 30;
    private static final int DEFAULT_FONT = 19;
    private static final int TOC_PER_PAGE = 9;
    private static final String[] FACES = {"宋体", "黑体"};
    private static final String[] SPACINGS = {"紧凑", "标准", "宽松"};
    private static final double[] LINE_HEIGHTS = {1.5, 1.75, 2.0};
    private static final int FONT_WAIT_TRIES = 40;
    /** Moves the body into #ink, a fixed-width scroller: a wide document would stretch the viewport and clamp the last pages. */
    private static final String MEASURE = "(function(){var d=document.getElementById('ink');if(!d){d=document.createElement('div');"
            + "d.id='ink';while(document.body.firstChild)d.appendChild(document.body.firstChild);document.body.appendChild(d);}"
            + "return d.scrollWidth})()";

    /** How far through the chapter's text the first visible character sits. */
    private static final String FIRST_VISIBLE = "(function(){var d=document.getElementById('ink'),k=document.createTreeWalker(d,4,null,false),"
            + "r=document.createRange(),W=d.clientWidth,t=0,h=-1,n;while(n=k.nextNode()){if(!n.nodeValue.trim())continue;var l=n.nodeValue.length;"
            + "if(h<0){for(var o=0;o<l;o+=8){r.setStart(n,o);r.setEnd(n,Math.min(l,o+1));var b=r.getBoundingClientRect();"
            + "if(b.width&&b.left>=0&&b.left<W){h=t+o;break}}}t+=l}return h<0||!t?0:h/t})()";
    /** The page holding the character that far through the chapter's text. */
    private static final String PAGE_AT = "(function(q){var d=document.getElementById('ink'),W=d.clientWidth,r=document.createRange(),t=0,c=0,n,"
            + "k=document.createTreeWalker(d,4,null,false);while(n=k.nextNode())if(n.nodeValue.trim())t+=n.nodeValue.length;"
            + "k=document.createTreeWalker(d,4,null,false);while(n=k.nextNode()){if(!n.nodeValue.trim())continue;var l=n.nodeValue.length;"
            + "if(c+l>=q*t){var o=Math.max(0,Math.min(l-1,Math.floor(q*t-c)));r.setStart(n,o);r.setEnd(n,o+1);"
            + "return Math.floor((r.getBoundingClientRect().left+d.scrollLeft+1)/W)}c+=l}return -1})(";

    private Book book;
    private Content content;
    private PdfRenderer pdf;
    private ParcelFileDescriptor pdfFile;
    private SharedPreferences prefs;

    private FrameLayout root;
    private WebView web;
    private ImageView pdfView;
    private Bitmap pdfBitmap;
    private TextView footerPage;
    private TextView footerTime;
    private TextView footerBattery;
    private FrameLayout menu;
    private TextView menuPosition;
    private Ui.Progress menuBar;
    private View typeCard;
    private boolean typeOpen;
    private LinearLayout listenCard;
    private boolean listenOpen;
    private View tocView;

    private int chapter;
    private int page;
    private int pages = 1;
    private float pendingFraction;
    private boolean ready;
    private int tocPage;
    private float downX;
    private float downY;
    private boolean menuDismissed;
    private long resumedAt;
    private Ui.Swipe swipe;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setWindowAnimations(0);
        swipe = new Ui.Swipe(this);
        prefs = getSharedPreferences("reader", MODE_PRIVATE);
        book = Library.find(this, getIntent().getStringExtra("id"));
        if (book == null) {
            finish();
            return;
        }
        chapter = book.chapter;
        pendingFraction = book.fraction;
        buildViews();
        new Thread(this::openBook).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumedAt = System.currentTimeMillis();
        updateClock();
        Narrator.listen(new Narrator.Listener() {
            @Override public void reached(String bookId, int where, float ratio) {
                if (bookId.equals(book.id)) follow(where, ratio);
            }

            @Override public void changed() {
                renderListen();
            }
        });
        renderListen();
        catchUp();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Library.addReading(this, System.currentTimeMillis() - resumedAt);
        Narrator.listen(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (pdf != null) pdf.close();
            if (pdfFile != null) pdfFile.close();
        } catch (IOException ignored) {
        }
        if (web != null) web.destroy();
    }

    @Override
    public void onBackPressed() {
        if (tocView != null) {
            closeToc();
        } else if (typeOpen) {
            toggleType();
        } else if (listenOpen) {
            toggleListen();
        } else if (menu.getVisibility() == View.VISIBLE) {
            hideMenu();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        int direction = swipe.track(event);
        if (direction != 0 && tocView != null) {
            event.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(event);
            turnToc(-direction);
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    private void buildViews() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.WHITE);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        FrameLayout pageArea = new FrameLayout(this);
        if (book.format.equals("PDF")) {
            pdfView = new ImageView(this);
            pageArea.addView(pdfView, new FrameLayout.LayoutParams(-1, -1));
        } else {
            web = new WebView(this);
            setUpWeb();
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -1);
            params.setMargins(dp(30), dp(30), dp(30), dp(6));
            pageArea.addView(web, params);
        }
        column.addView(pageArea, new LinearLayout.LayoutParams(-1, 0, 1));
        column.addView(footer());
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        View touch = new View(this);
        touch.setOnTouchListener(this::onPageTouch);
        root.addView(touch, new FrameLayout.LayoutParams(-1, -1));

        menu = buildMenu();
        menu.setVisibility(View.GONE);
        root.addView(menu, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    private void setUpWeb() {
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setBlockNetworkLoads(true);
        settings.setSupportZoom(false);
        settings.setTextZoom(100);
        web.setInitialScale(100);
        web.setBackgroundColor(Color.WHITE);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                view.postDelayed(() -> awaitFont(FONT_WAIT_TRIES), 80);
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }
        });
    }

    private View footer() {
        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(30), dp(6), dp(30), dp(14));
        footerTime = Ui.text(this, "", 12, Ui.GRAY, Ui.REGULAR);
        footer.addView(footerTime, new LinearLayout.LayoutParams(dp(56), -2));
        footerPage = Ui.text(this, "", 12, Ui.GRAY, Ui.REGULAR);
        footerPage.setGravity(Gravity.CENTER);
        footer.addView(footerPage, new LinearLayout.LayoutParams(0, -2, 1));
        footerBattery = Ui.text(this, "", 12, Ui.GRAY, Ui.REGULAR);
        footerBattery.setGravity(Gravity.END);
        footer.addView(footerBattery, new LinearLayout.LayoutParams(dp(56), -2));
        return footer;
    }

    /** Refreshed with the page rather than by the minute, so the panel stays still while you read. */
    private void updateClock() {
        footerTime.setText(new SimpleDateFormat("H:mm", Locale.ROOT).format(new Date()));
        footerBattery.setText(Ui.batteryLevel(this) + "%");
    }

    private FrameLayout buildMenu() {
        FrameLayout layer = new FrameLayout(this);
        layer.addView(topBar(), new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        layer.addView(bottomPanel(), new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        return layer;
    }

    private void hideMenu() {
        menu.setVisibility(View.GONE);
        if (typeOpen) toggleType();
        if (listenOpen) toggleListen();
    }

    private void toggleType() {
        if (listenOpen) toggleListen();
        typeOpen = !typeOpen;
        typeCard.setVisibility(typeOpen ? View.VISIBLE : View.GONE);
    }

    private void toggleListen() {
        if (typeOpen) toggleType();
        listenOpen = !listenOpen;
        listenCard.setVisibility(listenOpen ? View.VISIBLE : View.GONE);
    }

    private void renderListen() {
        if (listenCard == null) return;
        listenCard.removeAllViews();
        boolean active = Narrator.active(book.id);
        boolean paused = active && Narrator.paused();
        String status = paused ? "已暂停" : active ? "正在朗读" : Narrator.problem() != null ? Narrator.problem()
                : Speech.configured(this) ? "从这一页开始朗读" : "先填写语音接口";
        listenCard.addView(Ui.text(this, status, 14, Ui.GRAY, Ui.MEDIUM));
        String[] labels = active ? new String[]{paused ? "继续" : "暂停", "停止"} : new String[]{"开始朗读", "接口设置"};
        Ui.Choice choice = active ? this::controlListening : this::chooseListening;
        listenCard.addView(Ui.segmented(this, labels, -1, choice), spaced(-1, -2, 12, 0));
    }

    private void controlListening(int choice) {
        if (choice == 0) Narrator.toggle(this);
        else Narrator.stop(this);
    }

    private void chooseListening(int choice) {
        if (choice == 1 || !Speech.configured(this)) {
            startActivity(new Intent(this, VoiceActivity.class).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } else if (ready) {
            web.evaluateJavascript(FIRST_VISIBLE, value -> {
                float ratio;
                try {
                    ratio = Float.parseFloat(value);
                } catch (NumberFormatException e) {
                    ratio = 0;
                }
                Narrator.read(this, book, chapter, ratio);
                hideMenu();
            });
        }
    }

    /** The voice carries on with the screen off, so the page has to find it again on the way back. */
    private void catchUp() {
        Book saved = Library.find(this, book.id);
        if (saved != null && saved.lastRead > book.lastRead) follow(saved.chapter, saved.fraction);
    }

    /** Keeps the page under the voice; a page turn only happens when the sentence has moved on to it. */
    private void follow(int where, float ratio) {
        if (!ready) return;
        if (where != chapter) {
            chapter = where;
            pendingFraction = ratio;
            loadChapter();
            return;
        }
        web.evaluateJavascript(PAGE_AT + ratio + ")", value -> {
            try {
                int target = Integer.parseInt(value);
                if (target >= 0 && target < pages && target != page) {
                    page = target;
                    showWebPage();
                }
            } catch (NumberFormatException ignored) {
            }
        });
    }

    private View topBar() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(Ui.WHITE);
        column.setClickable(true);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(12), dp(24), dp(12));

        View back = Ui.icon(this, R.drawable.ic_back, Ui.BLACK);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(30), dp(30)));
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));

        if (!book.format.equals("PDF")) {
            View list = Ui.icon(this, R.drawable.ic_list, Ui.BLACK);
            list.setOnClickListener(v -> showToc());
            bar.addView(list, new LinearLayout.LayoutParams(dp(28), dp(28)));
            TextView listen = Ui.text(this, "听", 21, Ui.BLACK, Ui.MEDIUM);
            listen.setPadding(dp(30), 0, 0, 0);
            listen.setOnClickListener(v -> toggleListen());
            bar.addView(listen);
            TextView type = Ui.text(this, "Aa", 21, Ui.BLACK, Ui.MEDIUM);
            type.setPadding(dp(30), 0, 0, 0);
            type.setOnClickListener(v -> toggleType());
            bar.addView(type);
        }
        column.addView(bar);
        column.addView(Ui.hairline(this), Ui.hairlineParams());
        return column;
    }

    /** Where you are in the book; how the text is set stays out of sight until Aa asks for it. */
    private View bottomPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.addView(Ui.hairline(this), Ui.hairlineParams());
        if (!book.format.equals("PDF")) {
            typeCard = typeControls();
            typeCard.setVisibility(typeOpen ? View.VISIBLE : View.GONE);
            panel.addView(typeCard);
            listenCard = new LinearLayout(this);
            listenCard.setOrientation(LinearLayout.VERTICAL);
            listenCard.setBackgroundColor(Ui.WHITE);
            listenCard.setPadding(dp(30), dp(20), dp(30), dp(6));
            listenCard.setClickable(true);
            listenCard.setVisibility(listenOpen ? View.VISIBLE : View.GONE);
            panel.addView(listenCard);
            renderListen();
        }
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.VERTICAL);
        strip.setBackgroundColor(Ui.WHITE);
        strip.setPadding(dp(30), dp(14), dp(30), dp(10));
        strip.setClickable(true);
        menuPosition = Ui.oneLine(Ui.text(this, "", 14, Ui.GRAY, Ui.MEDIUM));
        strip.addView(menuPosition);
        menuBar = new Ui.Progress(this);
        menuBar.setOnTouchListener(this::onSeek);
        strip.addView(menuBar, spaced(-1, dp(30), 2, 0));
        panel.addView(strip);
        return panel;
    }

    private View typeControls() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Ui.WHITE);
        card.setPadding(dp(30), dp(20), dp(30), dp(6));
        card.setClickable(true);
        card.addView(fontStepper());
        card.addView(Ui.segmented(this, FACES, prefs.getInt("face", 0), this::changeFace), spaced(-1, -2, 12, 0));
        card.addView(Ui.segmented(this, SPACINGS, prefs.getInt("spacing", 1), this::changeSpacing), spaced(-1, -2, 12, 0));
        return card;
    }

    private View fontStepper() {
        LinearLayout stepper = new LinearLayout(this);
        stepper.setGravity(Gravity.CENTER_VERTICAL);
        stepper.setPadding(dp(3), dp(3), dp(3), dp(3));
        stepper.setBackground(Ui.round(this, Ui.FILL, 18, 0));
        TextView smaller = Ui.text(this, "A", 14, Ui.BLACK, Ui.MEDIUM);
        TextView bigger = Ui.text(this, "A", 21, Ui.BLACK, Ui.MEDIUM);
        for (TextView cell : new TextView[]{smaller, bigger}) {
            cell.setGravity(Gravity.CENTER);
            cell.setMinHeight(dp(30));
        }
        smaller.setOnClickListener(v -> changeFont(-1));
        bigger.setOnClickListener(v -> changeFont(1));
        stepper.addView(smaller, new LinearLayout.LayoutParams(0, -2, 1));
        View divider = new View(this);
        divider.setBackgroundColor(Ui.LINE);
        stepper.addView(divider, new LinearLayout.LayoutParams(1, dp(18)));
        stepper.addView(bigger, new LinearLayout.LayoutParams(0, -2, 1));
        return stepper;
    }

    private boolean onSeek(View view, MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_UP || !ready) return true;
        float target = Math.max(0, Math.min(1, event.getX() / view.getWidth()));
        if (pdf != null) {
            page = Math.min(pages - 1, (int) (target * pages));
            showPdfPage();
        } else {
            float position = target * content.chapters();
            chapter = Math.min(content.chapters() - 1, (int) position);
            pendingFraction = position - chapter;
            loadChapter();
        }
        return true;
    }

    private void openBook() {
        try {
            if (book.format.equals("PDF")) {
                pdfFile = ParcelFileDescriptor.open(new File(book.path), ParcelFileDescriptor.MODE_READ_ONLY);
                pdf = new PdfRenderer(pdfFile);
            } else {
                content = Library.open(this, book);
            }
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (pdf != null) {
                    pages = pdf.getPageCount();
                    page = Math.round(pendingFraction * (pages - 1));
                    root.post(this::showPdfPage);
                } else {
                    chapter = Math.min(chapter, content.chapters() - 1);
                    root.post(this::loadChapter);
                }
            });
        } catch (Exception e) {
            runOnUiThread(() -> footerPage.setText("无法打开这本书: " + e.getMessage()));
        }
    }

    private void loadChapter() {
        ready = false;
        try {
            String style = "<style>" + css() + "</style>";
            String html = content.html(chapter).replaceAll("(?i)<meta[^>]*viewport[^>]*>", "");
            int head = html.toLowerCase(Locale.ROOT).indexOf("</head>");
            html = head >= 0 ? html.substring(0, head) + style + html.substring(head) : style + html;
            web.loadDataWithBaseURL(Uri.fromFile(content.base(chapter)).toString(), html, "text/html", "UTF-8", null);
        } catch (IOException e) {
            footerPage.setText("这一章读取失败");
        }
    }

    private String css() {
        int w = web.getWidth();
        int h = web.getHeight();
        int font = dp(prefs.getInt("font", DEFAULT_FONT));
        double height = LINE_HEIGHTS[prefs.getInt("spacing", 1)];
        String family = prefs.getInt("face", 0) == 0 ? "InkSerif,serif" : "sans-serif";
        return "@font-face{font-family:InkSerif;src:url('file:///android_asset/serif.otf');}"
                + "html,body{margin:0!important;padding:0!important;background:#fff!important;}"
                + "html{height:" + h + "px;overflow:hidden;}"
                + "#ink{overflow:hidden;width:" + w + "px;height:" + h + "px;-webkit-column-width:" + w + "px;column-width:" + w + "px;"
                + "-webkit-column-gap:0;column-gap:0;-webkit-column-fill:auto;column-fill:auto;}"
                + "body{width:" + w + "px;height:" + h + "px;overflow:hidden;font-size:" + font + "px!important;line-height:" + height + "!important;color:#000!important;"
                + "text-align:justify;word-wrap:break-word;}"
                + "body,body *{font-family:" + family + "!important;color:#000!important;}"
                + "p{margin:0 0 .5em;}p.t{text-indent:2em;}"
                + "h1,h2,h3{line-height:1.35;margin:.2em 0 .8em;}"
                + "img,svg{max-width:" + w + "px!important;max-height:" + (h - 8) + "px!important;height:auto;}"
                + "a,a *,font{color:#000!important;}";
    }

    /** Pagination must wait for the body font, or the page count is taken from the fallback's metrics. */
    private void awaitFont(int tries) {
        if (isDestroyed()) return;
        web.evaluateJavascript("document.fonts.status", value -> {
            if (tries > 0 && "\"loading\"".equals(value)) {
                web.postDelayed(() -> awaitFont(tries - 1), 50);
            } else {
                measure();
            }
        });
    }

    private void measure() {
        web.evaluateJavascript(MEASURE, value -> {
            try {
                pages = Math.max(1, (int) Math.ceil((Double.parseDouble(value) - 2) / web.getWidth()));
            } catch (NumberFormatException e) {
                pages = 1;
            }
            page = Math.min(pages - 1, Math.round(pendingFraction * (pages - 1)));
            ready = true;
            showWebPage();
        });
    }

    private void showWebPage() {
        web.evaluateJavascript("document.getElementById('ink').scrollLeft=" + page * web.getWidth(), null);
        updateFooter();
        savePosition();
    }

    private void showPdfPage() {
        try (PdfRenderer.Page current = pdf.openPage(page)) {
            int w = pdfView.getWidth();
            int h = pdfView.getHeight();
            float scale = Math.min(w / (float) current.getWidth(), h / (float) current.getHeight());
            Bitmap bitmap = Bitmap.createBitmap(Math.max(1, Math.round(current.getWidth() * scale)),
                    Math.max(1, Math.round(current.getHeight() * scale)), Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE);
            current.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            pdfView.setImageBitmap(bitmap);
            if (pdfBitmap != null) pdfBitmap.recycle();
            pdfBitmap = bitmap;
        }
        ready = true;
        updateFooter();
        savePosition();
    }

    private void turn(int delta) {
        if (!ready) return;
        int next = page + delta;
        if (pdf != null) {
            if (next >= 0 && next < pages) {
                page = next;
                showPdfPage();
            }
        } else if (next >= 0 && next < pages) {
            page = next;
            showWebPage();
        } else if (next < 0 && chapter > 0) {
            chapter--;
            pendingFraction = 1;
            loadChapter();
        } else if (next >= pages && chapter < content.chapters() - 1) {
            chapter++;
            pendingFraction = 0;
            loadChapter();
        }
    }

    private float fraction() {
        return pages <= 1 ? 0 : page / (float) (pages - 1);
    }

    private float progress() {
        if (pdf != null) return (page + 1) / (float) pages;
        return (chapter + (page + 1) / (float) pages) / content.chapters();
    }

    private void updateFooter() {
        float progress = progress();
        footerPage.setText((page + 1) + " / " + pages);
        updateClock();
        String where = pdf != null ? Library.shortTitle(book) : chapterTitle();
        menuBar.set(progress);
        menuPosition.setText(where + " · " + Math.round(progress * 100) + "%");
    }

    private String chapterTitle() {
        String title = Library.shortTitle(book);
        for (Content.TocEntry entry : content.toc()) {
            if (entry.chapter > chapter) break;
            title = entry.title;
        }
        return title;
    }

    private void savePosition() {
        book.chapter = chapter;
        book.fraction = fraction();
        book.progress = progress();
        book.lastRead = System.currentTimeMillis();
        Library.update(this, book);
    }

    private boolean onPageTouch(View view, MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            downY = event.getY();
            menuDismissed = menu.getVisibility() == View.VISIBLE;
            hideMenu();
        } else if (event.getAction() == MotionEvent.ACTION_UP && !menuDismissed) {
            float dx = event.getX() - downX;
            float third = view.getWidth() / 3f;
            if (Math.abs(dx) > dp(50)) {
                turn(dx < 0 ? 1 : -1);
            } else if (downX < third) {
                turn(-1);
            } else if (downX > third * 2) {
                turn(1);
            } else {
                menu.setVisibility(View.VISIBLE);
            }
        }
        return true;
    }

    private void changeFont(int delta) {
        int font = Math.max(MIN_FONT, Math.min(MAX_FONT, prefs.getInt("font", DEFAULT_FONT) + delta));
        prefs.edit().putInt("font", font).apply();
        relayout();
    }

    private void changeFace(int face) {
        prefs.edit().putInt("face", face).apply();
        rebuildMenu();
        relayout();
    }

    private void changeSpacing(int spacing) {
        prefs.edit().putInt("spacing", spacing).apply();
        rebuildMenu();
        relayout();
    }

    private void rebuildMenu() {
        int index = root.indexOfChild(menu);
        root.removeView(menu);
        menu = buildMenu();
        root.addView(menu, index, new FrameLayout.LayoutParams(-1, -1));
        updateFooter();
    }

    private void relayout() {
        if (web != null && ready) {
            pendingFraction = fraction();
            loadChapter();
        }
    }

    private void showToc() {
        if (content == null) return;
        tocPage = currentEntry() / TOC_PER_PAGE;
        renderToc();
    }

    private void turnToc(int delta) {
        int next = tocPage + delta;
        if (next < 0 || next >= tocPages()) return;
        tocPage = next;
        renderToc();
    }

    private int tocPages() {
        return Math.max(1, (content.toc().size() + TOC_PER_PAGE - 1) / TOC_PER_PAGE);
    }

    private void renderToc() {
        if (tocView != null) root.removeView(tocView);
        final List<Content.TocEntry> toc = content.toc();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Ui.WHITE);
        panel.setPadding(dp(Ui.MARGIN), dp(12), dp(Ui.MARGIN), dp(12));
        panel.setClickable(true);

        LinearLayout back = new LinearLayout(this);
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.addView(Ui.icon(this, R.drawable.ic_back, Ui.BLACK), new LinearLayout.LayoutParams(dp(24), dp(24)));
        back.addView(Ui.text(this, "返回", 17, Ui.BLACK, Ui.MEDIUM));
        back.setOnClickListener(v -> closeToc());
        LinearLayout top = new LinearLayout(this);
        top.addView(back);
        panel.addView(top, spaced(-1, -2, 0, 16));
        panel.addView(Ui.header(this, Library.shortTitle(book), "目录"));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int start = tocPage * TOC_PER_PAGE;
        int current = currentEntry();
        for (int i = start; i < start + TOC_PER_PAGE; i++) {
            list.addView(i < toc.size() ? tocRow(toc.get(i), i == current) : new View(this),
                    new LinearLayout.LayoutParams(-1, 0, 1));
            if (i < toc.size() - 1 && i < start + TOC_PER_PAGE - 1) list.addView(Ui.hairline(this), Ui.hairlineParams());
        }
        panel.addView(list, spaced(-1, 0, 12, 0, 1));
        panel.addView(Ui.pager(this, tocPage, tocPages(), this::turnToc), new LinearLayout.LayoutParams(-1, dp(42)));
        tocView = panel;
        root.addView(tocView, new FrameLayout.LayoutParams(-1, -1));
    }

    private int currentEntry() {
        List<Content.TocEntry> toc = content.toc();
        int current = 0;
        for (int i = 0; i < toc.size(); i++) {
            if (toc.get(i).chapter <= chapter) current = i;
        }
        return current;
    }

    private View tocRow(final Content.TocEntry entry, boolean here) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = Ui.oneLine(Ui.text(this, entry.title, 18, Ui.BLACK, here ? Ui.BOLD : Ui.REGULAR));
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        if (here) {
            TextView mark = Ui.text(this, "正在读", 13, Ui.GRAY, Ui.MEDIUM);
            mark.setPadding(dp(14), 0, 0, 0);
            row.addView(mark);
        }
        row.setOnClickListener(v -> {
            closeToc();
            hideMenu();
            chapter = entry.chapter;
            pendingFraction = 0;
            loadChapter();
        });
        return row;
    }

    private void closeToc() {
        root.removeView(tocView);
        tocView = null;
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
