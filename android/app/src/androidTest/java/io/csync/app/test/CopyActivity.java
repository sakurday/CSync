package io.csync.app.test;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

/** Pure Java: this separate test-APK process does not inherit target APK's Kotlin runtime. */
public final class CopyActivity extends Activity {
    private boolean copied;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView caption = new TextView(this);
        caption.setText("CSync 实机测试\n\n这是另一个应用，复制测试文本。\nCSync 此时位于后台。");
        caption.setTextSize(22);
        caption.setPadding(32, 64, 32, 32);
        setContentView(caption);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && !copied) {
            copied = true;
            String text = "CSync Android→PC 后台验证 🙂 [" + getIntent().getStringExtra("runId") + "]";
            ClipboardManager manager = getSystemService(ClipboardManager.class);
            manager.setPrimaryClip(ClipData.newPlainText("CSync test", text));
            ClipData data = manager.getPrimaryClip();
            boolean success = data != null && data.getItemCount() > 0 && text.contentEquals(data.getItemAt(0).getText());
            sendBroadcast(new Intent("io.csync.test.COPIED").setPackage("io.csync.app")
                    .putExtra("nonce", getIntent().getStringExtra("nonce")).putExtra("success", success));
        }
    }
}
