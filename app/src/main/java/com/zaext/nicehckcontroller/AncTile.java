package com.zaext.nicehckcontroller;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

import androidx.annotation.RequiresApi;

@RequiresApi(api = Build.VERSION_CODES.N)
public class AncTile extends TileService implements BluetoothController.TileStateListener {

    private BluetoothController controller;
    private Handler mainHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        controller = BluetoothController.getInstance(this);
        mainHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public void onStartListening() {
        super.onStartListening();

        // 注册监听器
        controller.addTileListener(this);

        // 尝试连接并查询当前状态
        new Thread(() -> {
            if (!controller.isConnected()) {
                controller.connectDefaultDevice();
            }

            // 等待连接建立
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            if (controller.isConnected()) {
                // 查询当前 ANC 模式
                controller.queryAncMode();

                // 如果已有缓存状态，立即更新 UI
                mainHandler.post(() -> {
                    int mode = controller.getAncMode();
                    if (mode >= 0) {
                        updateTileUI(mode);
                    }
                });
            } else {
                // 连接失败，显示未知状态
                mainHandler.post(() -> updateTileUI(-1));
            }
        }).start();
    }

    @Override
    public void onStopListening() {
        super.onStopListening();
        // 注销监听器
        controller.removeTileListener(this);
    }

    @Override
    public void onClick() {
        if (!controller.isConnected()) {
            if (!controller.connectDefaultDevice()) {
                Toast.makeText(this, "未连接耳机，请先确保耳机已配对", Toast.LENGTH_SHORT).show();
                updateTileUI(-1);
                return;
            }
        }

        // 获取当前模式
        int currentMode = controller.getAncMode();
        int nextMode;

        // 循环切换：关闭(00) → 深度降噪(03) → 通透(01) → 关闭
        switch (currentMode) {
            case 0x00: // 关闭 → 深度降噪
                nextMode = 0x03;
                controller.sendRaw(new byte[]{(byte)0x4E, 0x05, 0x00, 0x00, 0x01, 0x02, 0x03, 0x00});
                Toast.makeText(this, "ANC: 深度降噪", Toast.LENGTH_SHORT).show();
                break;
            case 0x03: // 深度降噪 → 通透
                nextMode = 0x01;
                controller.sendRaw(new byte[]{(byte)0x4E, 0x05, 0x00, 0x00, 0x01, 0x02, 0x01, 0x00});
                Toast.makeText(this, "ANC: 通透模式", Toast.LENGTH_SHORT).show();
                break;
            default: // 其他状态 → 关闭
                nextMode = 0x00;
                controller.sendRaw(new byte[]{(byte)0x4E, 0x05, 0x00, 0x00, 0x01, 0x02, 0x00, 0x00});
                Toast.makeText(this, "ANC: 关闭", Toast.LENGTH_SHORT).show();
                break;
        }

        // 发送查询指令确认状态（延迟200ms等待设备处理）
        mainHandler.postDelayed(() -> controller.queryAncMode(), 200);
    }

    /**
     * TileStateListener 回调 - 在后台线程调用
     */
    @Override
    public void onAncModeChanged(int mode) {
        // 切换到主线程更新 UI
        mainHandler.post(() -> updateTileUI(mode));
    }

    /**
     * 更新磁贴 UI（主线程）
     */
    private void updateTileUI(int mode) {
        Tile tile = getQsTile();
        if (tile == null) return;

        switch (mode) {
            case 0x00: // 关闭
                tile.setState(Tile.STATE_INACTIVE);
                tile.setLabel("ANC: 关");
                break;
            case 0x01: // 通透
                tile.setState(Tile.STATE_ACTIVE);
                tile.setLabel("ANC: 通透");
                break;
            case 0x02: // 普通降噪
                tile.setState(Tile.STATE_ACTIVE);
                tile.setLabel("ANC: 普通");
                break;
            case 0x03: // 深度降噪
                tile.setState(Tile.STATE_ACTIVE);
                tile.setLabel("ANC: 深度");
                break;
            case 0x10: // 实验性降噪
                tile.setState(Tile.STATE_ACTIVE);
                tile.setLabel("ANC: 实验");
                break;
            case 0x11: // 风噪抑制
                tile.setState(Tile.STATE_ACTIVE);
                tile.setLabel("ANC: 抗风");
                break;
            default: // 未知状态
                tile.setState(Tile.STATE_UNAVAILABLE);
                tile.setLabel("ANC: 未知");
                break;
        }
        tile.updateTile();
    }
}