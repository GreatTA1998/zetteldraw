package com.zetteldraw.v2;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

/**
 * UI shell host. Will bind CollectionNav + ToolController + InkSurface
 * per docs/greenfield/architecture.md. No legacy CanvasActivity port.
 */
public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }
}
