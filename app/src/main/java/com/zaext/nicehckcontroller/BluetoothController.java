package com.zaext.nicehckcontroller; // 替换你的包名

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

import com.elvishew.xlog.XLog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;

public class BluetoothController {
    private static BluetoothController instance;
    private BluetoothSocket socket;
    private OutputStream outputStream;
    private InputStream inputStream;
    private Thread receiveThread;
    private volatile boolean isReceiving = false;

    // 这里的 UUID 必须是你之前确认有效的那个
    private static final UUID SPP_UUID = UUID.fromString("0000a100-1000-8000-4e48-434b4354524c");
    private static final String TAG = "NiceHCK_Controller";

    private volatile int firmwareMainVersion = -1;
    private volatile int firmwareSubVersion = -1;

    private Context appContext;

    // 设备状态
    private volatile int leftBatteryLevel = -1;  // -1 表示未知
    private volatile int rightBatteryLevel = -1;

    private volatile int caseBatteryLevel = -1;
    private volatile NiceHckProtocol.AncMode ancMode = NiceHckProtocol.AncMode.OFF;
    // 状态监听器（用于 MainActivity 等 UI）
    public interface StateListener {
        void onBatteryChanged(int left, int right, int caseLevel);
        void onAncModeChanged(NiceHckProtocol.AncMode mode);
        void onEqModeChanged(NiceHckProtocol.EqMode mode);
        void onGameModeChanged(boolean enabled);
        void onLowLatencyChanged(boolean enabled);
        void onDualConnChanged(boolean enabled);
        void onWindSuppressionChanged(boolean enabled);
    }
    private StateListener stateListener;

    // 固件版本监听器
    public interface FirmwareListener {
        void onFirmwareVersionReceived(int mainVersion, int subVersion);
    }
    private FirmwareListener firmwareListener;

    // Tile 状态监听器（用于 Quick Settings Tiles）
    public interface TileStateListener {
        void onAncModeChanged(NiceHckProtocol.AncMode mode);
    }
    private final java.util.List<TileStateListener> tileListeners = new java.util.ArrayList<>();

    private BluetoothController() {}

    public static synchronized BluetoothController getInstance(Context context) {
        if (instance == null) {
            instance = new BluetoothController();
        }
        if (context != null) {
            instance.appContext = context.getApplicationContext();
        }
        return instance;
    }

    public boolean connectDefaultDevice() {
        XLog.d( "开始尝试自动连接...");

        if (isConnected()) {
            XLog.d("当前已连接，无需重连");
            return true;
        }

        if (appContext == null) {
            XLog.e("错误：Context 为空，无法检查权限");
            return false;
        }

        if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            XLog.e("错误：缺少 BLUETOOTH_CONNECT 权限");
            return false;
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            XLog.e("错误：蓝牙未开启或不支持");
            return false;
        }

        Set<BluetoothDevice> devices = adapter.getBondedDevices();
        BluetoothDevice targetDevice = null;

        // 打印所有已配对设备，方便调试
        XLog.d("已配对设备列表:");
        for (BluetoothDevice d : devices) {
            String name = d.getName();
            XLog.d(" - " + name + " (" + d.getAddress() + ")");
            // 这里的判断条件要确保能覆盖你的耳机名字
            if (name != null && (name.contains("YUANDAO") || name.contains("OriG"))) {
                targetDevice = d;
            }
        }

        if (targetDevice == null) {
            XLog.e("错误：未找到名称包含 NiceHCK/EB2S/HCK 的已配对设备！");
            return false;
        }

        XLog.d("找到目标设备: " + targetDevice.getName() + "，准备连接...");

