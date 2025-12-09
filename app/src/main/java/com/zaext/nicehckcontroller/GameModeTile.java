package com.zaext.nicehckcontroller; // 替换你的包名

import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

import androidx.annotation.RequiresApi;

@RequiresApi(api = Build.VERSION_CODES.N)
public class GameModeTile extends TileService {

    private boolean isGameModeOn = false; // 用于记录状态

    @Override
    public void onStartListening() {
        super.onStartListening();
        // 当下拉通知栏，磁贴可见时调用
        updateTileState();
        // TODO 更新当前状态
    }

    @Override
    public void onClick() {
        super.onClick();

        // 获取单例控制器
        BluetoothController controller = BluetoothController.getInstance(this);

        // 切换状态
        isGameModeOn = !isGameModeOn;

        // 发送指令
        if (isGameModeOn) {
            // 开启游戏模式: 4E 04 ... 06 02 01
            controller.sendRaw(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x06, 0x02, 0x01});
            Toast.makeText(this, "游戏模式: 开", Toast.LENGTH_SHORT).show();
        } else {
            // 关闭游戏模式: 4E 04 ... 06 02 00
            controller.sendRaw(new byte[]{(byte)0x4E, 0x04, 0x00, 0x00, 0x06, 0x02, 0x00});
            Toast.makeText(this, "游戏模式: 关", Toast.LENGTH_SHORT).show();
        }

        // 更新磁贴UI
        updateTileState();
    }

    private void updateTileState() {
        Tile tile = getQsTile();
        if (tile != null) {
            // 设置状态：Active(高亮) 或 Inactive(灰色)
            tile.setState(isGameModeOn ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);

            // 设置标签
            tile.setLabel(isGameModeOn ? "NiceHCK: 游戏" : "NiceHCK: 音乐");

            // 刷新显示
            tile.updateTile();
        }
    }
}