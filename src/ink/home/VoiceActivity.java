package ink.home;

import android.app.Activity;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Where the speech API is filled in, with a sample to prove it works. */
public class VoiceActivity extends Activity {
    private static final String SAMPLE = "这是墨页的朗读测试。";
    private static final String IMPORT = "Download/inkvoice.json";

    private EditText url;
    private EditText key;
    private EditText model;
    private EditText voice;
    private TextView status;
    private MediaPlayer player;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setWindowAnimations(0);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setBackgroundColor(Ui.WHITE);
        form.setPadding(dp(Ui.MARGIN), dp(12), dp(Ui.MARGIN), dp(24));

        LinearLayout back = new LinearLayout(this);
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.addView(Ui.icon(this, R.drawable.ic_back, Ui.BLACK), new LinearLayout.LayoutParams(dp(24), dp(24)));
        back.addView(Ui.text(this, "返回", 17, Ui.BLACK, Ui.MEDIUM));
        back.setOnClickListener(v -> finish());
        LinearLayout top = new LinearLayout(this);
        top.addView(back);
        form.addView(top);
        form.addView(spaced(Ui.header(this, "OpenAI 兼容的语音接口", "朗读"), 16));

        SharedPreferences prefs = Speech.prefs(this);
        url = field(form, "接口地址", "https://api.openai.com/v1/audio/speech", prefs.getString(Speech.URL_KEY, ""), false);
        key = field(form, "API Key", "没有可留空", prefs.getString(Speech.API_KEY, ""), true);
        model = field(form, "模型", "tts-1", prefs.getString(Speech.MODEL_KEY, ""), false);
        voice = field(form, "声音", "alloy", prefs.getString(Speech.VOICE_KEY, ""), false);

        form.addView(spaced(Ui.segmented(this, new String[]{"试听", "导入配置"}, -1, this::act), 22));
        status = Ui.text(this, "", 14, Ui.GRAY, Ui.REGULAR);
        form.addView(spaced(status, 12));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.WHITE);
        scroll.addView(form);
        setContentView(scroll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPlayer();
    }

    private EditText field(LinearLayout form, String label, String hint, String value, boolean secret) {
        form.addView(spaced(Ui.text(this, label, 14, Ui.GRAY, Ui.MEDIUM), 16));
        EditText input = new EditText(this);
        input.setBackground(Ui.round(this, Ui.FILL, 14, 0));
        input.setPadding(dp(14), dp(11), dp(14), dp(11));
        input.setTextSize(15);
        input.setTextColor(Ui.BLACK);
        input.setHintTextColor(Ui.GRAY);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD));
        input.setText(value);
        form.addView(spaced(input, 6));
        return input;
    }

    private void save() {
        Speech.prefs(this).edit()
                .putString(Speech.URL_KEY, url.getText().toString().trim())
                .putString(Speech.API_KEY, key.getText().toString().trim())
                .putString(Speech.MODEL_KEY, model.getText().toString().trim())
                .putString(Speech.VOICE_KEY, voice.getText().toString().trim())
                .apply();
    }

    private void act(int choice) {
        if (choice == 0) sample();
        else importFile();
    }

    /** A long key is miserable to type on this panel; the file is deleted so the key does not linger in shared storage. */
    private void importFile() {
        File file = new File(Environment.getExternalStorageDirectory(), IMPORT);
        try {
            JSONObject json = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            url.setText(json.optString("url"));
            key.setText(json.optString("key"));
            model.setText(json.optString("model"));
            voice.setText(json.optString("voice"));
            save();
            file.delete();
            status.setText("已导入，文件已删除");
        } catch (IOException | JSONException e) {
            status.setText("没有读到 " + IMPORT);
        }
    }

    private void sample() {
        save();
        if (!Speech.configured(this)) {
            status.setText("先填写接口地址");
            return;
        }
        status.setText("正在请求…");
        File audio = new File(getCacheDir(), "sample.audio");
        new Thread(() -> {
            try {
                Speech.synthesize(this, SAMPLE, audio);
                runOnUiThread(() -> play(audio));
            } catch (IOException | RuntimeException e) {
                runOnUiThread(() -> status.setText("失败: " + e.getMessage()));
            }
        }).start();
    }

    private void play(File audio) {
        if (isDestroyed()) return;
        stopPlayer();
        try {
            player = new MediaPlayer();
            player.setDataSource(audio.getPath());
            player.prepare();
            player.start();
            status.setText("接口正常，正在播放");
        } catch (IOException e) {
            status.setText("收到了数据，但无法播放");
        }
    }

    private void stopPlayer() {
        if (player == null) return;
        player.release();
        player = null;
    }

    private <T extends View> T spaced(T view, int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(topDp);
        view.setLayoutParams(params);
        return view;
    }

    private int dp(float value) {
        return Ui.dp(this, value);
    }
}