        try {
            // 使用标准安全连接
            socket = targetDevice.createRfcommSocketToServiceRecord(SPP_UUID);
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
            socket.connect();
            outputStream = socket.getOutputStream();
            inputStream = socket.getInputStream();
            startReceiving();
            XLog.d(">>> 连接成功！ <<<");
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                queryFirmwareVersion();
            }, 200);
            return true;
        } catch (IOException e) {
            XLog.e("标准连接失败: " + e.getMessage());
            // 尝试备选方案：Insecure
            try {
                XLog.d("尝试 Insecure 连接...");
                socket = targetDevice.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();
                outputStream = socket.getOutputStream();
                inputStream = socket.getInputStream();
                startReceiving();
                XLog.d(">>> Insecure 连接成功！ <<<");
                return true;
            } catch (IOException e2) {
                XLog.e("Insecure 连接也失败: " + e2.getMessage());
            }
            close();
            return false;
        }
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected();
    }

    public void close() {
        stopReceiving();
        try {
            if (inputStream != null) inputStream.close();
            if (outputStream != null) outputStream.close();
            if (socket != null) socket.close();
        } catch (IOException e) {
            XLog.e("关闭连接异常: " + e.getMessage());
        }
        socket = null;
        outputStream = null;
        inputStream = null;
        this.firmwareMainVersion = -1;
        this.firmwareSubVersion = -1;
        if (firmwareListener != null) {
            firmwareListener.onFirmwareVersionReceived(-1, -1);
        }
    }

    /**
     * 启动接收数据线程
     */
    private void startReceiving() {
        if (isReceiving) {
            XLog.w("接收线程已在运行");
            return;
        }

        isReceiving = true;
        receiveThread = new Thread(() -> {
            XLog.d("接收线程已启动");
            byte[] buffer = new byte[1024];
            byte[] dataBuffer = new byte[2048]; // 用于处理粘包的缓冲区
            int dataBufferPos = 0; // 缓冲区中已有的数据长度

            while (isReceiving && socket != null && socket.isConnected()) {
                try {
                    if (inputStream == null) {
                        XLog.e("InputStream 为空，停止接收");
                        break;
                    }

                    // 读取数据
                    int bytes = inputStream.read(buffer);
                    if (bytes > 0) {
                        // 将新数据追加到数据缓冲区
                        System.arraycopy(buffer, 0, dataBuffer, dataBufferPos, bytes);
                        dataBufferPos += bytes;

                        // 打印原始接收数据
                        StringBuilder hex = new StringBuilder();
                        for (int i = 0; i < bytes; i++) {
                            hex.append(String.format("%02X ", buffer[i]));
                        }
                        XLog.d("<<< 接收原始数据 (" + bytes + " bytes): " + hex.toString().trim());

                        // 处理缓冲区中的完整数据包
                        dataBufferPos = processPackets(dataBuffer, dataBufferPos);
                    }
                } catch (IOException e) {
                    if (isReceiving) {
                        XLog.e("接收数据异常: " + e.getMessage());
                        break;
                    }
                }
            }

            isReceiving = false;
            XLog.d("接收线程已停止");
        });
        receiveThread.start();
    }

    /**
     * 处理缓冲区中的数据包（处理粘包）
     * @param buffer 数据缓冲区
     * @param length 缓冲区中有效数据的长度
     * @return 处理后剩余数据的长度
     */
    private int processPackets(byte[] buffer, int length) {
        int pos = 0;

        while (pos < length) {
            // 查找包头 0x4E
            if ((buffer[pos] & 0xFF) != 0x4E) {
                XLog.w("数据格式错误：未找到包头 4E，位置=" + pos);
                pos++;
                continue;
            }

            // 至少需要4个字节才能读取长度
            if (pos + 3 >= length) {
                // 数据不完整，保留剩余数据
                break;
            }

            // 读取长度字段（总包长 = length + 3）
            int packetLength = (buffer[pos + 1] & 0xFF) + 3;

            // 检查是否有完整的数据包
            if (pos + packetLength > length) {
                // 数据包不完整，保留剩余数据
                break;
            }

            // 提取完整数据包
            byte[] packet = new byte[packetLength];
            System.arraycopy(buffer, pos, packet, 0, packetLength);

            // 解析数据包
            parsePacket(packet);

            // 移动到下一个数据包
            pos += packetLength;
        }

        // 将未处理的数据移到缓冲区开头
        if (pos < length) {
            int remaining = length - pos;
            System.arraycopy(buffer, pos, buffer, 0, remaining);
            return remaining;
        }

        return 0;
    }

    /**
     * 解析单个完整的数据包
     */
    private void parsePacket(byte[] packet) {
        if (packet.length < 4) {
            return;
        }

        // 打印完整数据包
        StringBuilder hex = new StringBuilder();
        for (byte b : packet) {
            hex.append(String.format("%02X ", b));
        }
        XLog.d("<<< 解析数据包 (" + packet.length + " bytes): " + hex.toString().trim());

        // 检查包头
        if ((packet[0] & 0xFF) != 0x4E) {
            return;
        }

        int opCode = (packet[5] & 0xFF) << 8 | (packet[4] & 0xFF);
        XLog.d("解析 OpCode: 0x" + String.format("%04X", opCode));

        switch (opCode) {
            case 0x0005: // 电量数据包
                leftBatteryLevel = packet[6] & 0xFF;
                rightBatteryLevel = packet[7] & 0xFF;
                if ((packet[8] & 0xFF) != 0) {
                    caseBatteryLevel = packet[8] & 0xFF;
                } else {
                    XLog.i("case battery unknown, use last: " + caseBatteryLevel + "%");
                }
                XLog.i("电量更新: 左=" + leftBatteryLevel + "%, 右=" + rightBatteryLevel + "%, 盒子=" + caseBatteryLevel + "%");
                if (stateListener != null) {
                    stateListener.onBatteryChanged(leftBatteryLevel, rightBatteryLevel, caseBatteryLevel);
                }
                break;
            case 0x0101: //  ANC模式数据包
                int modeValue = packet[6] & 0xFF;
                NiceHckProtocol.AncMode mode = NiceHckProtocol.AncMode.fromValue(modeValue);
                XLog.i("ANC模式更新: " + mode.label + " (0x" + String.format("%02X", mode.value) + ")");
                if (stateListener != null) {
                    stateListener.onAncModeChanged(mode);
                }
                notifyTileListeners(mode);
                break;
            case 0x0003: //    固件版本数据包
                this.firmwareSubVersion = packet[6] & 0xFF;
                this.firmwareMainVersion = packet[7] & 0xFF;
                XLog.i("固件版本: " + firmwareMainVersion + "." + firmwareSubVersion);
                if (firmwareListener != null) {
                    firmwareListener.onFirmwareVersionReceived(firmwareMainVersion, firmwareSubVersion);
                }
                break;
            case 0x0107: // EQ模式数据包
                int eqValue = packet[6] & 0xFF;
                NiceHckProtocol.EqMode eqMode1 = NiceHckProtocol.EqMode.fromValue(eqValue);
                XLog.i("EQ模式更新: " + eqMode1.label + " (0x" + String.format("%02X", eqMode1.value) + ")");
                if (stateListener != null) {
                    stateListener.onEqModeChanged(eqMode1);
                }
                break;
            case 0x0108: // 游戏模式响应包
                int gameModeStatus = packet[6] & 0xFF;
                boolean enabled = (gameModeStatus == 0x01);
                if (stateListener != null) {
                    stateListener.onGameModeChanged(enabled);
                }
            case 0x0106: // 低延迟状态响应包
                int lowLatencyStatus = packet[6] & 0xFF;
                boolean lowLatencyEnabled = (lowLatencyStatus == 0x01);
                if (stateListener != null) {
                    stateListener.onLowLatencyChanged(lowLatencyEnabled);
                }
                break;
            case 0x0105: // 双设备连接状态响应包
                int dualConnStatus = packet[6] & 0xFF;
                boolean dualEnabled = (dualConnStatus == 0x01);
                if (stateListener != null) {
                    stateListener.onDualConnChanged(dualEnabled);
                }
                break;
            case 0x01E1: // 抗风噪状态响应包
                int windSuppStatus = packet[6] & 0xFF;
                boolean windSuppEnabled = (windSuppStatus == 0x01);
                if (stateListener != null) {
                    stateListener.onWindSuppressionChanged(windSuppEnabled);
                }

        }

        // 电量数据包：4E 06 00 00 05 00 [左] [右] 00
//        if (packet.length == 9 &&
//            (packet[0] & 0xFF) == 0x4E &&
//            (packet[1] & 0xFF) == 0x06 &&
//            (packet[2] & 0xFF) == 0x00 &&
//            (packet[3] & 0xFF) == 0x00 &&
//            (packet[4] & 0xFF) == 0x05 &&
//            (packet[5] & 0xFF) == 0x00) {
//
//            int left = packet[6] & 0xFF;
//            int right = packet[7] & 0xFF;
//
//            if (left != leftBatteryLevel || right != rightBatteryLevel) {
//                leftBatteryLevel = left;
//                rightBatteryLevel = right;
//                XLog.i("电量更新: 左=" + left + "%, 右=" + right + "%");
//
//                if (stateListener != null) {
//                    stateListener.onBatteryChanged(left, right, caseBatteryLevel);
//                }
//            }
//        }
        // ANC模式数据包：4E 04 00 00 01 01 [模式]
//        else if (packet.length == 7 &&
//                 (packet[0] & 0xFF) == 0x4E &&
//                 (packet[1] & 0xFF) == 0x04 &&
//                 (packet[2] & 0xFF) == 0x00 &&
//                 (packet[3] & 0xFF) == 0x00 &&
//                 (packet[4] & 0xFF) == 0x01 &&
//                 (packet[5] & 0xFF) == 0x01) {
//
//            int modeValue = packet[6] & 0xFF;
//            NiceHckProtocol.AncMode mode = NiceHckProtocol.AncMode.fromValue(modeValue);
//
//            if (mode != ancMode) {
//                ancMode = mode;
//                String modeName = mode.label;
//                XLog.i("ANC模式更新: " + modeName + " (0x" + String.format("%02X", mode.value) + ")");
//
//                // 通知主 UI 监听器
//                if (stateListener != null) {
//                    stateListener.onAncModeChanged(mode);
//                }
//
//                // 通知所有 Tile 监听器
//                notifyTileListeners(mode);
//            }
//        }
        // 固件版本数据包：4E 04 00 00 03 00 [主版本] [子版本]
//        else if (packet.length == 7 &&
//                    (packet[4] & 0xFF) == 0x03 &&
//                    (packet[5] & 0xFF) == 0x00) {
//            firmwareMainVersion = packet[6] & 0xFF;
//            firmwareSubVersion = packet[7] & 0xFF;
//            XLog.i("固件版本: " + firmwareMainVersion + "." + firmwareSubVersion);
//            if (firmwareListener != null) {
//                firmwareListener.onFirmwareVersionReceived(firmwareMainVersion, firmwareSubVersion);
//            }
//        }

    }

    /**
     * 【新增】查询固件版本 (OpCode: 03 00)
     * 发送: 4E 03 00 00 03 00
     */
    public void queryFirmwareVersion() {
        XLog.i("正在查询固件版本...");
        sendRaw(new byte[]{(byte)0x4E, 0x03, 0x00, 0x00, 0x03, 0x00});
    }

    /**
     * 【新增】判断是否为 0408 或更高版本
     */
    public boolean isVersionAtLeast0408() {
        return firmwareMainVersion >= 4 && firmwareSubVersion >= 8;
    }

    public int getFirmwareMainVersion() {
        return firmwareMainVersion;
    }
    public int getFirmwareSubVersion() {
        return firmwareSubVersion;
    }

    /**
     * 停止接收数据线程
     */
    private void stopReceiving() {
        if (!isReceiving) {
            return;
        }

        isReceiving = false;
        if (receiveThread != null && receiveThread.isAlive()) {
            try {
                receiveThread.interrupt();
                receiveThread.join(1000); // 等待最多1秒
            } catch (InterruptedException e) {
                XLog.w("停止接收线程被中断: " + e.getMessage());
            }
            receiveThread = null;
        }
    }

    /**
     * 统一封包发送方法 (核心抽象)
     * @param opCode 功能码
     * @param params 参数部分
     */
    private void sendNiceCommand(int opCode, byte... params) {
        int payloadLength = 3 + params.length; // OpCode + Params
        byte[] packet = new byte[3 + payloadLength]; // Magic(1) + Len(2) + Res(1) + Payload
        packet[0] = 0x4E; // Magic
        packet[1] = (byte)(payloadLength & 0xFF); // Length LSB
        packet[2] = (byte)((payloadLength >> 8) & 0xFF); // Length MSB
        packet[3] = 0x00; // Reserved
        packet[4] = (byte)(opCode & 0xFF); // OpCode LSB
        packet[5] = (byte)((opCode >> 8) & 0xFF); // OpCode MSB
        System.arraycopy(params, 0, packet, 6, params.length); // Params
        XLog.d("准备发送指令... OpCode=0x" + String.format("%04X", opCode));
        sendRaw(packet);
    }

    public void sendRaw(byte[] packet) {
        XLog.d("准备发送指令...");

        if (!isConnected()) {
            XLog.w("当前未连接，尝试自动重连...");
            if (!connectDefaultDevice()) {
                XLog.e("自动重连失败，指令发送取消");
                return;
            }
        }

        if (outputStream == null) {
            XLog.e("错误：OutputStream 为空，无法写入数据");
            return;
        }

        try {
            // 直接发送原始数据包
            outputStream.write(packet);
            outputStream.flush();

            StringBuilder hex = new StringBuilder();
            for (byte b : packet) hex.append(String.format("%02X ", b));
            XLog.d(">>> 发送成功 (Raw): " + hex);

        } catch (IOException e) {
            XLog.e("发送异常: " + e.getMessage(), e);
            close();
        }
    }

    // ==========================================
    // ============ 上层语义化调用接口 ============
    // ==========================================
    public void setAncMode(NiceHckProtocol.AncMode mode) {
        XLog.i("设置ANC模式为: " + mode.label);
        sendNiceCommand(NiceHckProtocol.Op.ANC_SET, (byte)mode.value, (byte)0x00);
        syncStatus(NiceHckProtocol.Op.ANC_QUERY);
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            queryAncMode();
        }, 100);
    }

    public void setEqMode(NiceHckProtocol.EqMode mode) {
        XLog.i("设置EQ模式为: " + mode.label);
        if (firmwareSubVersion < 8) {
            if (mode == NiceHckProtocol.EqMode.FINE || mode == NiceHckProtocol.EqMode.VOCAL) {
                Toast.makeText(appContext, "当前固件版本过低，不支持选择 " + mode.label + " 模式", Toast.LENGTH_SHORT).show();
                XLog.w("当前固件版本过低，不支持选择 " + mode.label + " 模式");
                syncStatus(NiceHckProtocol.Op.EQ_QUERY);
                return;
            }
        }
        sendNiceCommand(NiceHckProtocol.Op.EQ_SET, (byte)mode.value);
        syncStatus(NiceHckProtocol.Op.EQ_QUERY);
    }

    public void setFeatureMode(NiceHckProtocol.Feature feature, boolean enable) {
        XLog.i((enable ? "启用" : "禁用") + "功能: " + feature.name());
        sendNiceCommand(feature.opCode, (byte)(enable ? 0x01 : 0x00));
        syncStatus(0x0108); // 查询低延迟状态
    }

    public void setCodec(NiceHckProtocol.Codec codec) {
        XLog.i("设置编解码器为: " + codec.name());
        sendNiceCommand(NiceHckProtocol.Op.CODEC, (byte)codec.value);
    }

    // ==========================================
    // ============ 查询功能 ===================
    // ==========================================

    /**
     * 查询电量
     * 发送: 4E 03 00 00 05 00
     * 返回: 4E 06 00 00 05 00 [左] [右] 00
     */
    public void queryBattery() {
        sendRaw(new byte[]{(byte)0x4E, 0x03, 0x00, 0x00, 0x05, 0x00});
    }

    /**
     * 查询ANC模式
     * 发送: 4E 03 00 00 01 01
     * 返回: 4E 04 00 00 01 01 [模式]
     */
    public void queryAncMode() {
//        sendRaw(new byte[]{(byte)0x4E, 0x03, 0x00, 0x00, 0x01, 0x01});
        sendNiceCommand(NiceHckProtocol.Op.ANC_QUERY);
    }
    public void queryEqMode() {
        sendNiceCommand(NiceHckProtocol.Op.EQ_QUERY);
    }
    public void queryGameMode() {
        sendNiceCommand(NiceHckProtocol.Op.GAME_MODE_QUERY);
    }
    public void queryLowLatencyMode() {
        sendNiceCommand(NiceHckProtocol.Op.LOW_LATENCY_QUERY);
    }
    public void queryDualConnMode() {
        sendNiceCommand(NiceHckProtocol.Op.DUAL_CONN_QUERY);
    }
    public void queryWindSuppressionMode() {
        sendNiceCommand(NiceHckProtocol.Op.WIND_SUPPRESSION_QUERY);
    }

    // ==========================================
    // ============ 状态获取 ===================
    // ==========================================

    /**
     * 获取左耳电量
     * @return 电量百分比，-1表示未知
     */
    public int getLeftBatteryLevel() {
        return leftBatteryLevel;
    }

    /**
     * 获取右耳电量
     * @return 电量百分比，-1表示未知
     */
    public int getRightBatteryLevel() {
        return rightBatteryLevel;
    }

    /**
     * 获取ANC模式
     * @return 模式值（00=关, 01=通透, 02=普通, 03=深度, 10=实验, 11=风噪），-1表示未知
     */
    public NiceHckProtocol.AncMode getAncMode() {
        return ancMode;
    }

    private void syncStatus(int OpCode) {
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            sendNiceCommand(OpCode);
        },100);
    }

    public void syncAllStatus() {
        if (!isConnected()) {
            XLog.w("当前未连接，无法同步状态");
            return;
        }
        XLog.i("同步所有状态...");
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        handler.post(() -> queryFirmwareVersion());
    }

    /**
     * 设置状态监听器
     */
    public void setStateListener(StateListener listener) {
        this.stateListener = listener;
    }

    // ==========================================
    // ============ Tile 监听器管理 =============
    // ==========================================

    /**
     * 添加 Tile 状态监听器
     */
    public void addTileListener(TileStateListener listener) {
        if (listener != null && !tileListeners.contains(listener)) {
            tileListeners.add(listener);
            XLog.d("Tile 监听器已注册，当前总数: " + tileListeners.size());
        }
    }

    /**
     * 移除 Tile 状态监听器
     */
    public void removeTileListener(TileStateListener listener) {
        if (listener != null) {
            tileListeners.remove(listener);
            XLog.d("Tile 监听器已移除，当前总数: " + tileListeners.size());
        }
    }

    /**
     * 通知所有 Tile 监听器 ANC 模式变化
     */
    private void notifyTileListeners(NiceHckProtocol.AncMode mode) {
        for (TileStateListener listener : tileListeners) {
            if (listener != null) {
                listener.onAncModeChanged(mode);
            }
        }
    }

    // 设置固件版本监听器
    public void setFirmwareListener(FirmwareListener listener) {
        this.firmwareListener = listener;
        if (firmwareSubVersion != -1 && firmwareMainVersion != -1 && listener != null) {
            listener.onFirmwareVersionReceived(firmwareMainVersion, firmwareSubVersion);
        }
    }
}