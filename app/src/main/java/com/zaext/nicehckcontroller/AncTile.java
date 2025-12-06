package com.zaext.nicehckcontroller;

import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

import androidx.annotation.RequiresApi;

@RequiresApi(api = Build.VERSION_CODES.N)
public class AncTile extends TileService {

    // 0: 关闭, 1: 降噪, 2: 通透
    private int currentMode = 0;

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTileUI();
    }

    @Override
    public void onClick() {
        BluetoothController controller = BluetoothController.getInstance(this);

        // 关键：每次点击都尝试确保连接
        if (!controller.isConnected()) {
            // 尝试连接，如果失败则提示
            if (!controller.connectDefaultDevice()) {
                Toast.makeText(this, "未连接耳机，请先确保耳机已配对", Toast.LENGTH_SHORT).show();
                updateTileUI(); // 更新为未连接状态
                return;
            }
        }

        // 循环切换模式
        currentMode++;
        if (currentMode > 2) currentMode = 0;

        switch (currentMode) {
            case 1: // 降噪 (深度)
                controller.sendRaw(new byte[]{(byte)0x4E, 0x05, 0x00, 0x00, 0x01, 0x02, 0x03, 0x00});
                Toast.makeText(this, "ANC: 深度降噪", Toast.LENGTH_SHORT).show();
                break;
            case 2: // 通透
                controller.sendRaw(new byte[]{(byte)0x4E, 0x05, 0x00, 0x00, 0x01, 0x02, 0x01, 0x00});
                Toast.makeText(this, "ANC: 通透模式", Toast.LENGTH_SHORT).show();
                break;
            default: // 关闭
                currentMode = 0;
                controller.sendRaw(new byte[]{(byte)0x4E, 0x05, 0x00, 0x00, 0x01, 0x02, 0x00, 0x00});
                Toast.makeText(this, "ANC: 关闭", Toast.LENGTH_SHORT).show();
                break;
        }
        updateTileUI();
    }

    private void updateTileUI() {
        Tile tile = getQsTile();
        if (tile != null) {
            if (currentMode == 0) {
                tile.setState(Tile.STATE_INACTIVE);
                tile.setLabel("ANC: 关");
            } else {
                tile.setState(Tile.STATE_ACTIVE);
                tile.setLabel(currentMode == 1 ? "ANC: 降噪" : "ANC: 通透");
            }
            tile.updateTile();
        }
    }
}