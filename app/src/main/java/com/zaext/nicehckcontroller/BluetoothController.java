package com.zaext.nicehckcontroller; // 替换你的包名

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;
import androidx.core.app.ActivityCompat;
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

    private Context appContext;

    // 设备状态
    private volatile int leftBatteryLevel = -1;  // -1 表示未知
    private volatile int rightBatteryLevel = -1;
    private volatile int ancMode = -1;  // 00=关, 01=通透, 02=普通, 03=深度, 10=实验, 11=风噪

    // 状态监听器（用于 MainActivity 等 UI）
    public interface StateListener {
        void onBatteryChanged(int left, int right);
        void onAncModeChanged(int mode);
    }
    private StateListener stateListener;

    // Tile 状态监听器（用于 Quick Settings Tiles）
    public interface TileStateListener {
        void onAncModeChanged(int mode);
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
        Log.d(TAG, "开始尝试自动连接...");

        if (isConnected()) {
            Log.d(TAG, "当前已连接，无需重连");
            return true;
        }

        if (appContext == null) {
            Log.e(TAG, "错误：Context 为空，无法检查权限");
            return false;
        }

        if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "错误：缺少 BLUETOOTH_CONNECT 权限");
            return false;
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Log.e(TAG, "错误：蓝牙未开启或不支持");
            return false;
        }

        Set<BluetoothDevice> devices = adapter.getBondedDevices();
        BluetoothDevice targetDevice = null;

        // 打印所有已配对设备，方便调试
        Log.d(TAG, "已配对设备列表:");
        for (BluetoothDevice d : devices) {
            String name = d.getName();
            Log.d(TAG, " - " + name + " (" + d.getAddress() + ")");
            // 这里的判断条件要确保能覆盖你的耳机名字
            if (name != null && (name.contains("YUANDAO") || name.contains("OriG"))) {
                targetDevice = d;
            }
        }

        if (targetDevice == null) {
            Log.e(TAG, "错误：未找到名称包含 NiceHCK/EB2S/HCK 的已配对设备！");
            return false;
        }

        Log.d(TAG, "找到目标设备: " + targetDevice.getName() + "，准备连接...");

        try {
            // 使用标准安全连接
            socket = targetDevice.createRfcommSocketToServiceRecord(SPP_UUID);
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
            socket.connect();
            outputStream = socket.getOutputStream();
            inputStream = socket.getInputStream();
            startReceiving();
            Log.d(TAG, ">>> 连接成功！ <<<");
            return true;
        } catch (IOException e) {
            Log.e(TAG, "标准连接失败: " + e.getMessage());
            // 尝试备选方案：Insecure
            try {
                Log.d(TAG, "尝试 Insecure 连接...");
                socket = targetDevice.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();
                outputStream = socket.getOutputStream();
                inputStream = socket.getInputStream();
                startReceiving();
                Log.d(TAG, ">>> Insecure 连接成功！ <<<");
                return true;
            } catch (IOException e2) {
                Log.e(TAG, "Insecure 连接也失败: " + e2.getMessage());
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
            Log.e(TAG, "关闭连接异常: " + e.getMessage());
        }
        socket = null;
        outputStream = null;
        inputStream = null;
    }

    /**
     * 启动接收数据线程
     */
    private void startReceiving() {
        if (isReceiving) {
            Log.w(TAG, "接收线程已在运行");
            return;
        }

        isReceiving = true;
        receiveThread = new Thread(() -> {
            Log.d(TAG, "接收线程已启动");
            byte[] buffer = new byte[1024];
            byte[] dataBuffer = new byte[2048]; // 用于处理粘包的缓冲区
            int dataBufferPos = 0; // 缓冲区中已有的数据长度

            while (isReceiving && socket != null && socket.isConnected()) {
                try {
                    if (inputStream == null) {
                        Log.e(TAG, "InputStream 为空，停止接收");
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
                        Log.d(TAG, "<<< 接收原始数据 (" + bytes + " bytes): " + hex.toString().trim());

                        // 处理缓冲区中的完整数据包
                        dataBufferPos = processPackets(dataBuffer, dataBufferPos);
                    }
                } catch (IOException e) {
                    if (isReceiving) {
                        Log.e(TAG, "接收数据异常: " + e.getMessage());
                        break;
                    }
                }
            }

            isReceiving = false;
            Log.d(TAG, "接收线程已停止");
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
                Log.w(TAG, "数据格式错误：未找到包头 4E，位置=" + pos);
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
        Log.d(TAG, "<<< 解析数据包 (" + packet.length + " bytes): " + hex.toString().trim());

        // 检查包头
        if ((packet[0] & 0xFF) != 0x4E) {
            return;
        }

        // 电量数据包：4E 06 00 00 05 00 [左] [右] 00
        if (packet.length == 9 &&
            (packet[0] & 0xFF) == 0x4E &&
            (packet[1] & 0xFF) == 0x06 &&
            (packet[2] & 0xFF) == 0x00 &&
            (packet[3] & 0xFF) == 0x00 &&
            (packet[4] & 0xFF) == 0x05 &&
            (packet[5] & 0xFF) == 0x00) {

            int left = packet[6] & 0xFF;
            int right = packet[7] & 0xFF;

            if (left != leftBatteryLevel || right != rightBatteryLevel) {
                leftBatteryLevel = left;
                rightBatteryLevel = right;
                Log.i(TAG, "电量更新: 左=" + left + "%, 右=" + right + "%");

                if (stateListener != null) {
                    stateListener.onBatteryChanged(left, right);
                }
            }
        }
        // ANC模式数据包：4E 04 00 00 01 01 [模式]
        else if (packet.length == 7 &&
                 (packet[0] & 0xFF) == 0x4E &&
                 (packet[1] & 0xFF) == 0x04 &&
                 (packet[2] & 0xFF) == 0x00 &&
                 (packet[3] & 0xFF) == 0x00 &&
                 (packet[4] & 0xFF) == 0x01 &&
                 (packet[5] & 0xFF) == 0x01) {

            int mode = packet[6] & 0xFF;

            if (mode != ancMode) {
                ancMode = mode;
                String modeName = getAncModeName(mode);
                Log.i(TAG, "ANC模式更新: " + modeName + " (0x" + String.format("%02X", mode) + ")");

                // 通知主 UI 监听器
                if (stateListener != null) {
                    stateListener.onAncModeChanged(mode);
                }

                // 通知所有 Tile 监听器
                notifyTileListeners(mode);
            }
        }
    }

    /**
     * 获取ANC模式的名称
     */
    private String getAncModeName(int mode) {
        switch (mode) {
            case 0x00: return "关闭";
            case 0x01: return "通透";
            case 0x02: return "普通降噪";
            case 0x03: return "深度降噪";
            case 0x10: return "实验性降噪";
            case 0x11: return "风噪抑制";
            default: return "未知(0x" + String.format("%02X", mode) + ")";
        }
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
                Log.w(TAG, "停止接收线程被中断: " + e.getMessage());
            }
            receiveThread = null;
        }
    }

    public void sendRaw(byte[] packet) {
        Log.d(TAG, "准备发送指令...");

        if (!isConnected()) {
            Log.w(TAG, "当前未连接，尝试自动重连...");
            if (!connectDefaultDevice()) {
                Log.e(TAG, "自动重连失败，指令发送取消");
                return;
            }
        }

        if (outputStream == null) {
            Log.e(TAG, "错误：OutputStream 为空，无法写入数据");
            return;
        }

        try {
            // 直接发送原始数据包
            outputStream.write(packet);
            outputStream.flush();

            StringBuilder hex = new StringBuilder();
            for (byte b : packet) hex.append(String.format("%02X ", b));
            Log.d(TAG, ">>> 发送成功 (Raw): " + hex);

        } catch (IOException e) {
            Log.e(TAG, "发送异常: " + e.getMessage(), e);
            close();
        }
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
        sendRaw(new byte[]{(byte)0x4E, 0x03, 0x00, 0x00, 0x01, 0x01});
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
    public int getAncMode() {
        return ancMode;
    }

    /**
     * 获取ANC模式名称
     * @return 模式名称字符串
     */
    public String getAncModeName() {
        return getAncModeName(ancMode);
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
            Log.d(TAG, "Tile 监听器已注册，当前总数: " + tileListeners.size());
        }
    }

    /**
     * 移除 Tile 状态监听器
     */
    public void removeTileListener(TileStateListener listener) {
        if (listener != null) {
            tileListeners.remove(listener);
            Log.d(TAG, "Tile 监听器已移除，当前总数: " + tileListeners.size());
        }
    }

    /**
     * 通知所有 Tile 监听器 ANC 模式变化
     */
    private void notifyTileListeners(int mode) {
        for (TileStateListener listener : tileListeners) {
            if (listener != null) {
                listener.onAncModeChanged(mode);
            }
        }
    }
}