package cc.hifly.xrayandroid;

import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        wirePlaceholderButton(R.id.buttonImport, "订阅导入待接入");
        wirePlaceholderButton(R.id.buttonPool, "节点池界面待接入");
        wirePlaceholderButton(R.id.buttonGateway, "透明网关控制待接入");
    }

    private void wirePlaceholderButton(int buttonId, String message) {
        Button button = findViewById(buttonId);
        button.setOnClickListener(view ->
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }
}
