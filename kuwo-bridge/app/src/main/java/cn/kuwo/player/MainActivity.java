package cn.kuwo.player;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private TextView status;
    private Runnable statusTicker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(getString(cn.kuwo.player.R.string.bridge_title));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 28, 32, 28);

        TextView title = new TextView(this);
        title.setText(getString(cn.kuwo.player.R.string.bridge_title));
        title.setTextSize(22f);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = new TextView(this);
        hint.setText("包名: cn.kuwo.player\n先启用通知读取权限，再播放 Apple Music。此版本支持手动 LRC 用于车机协议测试。");
        hint.setPadding(0, 18, 0, 18);
        root.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        Button settings = new Button(this);
        settings.setText("启用通知读取权限");
        settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            }
        });
        root.addView(settings, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setTextSize(15f);
        status.setPadding(0, 18, 0, 18);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        final EditText lyrics = new EditText(this);
        lyrics.setHint("可选：粘贴 LRC 歌词，例如 [00:03.00]第一句");
        lyrics.setGravity(android.view.Gravity.TOP);
        lyrics.setMinLines(5);
        root.addView(lyrics, new LinearLayout.LayoutParams(-1, 0, 1f));

        Button sample = new Button(this);
        sample.setText("填入示例歌词");
        sample.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                lyrics.setText("[00:00.00]歌词桥接测试\n[00:05.00]第二句歌词\n[00:10.00]快进后应切换到这一句");
            }
        });
        root.addView(sample, new LinearLayout.LayoutParams(-1, -2));

        Button apply = new Button(this);
        apply.setText("发送歌词到代理会话");
        apply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BridgeCore.get(MainActivity.this).setManualLyrics(lyrics.getText().toString());
                updateStatus();
            }
        });
        root.addView(apply, new LinearLayout.LayoutParams(-1, -2));

        Button stop = new Button(this);
        stop.setText("停止酷我代理会话");
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BridgeCore.get(MainActivity.this).clearProxy();
                updateStatus();
            }
        });
        root.addView(stop, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
        statusTicker = new Runnable() {
            @Override
            public void run() {
                updateStatus();
                handler.postDelayed(this, 1000L);
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
        if (statusTicker != null) {
            handler.removeCallbacks(statusTicker);
            handler.post(statusTicker);
        }
    }

    @Override
    protected void onPause() {
        if (statusTicker != null) {
            handler.removeCallbacks(statusTicker);
        }
        super.onPause();
    }

    private void updateStatus() {
        if (status != null) {
            status.setText(BridgeCore.get(this).status());
        }
    }
}
