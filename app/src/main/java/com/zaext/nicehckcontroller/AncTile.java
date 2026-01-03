package com.zaext.nicehckcontroller;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

import androidx.annotation.RequiresApi;

import com.elvishew.xlog.XLog;

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
                XLog.e("AncTile", "等待连接中断", e);
            }

            if (controller.isConnected()) {
                // 查询当前 ANC 模式
                controller.queryAncMode();

                // 如果已有缓存状态，立即更新 UI
                mainHandler.post(() -> {
                    NiceHckProtocol.AncMode mode = controller.getAncMode();
                    if (mode != NiceHckProtocol.AncMode.OFF) {
                        updateTileUI(mode);
                    }
                });
            } else {
                // 连接失败，显示未知状态
                mainHandler.post(() -> updateTileUI(null));
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
                updateTileUI(null);
                return;
            }
        }

        if (controller.getFirmwareSubVersion() ==  -1) {
            controller.queryFirmwareVersion();
        }

        // 获取当前模式
        NiceHckProtocol.AncMode currentMode = controller.getAncMode();
        NiceHckProtocol.AncMode nextMode;

        // 循环切换：关闭(00) → 深度降噪(03) → 通透(01) → 关闭
        switch (currentMode) {
            case OFF: // 关闭 → 深度降噪
                nextMode = NiceHckProtocol.AncMode.DEEP;
                break;
            case DEEP: // 深度降噪 → 通透
                nextMode = NiceHckProtocol.AncMode.TRANSPARENT;
                break;
            default: // 其他状态 → 关闭
                nextMode = NiceHckProtocol.AncMode.OFF;
                break;
        }
        controller.setAncMode(nextMode);
        Toast.makeText(this, "ANC: " + nextMode.label, Toast.LENGTH_SHORT).show();

        // 发送查询指令确认状态（延迟200ms等待设备处理）
        mainHandler.postDelayed(() -> controller.queryAncMode(), 200);
    }

    /**
     * TileStateListener 回调 - 在后台线程调用
     */
    @Override
    public void onAncModeChanged(NiceHckProtocol.AncMode mode) {
        // 切换到主线程更新 UI
        mainHandler.post(() -> updateTileUI(mode));
    }

    /**
     * 更新磁贴 UI（主线程）
     */
    private void updateTileUI(NiceHckProtocol.AncMode mode) {
        Tile tile = getQsTile();
        if (tile == null) return;

        switch (mode) {
            case OFF: // 关闭
                tile.setState(Tile.STATE_INACTIVE);
                tile.setLabel("ANC: 关");
                break;
            case TRANSPARENT: // 通透
                tile.setState(Tile.STATE_ACTIVE);
                tile.setLabel("ANC: 通透");
                break;
            case NORMAL: // 普通降噪
                tile.setState(Tile.STATE_ACTIVE);
                tile.setLabel("ANC: 普通");
                break;
            case DEEP: // 深度降噪
                tile.setState(Tile.STATE_ACTIVE);
                tile.setLabel("ANC: 深度");
                break;
            case EXPERIMENT: // 实验性降噪
                tile.setState(Tile.STATE_ACTIVE);
                tile.setLabel("ANC: 实验");
                break;
            case WIND_SUPPRESSION: // 风噪抑制
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