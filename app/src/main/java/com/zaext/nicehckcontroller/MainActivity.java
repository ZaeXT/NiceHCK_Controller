package com.zaext.nicehckcontroller; // 确保这里的包名和你自己的一致！

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 101;
    // 这是 SPP 的标准 UUID
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;
    private OutputStream outputStream;

    private Spinner spinnerDevices;
    private TextView textStatus;

    private ArrayList<BluetoothDevice> pairedDevicesList = new ArrayList<>();
    private ArrayList<String> pairedDeviceNames = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        spinnerDevices = findViewById(R.id.spinner_devices);
        textStatus = findViewById(R.id.text_status);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "该设备不支持蓝牙", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        checkAndRequestPermissions();
    }

    // --- 权限处理 ---
    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN}, PERMISSION_REQUEST_CODE);
            } else {
                listPairedDevices();
            }
        } else {
            // Android 11 及以下，权限在安装时授予
            listPairedDevices();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                listPairedDevices();
            } else {
                Toast.makeText(this, "需要蓝牙权限才能使用！", Toast.LENGTH_LONG).show();
            }
        }
    }

    // --- 蓝牙逻辑 ---
    private void listPairedDevices() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return; // 权限检查
        }
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        pairedDevicesList.clear();
        pairedDeviceNames.clear();

        if (pairedDevices.size() > 0) {
            for (BluetoothDevice device : pairedDevices) {
                pairedDevicesList.add(device);
                pairedDeviceNames.add(device.getName());
            }
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, pairedDeviceNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerDevices.setAdapter(adapter);
    }

    public void connectDevice(View view) {
        int selectedPosition = spinnerDevices.getSelectedItemPosition();
        if (selectedPosition < 0 || selectedPosition >= pairedDevicesList.size()) {
            Toast.makeText(this, "请先选择一个设备", Toast.LENGTH_SHORT).show();
            return;
        }

        BluetoothDevice device = pairedDevicesList.get(selectedPosition);
        textStatus.setText("状态：正在连接...");

        new Thread(() -> {
            try {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    return;
                }
                bluetoothSocket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
                bluetoothSocket.connect();
                outputStream = bluetoothSocket.getOutputStream();
                runOnUiThread(() -> textStatus.setText("状态：已连接到 " + device.getName()));
            } catch (IOException e) {
                // 打印详细错误到 Logcat
                Log.e("BluetoothConnect", "连接失败: " + e.getMessage());
                e.printStackTrace();
                runOnUiThread(() -> {
                    textStatus.setText("状态：连接失败");
                    Toast.makeText(MainActivity.this, "连接失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
                try {
                    if (bluetoothSocket != null) {
                        bluetoothSocket.close();
                    }
                } catch (IOException closeException) {
                    closeException.printStackTrace();
                }
            }
        }).start();
    }

    // --- 核心指令发送 ---
    private void sendCommand(byte[] payload) {
        if (outputStream == null) {
            Toast.makeText(this, "请先连接耳机", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            byte type = (byte) 0x85; // DATA channel
            int length = payload.length;

            ByteBuffer buffer = ByteBuffer.allocate(5 + length);
            buffer.order(ByteOrder.LITTLE_ENDIAN); // 小端序

            buffer.put(type);
            buffer.putInt(length);
            buffer.put(payload);

            byte[] packet = buffer.array();

            outputStream.write(packet);
            outputStream.flush();
            Toast.makeText(this, "指令已发送", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "发送失败", Toast.LENGTH_SHORT).show();
        }
    }

    // --- 按钮点击事件 ---
    public void setAncDeep(View view) {
        sendCommand(new byte[]{(byte) 0xFF, 0x03, (byte) 1});
    }

    public void setAncTransparency(View view) {
        sendCommand(new byte[]{(byte) 0xFF, 0x03, (byte) 2});
    }

    public void setAncOff(View view) {
        sendCommand(new byte[]{(byte) 0xFF, 0x03, (byte) 0});
    }

    public void setGameModeOn(View view) {
        sendCommand(new byte[]{(byte) 0xFF, 0x06, (byte) 1});
    }

    public void setGameModeOff(View view) {
        sendCommand(new byte[]{(byte) 0xFF, 0x06, (byte) 0});
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (outputStream != null) {
                outputStream.close();
            }
            if (bluetoothSocket != null) {
                bluetoothSocket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}