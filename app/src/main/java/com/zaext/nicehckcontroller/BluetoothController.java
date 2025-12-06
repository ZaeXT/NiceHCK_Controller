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
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Set;
import java.util.UUID;

public class BluetoothController {
    private static BluetoothController instance;
    private BluetoothSocket socket;
    private OutputStream outputStream;
    // 这里的 UUID 必须是你之前确认有效的那个
    private static final UUID SPP_UUID = UUID.fromString("0000a100-1000-8000-4e48-434b4354524c");
    private static final String TAG = "NiceHCK_Controller";

    private Context appContext;

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
        try {
            if (outputStream != null) outputStream.close();
            if (socket != null) socket.close();
        } catch (IOException e) {}
        socket = null;
        outputStream = null;
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
            // **关键修改**：直接发送 Frida 抓到的原始数据包，不再添加任何额外的头！
            outputStream.write(packet);
            outputStream.flush();

            StringBuilder hex = new StringBuilder();
            for (byte b : packet) hex.append(String.format("%02X ", b));
            Log.d(TAG, ">>> 发送成功 (Raw): " + hex.toString());

        } catch (IOException e) {
            Log.e(TAG, "发送异常: " + e.getMessage());
            e.printStackTrace();
            close();
        }
    }
}