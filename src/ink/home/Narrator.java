package ink.home;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.Semaphore;

/** Reads a book aloud from a foreground service, so it carries on with the screen off. */
public class Narrator extends Service {
    private static final String ACTION_READ = "read";
    private static final String ACTION_TOGGLE = "toggle";
    private static final String ACTION_STOP = "stop";
    private static final String CHANNEL = "reading";
    private static final int NOTIFICATION = 1;
    private static final int AHEAD = 3;
    private static final int ATTEMPTS = 3;
    private static final int RETRY_WAIT = 3000;

    /** Told, on the main thread, where the voice is and when its state changes. */
    interface Listener {
        void reached(String bookId, int chapter, float ratio);

        void changed();
    }

    private static final class Piece {
        final int chapter;
        final int chapters;
        final float ratio;
        final File audio;

        Piece(int chapter, int chapters, float ratio, File audio) {
            this.chapter = chapter;
            this.chapters = chapters;
            this.ratio = ratio;
            this.audio = audio;
        }
    }

    private static Narrator running;
    private static Listener listener;
    private static String problem;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Piece> queue = new ArrayDeque<>();
    private final Semaphore room = new Semaphore(AHEAD);
    private final AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();

    private Book book;
    private MediaPlayer player;
    private Thread worker;
    private boolean paused;
    private boolean finished;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private AudioFocusRequest focus;

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            setPaused(true);
        }
    };

    static void read(Context context, Book book, int chapter, float ratio) {
        context.startForegroundService(new Intent(context, Narrator.class).setAction(ACTION_READ)
                .putExtra("id", book.id).putExtra("chapter", chapter).putExtra("ratio", ratio));
    }

    static void toggle(Context context) {
        context.startService(new Intent(context, Narrator.class).setAction(ACTION_TOGGLE));
    }

    static void stop(Context context) {
        context.startService(new Intent(context, Narrator.class).setAction(ACTION_STOP));
    }

    static boolean active(String bookId) {
        return running != null && running.book != null && running.book.id.equals(bookId);
    }

    static boolean paused() {
        return running != null && running.paused;
    }

    static String problem() {
        return problem;
    }

    static void listen(Listener value) {
        listener = value;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
        wake = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ink:narrator");
        wifi = ((WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE))
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ink:narrator");
        wake.setReferenceCounted(false);
        wifi.setReferenceCounted(false);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(change -> {
                    if (change < 0) setPaused(true);
                }).build();
        registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_STOP : intent.getAction();
        if (ACTION_READ.equals(action)) {
            startForeground(NOTIFICATION, notification());
            begin(intent.getStringExtra("id"), intent.getIntExtra("chapter", 0), intent.getFloatExtra("ratio", 0));
        } else if (ACTION_TOGGLE.equals(action)) {
            setPaused(!paused);
        } else {
            end(null);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        halt();
        unregisterReceiver(noisy);
        running = null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification notification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "朗读", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, Narrator.class).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_app).setContentTitle("墨页正在朗读")
                .setContentText("点此停止").setContentIntent(stop).setOngoing(true).build();
    }

    private void begin(String id, int chapter, float ratio) {
        halt();
        problem = null;
        book = Library.find(this, id);
        if (book == null) {
            end("找不到这本书");
            return;
        }
        paused = false;
        finished = false;
        room.drainPermits();
        room.release(AHEAD);
        hold(true);
        final Book target = book;
        worker = new Thread(() -> produce(target, chapter, ratio));
        worker.start();
        announce();
    }

    /** Stays a few pieces ahead of the voice, so a slow reply never leaves a gap. */
    private void produce(Book target, int firstChapter, float firstRatio) {
        Thread self = Thread.currentThread();
        File folder = new File(getCacheDir(), "speech");
        folder.mkdirs();
        File[] stale = folder.listFiles();
        if (stale != null) for (File file : stale) file.delete();
        try {
            Content content = Library.open(this, target);
            int count = 0;
            float ratio = firstRatio;
            for (int chapter = firstChapter; chapter < content.chapters(); chapter++) {
                String text = Speech.plain(content.html(chapter));
                int start = Speech.sentenceStart(text, Math.round(ratio * text.length()));
                ratio = 0;
                while (start < text.length()) {
                    int end = Speech.pieceEnd(text, start);
                    String piece = text.substring(start, end).trim();
                    if (Speech.sayable(piece)) {
                        room.acquire();
                        File audio = new File(folder, count++ + ".audio");
                        fetch(piece, audio);
                        Piece ready = new Piece(chapter, content.chapters(), start / (float) text.length(), audio);
                        main.post(() -> {
                            if (worker == self) offer(ready);
                        });
                    }
                    start = end;
                }
            }
            main.post(() -> {
                if (worker == self) {
                    finished = true;
                    playNext();
                }
            });
        } catch (InterruptedException ignored) {
        } catch (Exception e) {
            String message = e instanceof IOException && e.getMessage() != null ? e.getMessage() : "读不出这本书";
            main.post(() -> {
                if (worker == self) end(message);
            });
        }
    }

    private void fetch(String piece, File audio) throws IOException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                Speech.synthesize(this, piece, audio);
                return;
            } catch (Speech.Refused e) {
                throw e;
            } catch (IOException e) {
                if (attempt == ATTEMPTS) throw e;
                Thread.sleep(RETRY_WAIT);
            }
        }
    }

    private void offer(Piece piece) {
        queue.add(piece);
        playNext();
    }

    private void playNext() {
        if (player != null || paused) return;
        Piece next = queue.poll();
        if (next == null) {
            if (finished) end(null);
            return;
        }
        try {
            ((AudioManager) getSystemService(AUDIO_SERVICE)).requestAudioFocus(focus);
            player = new MediaPlayer();
            player.setAudioAttributes(attributes);
            player.setOnCompletionListener(done -> advance(next));
            player.setOnErrorListener((failed, what, extra) -> {
                advance(next);
                return true;
            });
            player.setDataSource(next.audio.getPath());
            player.prepare();
            player.start();
        } catch (IOException e) {
            end("这段音频无法播放，接口返回的格式可能不对");
            return;
        }
        remember(next);
        if (listener != null) listener.reached(book.id, next.chapter, next.ratio);
    }

    private void advance(Piece piece) {
        releasePlayer();
        piece.audio.delete();
        room.release();
        playNext();
    }

    private void remember(Piece piece) {
        book.chapter = piece.chapter;
        book.fraction = piece.ratio;
        book.progress = (piece.chapter + piece.ratio) / piece.chapters;
        book.lastRead = System.currentTimeMillis();
        Library.update(this, book);
    }

    private void setPaused(boolean value) {
        if (book == null || paused == value) return;
        paused = value;
        hold(!value);
        if (player != null) {
            if (value) player.pause();
            else player.start();
        } else if (!value) {
            playNext();
        }
        announce();
    }

    /** Only speaking needs the radio and the CPU awake; a paused book should not drain the battery. */
    private void hold(boolean on) {
        if (on) {
            wake.acquire();
            wifi.acquire();
        } else {
            if (wake.isHeld()) wake.release();
            if (wifi.isHeld()) wifi.release();
        }
    }

    private void halt() {
        if (worker != null) worker.interrupt();
        worker = null;
        releasePlayer();
        queue.clear();
        hold(false);
        ((AudioManager) getSystemService(AUDIO_SERVICE)).abandonAudioFocusRequest(focus);
    }

    private void releasePlayer() {
        if (player == null) return;
        player.release();
        player = null;
    }

    private void end(String message) {
        problem = message;
        halt();
        book = null;
        stopForeground(true);
        stopSelf();
        announce();
    }

    private void announce() {
        if (listener != null) listener.changed();
    }
}
